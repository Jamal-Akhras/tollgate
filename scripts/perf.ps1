<#
Runs the performance matrix from docs/adr/0014-measure-performance.md and writes
everything to perf-results\<date-time>. Run it from the repository root, on mains
power, with other applications closed:

    powershell -ExecutionPolicy Bypass -File scripts\perf.ps1
    powershell -ExecutionPolicy Bypass -File scripts\perf.ps1 -Quick

-Quick runs every scenario once, briefly, to check the harness.
#>
param(
    [switch]$Quick,
    [string]$Output = "perf-results"
)

$ErrorActionPreference = "Stop"
$root = Split-Path $PSScriptRoot -Parent
Set-Location $root

$stubPort = 18434
$gatewayPort = 18080
$managementPort = 19090
$adminPort = 18081
$mvnw = Join-Path $root "mvnw.cmd"

if ($Quick) {
    $warmup = 5; $duration = 15; $repeats = 1; $rates = @(50)
} else {
    $warmup = 30; $duration = 120; $repeats = 3; $rates = @(50, 200, 500)
}
$otherRate = if ($Quick) { 50 } else { 200 }

function Mask([int]$from, [int]$count) {
    [long]$mask = 0
    for ($i = $from; $i -lt $from + $count; $i++) { $mask = $mask -bor ([long]1 -shl $i) }
    return $mask
}

$logical = [Environment]::ProcessorCount
if ($logical -ge 32) {
    $shared = $false
    $tollgateMask = Mask 0 8
    $stubMask = Mask 8 4
    $gatlingMask = Mask 16 ($logical - 16)
} else {
    $shared = $true
    $half = [int]($logical / 2)
    $quarter = [Math]::Max(1, [int]($logical / 4))
    $tollgateMask = Mask 0 $half
    $stubMask = Mask $half $quarter
    $gatlingMask = Mask ($half + $quarter) ($logical - $half - $quarter)
}

Write-Host "Building Tollgate and the benchmarks..."
& $mvnw -q -B -Pbenchmarks -DskipTests install
if ($LASTEXITCODE -ne 0) { throw "The build failed" }
& $mvnw -q -B -Pbenchmarks -pl tollgate-benchmarks dependency:build-classpath "-Dmdep.outputFile=target\classpath.txt" "-Dmdep.includeScope=test"
if ($LASTEXITCODE -ne 0) { throw "Could not resolve the Gatling classpath" }

$jar = (Get-ChildItem tollgate-server\target\tollgate-server-*.jar | Where-Object { $_.Name -notlike "*.original" })[0].FullName
$bench = (Resolve-Path tollgate-benchmarks\target\benchmarks.jar).Path
$gatlingClasspath = (Resolve-Path tollgate-benchmarks\target\test-classes).Path + ";" +
        (Resolve-Path tollgate-benchmarks\target\classes).Path + ";" +
        (Get-Content tollgate-benchmarks\target\classpath.txt -Raw).Trim()

$run = Join-Path $root (Join-Path $Output (Get-Date -Format "yyyy-MM-dd-HHmm"))
New-Item -ItemType Directory -Force $run | Out-Null
$workload = Join-Path $run "workload"
& java -cp $bench io.github.jamalakhras.tollgate.benchmarks.Workload $workload

$cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
$os = Get-CimInstance Win32_OperatingSystem
$battery = Get-CimInstance Win32_Battery -ErrorAction SilentlyContinue
$javaVersion = (& cmd /c "java -version 2>&1" | Select-Object -First 1)
[ordered]@{
    cpu = $cpu.Name.Trim()
    cores = $cpu.NumberOfCores
    logicalProcessors = $logical
    memoryGB = [Math]::Round($os.TotalVisibleMemorySize / 1MB, 1)
    os = "$($os.Caption) $($os.BuildNumber)"
    java = "$javaVersion"
    commit = (& git rev-parse HEAD).Trim()
    onBattery = [bool]($battery -and $battery.BatteryStatus -eq 1)
    sharedCores = $shared
    tollgateMask = "0x{0:X}" -f $tollgateMask
    stubMask = "0x{0:X}" -f $stubMask
    gatlingMask = "0x{0:X}" -f $gatlingMask
    warmupSeconds = $warmup
    measuredSeconds = $duration
    repeats = $repeats
} | ConvertTo-Json | Set-Content (Join-Path $run "system.json")

