# Deploying Tollgate

The quickstart in the [README](../README.md#quickstart) runs the server jar. This page covers the image and a systemd service.

## With Docker

Each version is published as `ghcr.io/jamal-akhras/tollgate:<version>` for
amd64 and arm64. Create the config and its secrets with the image itself,
writing them to the current folder as your own user:

```bash
docker run --rm --user "$(id -u):$(id -g)" -v "$PWD:/work" -w /work \
  ghcr.io/jamal-akhras/tollgate:0.1.0 init
```

On Windows, Docker Desktop writes the files as you, so leave out `--user`. In
PowerShell:

```powershell
docker run --rm -v "${PWD}:/work" -w /work ghcr.io/jamal-akhras/tollgate:0.1.0 init
```

The other commands here are for bash; in PowerShell, end each continued line
with a backtick instead of `\`, and write `${PWD}` for `$PWD`.

In `tollgate.yaml`, point the provider at `host.docker.internal` instead of
`localhost`. Then start it in the same folder, as you, so the audit trail
lands in `./audit`:

```bash
docker run -d --name tollgate --user "$(id -u):$(id -g)" \
  -v "$PWD:/work" -w /work -e TOLLGATE_CONFIG=/work/tollgate.yaml \
  --env-file tollgate.env \
  --add-host=host.docker.internal:host-gateway \
  -p 127.0.0.1:8080:8080 -p 127.0.0.1:8081:8081 -p 127.0.0.1:9090:9090 \
  ghcr.io/jamal-akhras/tollgate:0.1.0
```

On Windows, leave out `--user`, as for `init`. The folder is mounted, not the
file, because the console replaces `tollgate.yaml` and keeps earlier versions
in `config-history/`. The console is at `http://127.0.0.1:8081/console/`, and
edits are on. For a config the console must not change, remove
`TOLLGATE_ADMIN_EDITS` from `tollgate.env` and add `:ro` to the mount of a
folder that holds only the config. Point `audit.directory` at a folder
Tollgate can still write.

The `--add-host` line makes `host.docker.internal` work on Linux too; Docker
Desktop provides it already.

Each image comes with a signed record of how it was built. Check it before you
run it:

```bash
gh attestation verify oci://ghcr.io/jamal-akhras/tollgate:<version> --owner Jamal-Akhras
```

## As a systemd service

```ini
[Unit]
Description=Tollgate, a policy and audit gateway for LLM traffic
Wants=network-online.target
After=network-online.target

[Service]
User=tollgate
StateDirectory=tollgate
EnvironmentFile=/etc/tollgate/tollgate.env
Environment=TOLLGATE_CONFIG=/etc/tollgate/tollgate.yaml
Environment=TOLLGATE_ADDRESS=0.0.0.0
ExecStart=/usr/bin/java -XX:+ExitOnOutOfMemoryError -jar /opt/tollgate/tollgate.jar
Restart=on-failure

[Install]
WantedBy=multi-user.target
```

- **`TOLLGATE_ADDRESS`** is only needed when clients run on other machines or
  in containers. Without it, they get a connection error.
- **`tollgate.env`** holds the audit secrets and the admin token as
  `NAME=value` lines. Make it readable by root only.
- **Set `audit.directory` to `/var/lib/tollgate/audit`.** A relative directory
  is resolved next to the config file, and `/etc/tollgate` is not writable.
- **`/etc/tollgate` is not writable**, so remove `TOLLGATE_ADMIN_EDITS=file`
  from `tollgate.env`, or Tollgate stops at startup and says so. To use the
  console's edits, keep `tollgate.yaml` in `/var/lib/tollgate` and point
  `TOLLGATE_CONFIG` there.
- **Start it** with `systemctl enable --now tollgate`. `journalctl -u tollgate`
  shows the address each port listens on.
- **Memory:** each call in flight holds its request and answer, up to
  `tollgate.max-request-size` (16 MB) and `tollgate.max-response-size`
  (32 MB). A slow model lets calls pile up, so give its provider a
  `concurrent` limit, and set `-Xmx` to about half the machine's or
  container's memory. `-XX:+ExitOnOutOfMemoryError` with `Restart=on-failure`
  turns running out into a clean restart.
