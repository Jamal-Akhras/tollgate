# ADR 0019: MCP tool calls through Tollgate

Date: 2026-11-10 · Status: Accepted

## Context

Agents get most of their tools from MCP servers. Those tool calls carry as much
data as the model calls do, and today Tollgate sees them only indirectly:

- **Servers the client runs** (Claude Code, Cursor, the Agents SDK) reach the
  model as ordinary client tools. Tollgate checks the calls and results that
  pass through a model request, but not the call to the server itself: who
  made it, which server got which arguments, and whether that server should
  have received data of that class.
- **Servers the provider runs** (`mcp_servers` on Anthropic's messages, the
  `mcp` tool on OpenAI's Responses API) are refused (ADR 0008), because the
  provider connects to a URL the request chooses and Tollgate sees none of it.

MCP has its own risks on top of data leaving:

- **Tool poisoning.** A tool's description goes into the model's context. A
  server that changes a description after it was reviewed can instruct the
  model, and nothing tells you it changed.
- **Shared credentials.** Each client holds its own tokens for each server, so
  there is no one place to revoke or audit them.

## Decision

Tollgate becomes an MCP gateway. Servers are declared in the config, tenants
are given the servers they may use, and every tool call is checked, limited
and audited like a model call.

The aim is that an agent can't tell Tollgate is there: it adds one MCP URL,
sees the tools it would see anyway, and a refused call reads like a tool
error it can work around.

### Servers in the config

```yaml
mcp:
  servers:
    github:
      url: https://api.githubcopilot.com/mcp/
      headers: { Authorization: "Bearer ${env:GITHUB_MCP_TOKEN}" }
      region: us
      concurrent: 4
      tools: mcp/github.json

tenants:
  coder:
    mcpServers: [github]
    limits: { toolCallsPerMinute: 120 }
```

- **A server is a destination like a provider.** It has a URL, credentials
  held by Tollgate, a region for the data-class rules, a timeout and an
  optional concurrency limit (ADR 0016).
- **Only reviewed tools exist.** `tools` names a file holding the definitions
  someone reviewed, as the server listed them. Tools not in it are hidden
  from `tools/list` and refused on `tools/call`. The file is reloaded with the
  config.
- **`java -jar tollgate.jar mcp tools <yaml> <server>`** connects to the server
  and writes its tools to that file, showing what changed since the last
  review. Reviewing a server means reading that change, as with the price
  catalog (ADR 0009).
- **The model sees the reviewed definitions,** not whatever the server lists
  today:
  - If only a tool's title, description or annotations changed, Tollgate keeps
    serving the reviewed version and the tool keeps working. A server can't
    reach the model through a changed description, which is how tool
    poisoning works.
  - If its input or output schema changed, or it disappeared, the tool is
    hidden, because calls built from the old schema could do something else.
  - New tools stay hidden until reviewed.
  - Each change is audited once and counted in
    `tollgate.mcp.tools_changed{server,tool,change}`, so operators notice and
    users don't.
- **Only Streamable HTTP upstreams are supported.** A stdio server runs on the
  client as it does today, or behind an HTTP bridge if it should be shared.

### The endpoints

- **`POST /mcp`** speaks MCP's Streamable HTTP transport, with the tenant's
  usual key as a bearer token, and serves the tools of all the tenant's
  servers at once. A client adds Tollgate once, as an HTTP MCP server with
  that header. Tool names stay as the servers name them; two of a tenant's
  servers offering the same name is a config error, found at load time from
  the reviewed files. A server that is down loses its own tools from the list
  and nothing else.
- **`POST /mcp/{server}`** serves one server, for clients that want to switch
  servers on and off separately.
- `DELETE` ends a session; `GET` answers `405`, which the transport allows.
- **Sessions are Tollgate's own.** Tollgate gives the client its own session
  ID and maps it to the tenant, the server and the upstream session, so a
  session ID is useless to another tenant. Sessions live in memory; after a
  restart the client gets `404` and starts a new session, as the specification
  says.
- **Tools only, for now.** Tollgate relays `initialize`, `ping`, `tools/list`,
  `tools/call`, and the progress, logging and cancellation notifications. It
  advertises only the tools capability, answers resources and prompts with
  `-32601`, and declines the server's own requests (sampling, elicitation,
  roots) without passing them on. Sampling would let a server prompt the
  client's model, and the others can follow with their own review.