$keys = Join-Path $run "tollgate"
$initOutput = & java -jar $jar init $keys | Out-String
$apiKey = [regex]::Match($initOutput, "tg_\w+").Value
$generated = Get-Content (Join-Path $keys "tollgate.yaml") -Raw
$keyId = [regex]::Match($generated, "id: (\w+), tenant").Groups[1].Value
$keySha = [regex]::Match($generated, "sha256: (\w+) \}").Groups[1].Value
$publicKey = [regex]::Match($generated, "publicKey: (\S+)").Groups[1].Value
foreach ($line in Get-Content (Join-Path $keys "tollgate.env")) {
    $name, $value = $line -split "=", 2
    Set-Item "env:$name" $value
}
$auditDirectory = (Join-Path $run "audit") -replace "\\", "/"
$terms = (Get-Content (Join-Path $workload "terms.txt") | ForEach-Object { '"' + $_ + '"' }) -join ", "

function Write-Config([string]$name, [string]$audit, [bool]$redact) {
    $auditSection = if ($audit -eq "off") { "audit: { mode: off }" } else {
        "audit:`n  mode: $audit`n  directory: '$auditDirectory'`n  publicKey: $publicKey`n" +
        "  signingKey: `${env:TOLLGATE_AUDIT_SIGNING_KEY}`n  hmacKey: `${env:TOLLGATE_AUDIT_HMAC_KEY}"
    }
    $detectors = ""
    $redaction = ""
    if ($redact) {
        $detectors = "detectors:`n  clients: { terms: [$terms] }`n"
        $redaction = "    redact:`n" +
            "      email: [internal]`n      card: [internal]`n      iban: [internal]`n" +
            "      national-id: [internal]`n      phone: [internal]`n      ip: [internal]`n      clients: [internal]`n" +
            "    refuse:`n      secret: [internal]`n"
    }
    $config = @"
dataClasses: [public, internal, confidential]
providers:
  stub: { format: openai, baseUrl: "http://127.0.0.1:$stubPort/v1", region: local }
models:
  stub:
    provider: stub
    upstream: stub
    price: { input: 1, output: 2, cacheRead: 1, cacheWrite: 1 }
    maxOutputTokens: { default: 256, cap: 1024 }
tenants:
  bench:
    models: [stub]
    dataClass: internal
    budget: { limit: 1000000, period: total }
keys:
  - { id: $keyId, tenant: bench, sha256: $keySha }
$detectors
policies:
  default:
    guards:
      - { type: request-size, maxBytes: 1048576 }
      - type: data-class
        regions: { public: [local], internal: [local], confidential: [local] }
$redaction
$auditSection
"@
    $file = Join-Path $run "$name.yaml"
    [IO.File]::WriteAllText($file, $config)
    return $file
}

function Start-Pinned([string]$arguments, [long]$mask, [string]$log) {
    $process = Start-Process java -ArgumentList $arguments -PassThru -NoNewWindow `
            -RedirectStandardOutput $log -RedirectStandardError "$log.err"
    $null = $process.Handle
    $process.ProcessorAffinity = [IntPtr]$mask
    return $process
}

function Wait-Ready([string]$url) {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($watch.Elapsed.TotalSeconds -lt 300) {
        try {
            $response = Invoke-WebRequest $url -UseBasicParsing -TimeoutSec 2
            if ($response.StatusCode -eq 200) { return $watch.ElapsedMilliseconds }
        } catch { Start-Sleep -Milliseconds 100 }
    }
    throw "Nothing answered at $url"
}

function Allocated {
    $metric = Invoke-RestMethod "http://127.0.0.1:$managementPort/actuator/metrics/jvm.gc.memory.allocated"
    return [double]$metric.measurements[0].value
}

$results = New-Object System.Collections.Generic.List[object]
$gatlingOpens = "--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED " +
        "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED"

function Start-Gatling([string]$target, [string]$body, [double]$rate, [int]$seconds, [string]$stream,
                       [string]$folder, [string]$name) {
    $arguments = "-Xmx1g $gatlingOpens -cp `"$gatlingClasspath`" -Dtarget=$target -Dkey=$apiKey " +
            "-Dbody=`"$(Join-Path $workload $body)`" -Drate=$rate -Dduration=$seconds -Dstream=$stream " +
            "io.gatling.app.Gatling -s io.github.jamalakhras.tollgate.benchmarks.OverheadSimulation " +
            "-rf `"$(Join-Path $folder $name)`""
    return Start-Pinned $arguments $gatlingMask (Join-Path $folder "$name.log")
}

function Read-Request([string]$folder, [string]$request) {
    $slug = $request -replace " ", "-"
    $page = Get-ChildItem $folder -Recurse -Filter "req_$slug-*.html" | Select-Object -First 1
    $values = @{}
    if (-not $page) { return $values }
    $html = Get-Content $page.FullName -Raw
    $rows = [regex]::Matches($html,
            '<td class="title">(.+?)</td>\s*<td class="total">(.+?)</td>\s*<td class="ok">(.+?)</td>\s*<td class="ko">(.+?)</td>')
    foreach ($row in $rows) {
        $title = ($row.Groups[1].Value -replace "<[^>]+>", "").Trim()
        $values[$title] = @($row.Groups[2].Value.Trim(), $row.Groups[3].Value.Trim(), $row.Groups[4].Value.Trim())
    }
    return $values
}

function Measure-Run([string]$scenario, [string]$via, [string]$body, [double]$rate, [int]$repeat,
                     [string]$stream, $tollgate, $startupMs) {
    $target = if ($via -eq "direct") { "http://127.0.0.1:$stubPort" } else { "http://127.0.0.1:$gatewayPort" }
    $label = "$scenario-$via-$([IO.Path]::GetFileNameWithoutExtension($body))-$stream-$rate-$repeat"
    Write-Host "  $label"
    $folder = Join-Path $run "gatling\$label"
    New-Item -ItemType Directory -Force $folder | Out-Null

    $warm = Start-Gatling $target $body $rate $warmup $stream $folder "warm-up"
    $warm.WaitForExit()

    $before = if ($tollgate) { Allocated } else { 0 }
    $measured = Start-Gatling $target $body $rate $duration $stream $folder "measured"
    $peak = 0
    while (-not $measured.HasExited) {
        if ($tollgate) {
            $tollgate.Refresh()
            $peak = [Math]::Max($peak, $tollgate.WorkingSet64)
        }
        Start-Sleep -Milliseconds 500
    }
    $request = switch ($stream) { "first" { "first event" } "last" { "last event" } default { "chat" } }
    $stats = Read-Request (Join-Path $folder "measured") $request
    if ($measured.ExitCode -ne 0 -or -not $stats.ContainsKey("Total count")) {
        throw "Gatling failed for $label (exit code $($measured.ExitCode)); see $(Join-Path $folder 'measured.log')"
    }
    $count = $stats["Total count"]
    $allocatedPerRequest = $null
    if ($tollgate) {
        $allocatedPerRequest = [Math]::Round(((Allocated) - $before) / [double]$count[0] / 1KB, 1)
    }
    function Ok([string]$title) { return $stats[$title][1] }
    $results.Add([pscustomobject]@{
        scenario = $scenario; via = $via; body = $body; stream = $stream; rate = $rate; repeat = $repeat
        requests = [int]$count[0]; failed = [int]$count[2]
        meanMs = Ok "Mean"; p50Ms = Ok "50th percentile"; p90Ms = Ok "90th percentile"
        p99Ms = Ok "99th percentile"; p999Ms = Ok "99.9th percentile"; maxMs = Ok "Max"
        throughput = $stats["Mean count/s"][0]
        startupMs = $startupMs
        peakWorkingSetMB = if ($tollgate) { [Math]::Round($peak / 1MB) } else { $null }
        allocatedKBPerRequest = $allocatedPerRequest
    })
    $results | ConvertTo-Json | Set-Content (Join-Path $run "runs.json")
}