### Checks on each tool call

- **Allowed:** the tenant has the server, and the tool is reviewed and not
  hidden.
- **Data class:** the request's class comes from `X-Tollgate-Data-Class` or
  the tenant's default, as for model calls. The policy's guards run with the
  server in place of the provider, so the data-class guard and rules on
  `provider.region` apply unchanged.
- **Detectors:** the policy's `refuse` list applies to the arguments. A secret
  is never sent to a tool server. `redact` does not apply: redaction keeps
  values from the model provider, and a tool server that is cleared for the
  data class needs the real values to do its job.
- **Limits:** a call takes a slot on the server, and counts towards the
  tenant's `toolCallsPerMinute` if it has one. It doesn't count towards
  requests per minute (ADR 0015): agents make about one tool call per model
  call, and a tenant shouldn't reach its model limit sooner because its tools
  moved behind Tollgate.
- **Results** go back to the client unchanged. Anything the client sends on
  to a model goes through the chat checks there.
- **A refused call is a tool error,** a result with `isError: true` and the
  reason, so the model reads why and can try something else, as it would with
  any failing tool. Only requests the protocol itself rejects, such as an
  unknown tool, get a JSON-RPC error.

### Audit, metrics and traces

- **Each tool call gets an intent and an outcome record:** endpoint `mcp`,
  the server, the tool, HMACs of the arguments and the result, and no usage or
  cost. A tool error is a `responded` outcome; the HMAC shows what was
  returned.
- **The records need their own `tool` field,** which is a new version of the
  audit encoding (ADR 0007). Session IDs need one too, so both go into one
  version 2, decided in its own ADR and built before this one. Putting the
  server in `model` and the tool in `upstream` would avoid the new version but
  mix servers in with models in every usage report.
- **Metrics:** `tollgate.mcp.calls{tenant,server,tool,result}`, a duration
  timer, and the tools-changed counter. The Grafana dashboard gains a tools
  row.
- **Spans** are named `execute_tool <tool>` with the GenAI attributes for tool
  calls, and carry no arguments or results.

### MCP the provider runs stays refused

`mcp_servers` on Anthropic's messages and the `mcp` tool on the Responses API
stay refused. They could be allowed when they point at Tollgate's own
endpoint, with a token for that one request and redaction carried through the
call. That needs Tollgate reachable from the provider, and a token that
changes per request may defeat the provider's prompt cache. It gets its own
ADR after the Responses API (ADR 0018), once that has been measured.

## Alternatives considered

- **An allowlist of outside MCP URLs for the provider to call directly.**
  Tollgate would choose where data goes but would see none of it, and could
  not audit, limit or redact the calls.
- **Allowing every tool a server offers.** Easier to set up, but a server
  could add or change a tool and reach the model without anyone reviewing it.
- **Pinning digests and hiding any tool that changes.** Safe, but a server
  that rewords a description would break agents in the middle of their work.
  Serving the reviewed definition is just as safe and nobody notices.
- **Prefixing tool names with the server's name** on `/mcp`. It avoids
  clashes, but the model sees names that differ from the server's own
  documentation and from what it saw without Tollgate.
- **Applying redaction to tool arguments.** Most tools break when an address
  or account number becomes a placeholder. The data-class rules decide whether
  a server may see the data at all.
- **Running stdio servers inside Tollgate.** It would turn the gateway into a
  place that runs commands from its config, with the access that brings.
- **Passing the client's own server credentials through.** Per-user
  credentials are useful for servers that act as the user, but they need user
  identity in Tollgate. They belong with the login work for Studio (ADR 0004).

## Consequences

- **Remote MCP servers are usable from any client that runs MCP itself,**
  with one place to grant, revoke, limit and audit them.
- **Tool definitions are pinned,** so a change upstream shows up as an audit
  record rather than as new instructions to the model, and agents keep
  working unless a schema changed.
- **Reviewed tool files are part of the deployment,** next to the config, and
  need reviewing when a server changes.
- **Calls act as Tollgate's identity on each server,** not the user's, until
  per-user credentials exist.
- **MCP the provider runs is still refused,** until its own ADR.
- **This depends on version 2 of the audit encoding.**
- **Resources, prompts and sampling are not available through Tollgate** until
  they are reviewed.