$scenarios = @(
    @{ name = "async"; audit = "async"; redact = $false; bodies = @("request-2kb.json", "request-32kb.json"); rates = $rates; streams = @("none") },
    @{ name = "off"; audit = "off"; redact = $false; bodies = @("request-2kb.json"); rates = @($otherRate); streams = @("none") },
    @{ name = "sync"; audit = "sync"; redact = $false; bodies = @("request-2kb.json"); rates = @($otherRate); streams = @("none") },
    @{ name = "async-redaction"; audit = "async"; redact = $true; bodies = @("request-2kb.json"); rates = @($otherRate); streams = @("none") },
    @{ name = "sync-redaction"; audit = "sync"; redact = $true; bodies = @("request-2kb.json"); rates = @($otherRate); streams = @("none") },
    @{ name = "stream-async-redaction"; audit = "async"; redact = $true; bodies = @("request-2kb-stream.json"); rates = @($otherRate); streams = @("first", "last") }
)

$stub = Start-Pinned "-Xmx512m -cp `"$bench`" io.github.jamalakhras.tollgate.benchmarks.StubProvider $stubPort" $stubMask (Join-Path $run "stub.log")
try {
    Start-Sleep -Seconds 2
    Write-Host "Direct to the stub"
    foreach ($body in @("request-2kb.json", "request-32kb.json")) {
        foreach ($rate in $rates) {
            for ($repeat = 1; $repeat -le $repeats; $repeat++) {
                Measure-Run "direct" "direct" $body $rate $repeat "none" $null $null
            }
        }
    }
    foreach ($stream in @("first", "last")) {
        for ($repeat = 1; $repeat -le $repeats; $repeat++) {
            Measure-Run "direct-stream" "direct" "request-2kb-stream.json" $otherRate $repeat $stream $null $null
        }
    }

    foreach ($scenario in $scenarios) {
        Write-Host "Through Tollgate: $($scenario.name)"
        $config = Write-Config $scenario.name $scenario.audit $scenario.redact
        $arguments = "-Xmx512m -XX:+ExitOnOutOfMemoryError -jar `"$jar`" --tollgate.config=`"$config`" " +
                "--server.port=$gatewayPort --management.server.port=$managementPort --tollgate.admin.port=$adminPort " +
                "--management.endpoints.web.exposure.include=health,metrics"
        $tollgate = Start-Pinned $arguments $tollgateMask (Join-Path $run "tollgate-$($scenario.name).log")
        try {
            $startupMs = Wait-Ready "http://127.0.0.1:$managementPort/actuator/health/readiness"
            $tollgate.Refresh()
            Add-Content (Join-Path $run "startup.csv") "$($scenario.name),$startupMs,$([Math]::Round($tollgate.WorkingSet64 / 1MB))"
            foreach ($body in $scenario.bodies) {
                foreach ($stream in $scenario.streams) {
                    foreach ($rate in $scenario.rates) {
                        for ($repeat = 1; $repeat -le $repeats; $repeat++) {
                            Measure-Run $scenario.name "tollgate" $body $rate $repeat $stream $tollgate $startupMs
                        }
                    }
                }
            }
        } finally {
            Stop-Process -Id $tollgate.Id -Force
            Start-Sleep -Seconds 2
        }
    }
} finally {
    Stop-Process -Id $stub.Id -Force
}

Write-Host "Verifying the audit trail"
$verify = Measure-Command { & java -jar $jar audit verify --public-key $publicKey $auditDirectory | Out-File (Join-Path $run "verify.txt") }
$checked = [regex]::Match((Get-Content (Join-Path $run "verify.txt") -Raw), "Checked (\d+) records").Groups[1].Value
Add-Content (Join-Path $run "trail.csv") "verify,$checked,$([Math]::Round($verify.TotalMilliseconds))"

Write-Host "Rebuilding budgets from the whole trail at startup"
$config = Join-Path $run "async.yaml"
$tollgate = Start-Pinned ("-Xmx512m -jar `"$jar`" --tollgate.config=`"$config`" --server.port=$gatewayPort " +
        "--management.server.port=$managementPort --tollgate.admin.port=$adminPort") $tollgateMask (Join-Path $run "tollgate-replay.log")
try {
    $replayMs = Wait-Ready "http://127.0.0.1:$managementPort/actuator/health/readiness"
    Add-Content (Join-Path $run "trail.csv") "replay,$checked,$replayMs"
} finally {
    Stop-Process -Id $tollgate.Id -Force
}

Write-Host "Done. Results are in $run"
