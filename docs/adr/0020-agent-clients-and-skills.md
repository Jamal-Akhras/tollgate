# ADR 0020: Agent clients and skills

Date: 2026-11-10 · Status: Accepted

## Context

Claude Code, Codex, GitHub Copilot, Gemini CLI and the agent SDKs are how
most developers reach a model now. Their skills are files on the client:
instructions that go into the context, and scripts the agent runs with its own
tools. Every model call and every tool result already goes through the gateway
the client points at, so skills need nothing of their own from Tollgate.

What can block these clients is request shape, not policy. Tollgate refuses
fields and betas it hasn't reviewed (ADR 0008), and nobody has checked what
these clients send. A client that only works with `unreviewed-fields` has the
review switched off for everything, and a client update can break it without
notice.

Skills in the Anthropic API are a different thing: the request names skills in
`container` and the skill runs in a sandbox at the provider. They are not part
of this decision; see the alternatives.

## Decision

### Agent clients can't tell Tollgate is there

- **The bar:** a recorded session from each supported client replays through
  Tollgate with nothing refused beyond what the tenant's policy refuses, and
  with nothing changed except the model name, the output cap and redacted
  values. A client that needs `unreviewed-fields` doesn't meet it.
- **Record first.** Before any field is allowed, real sessions from each
  client are recorded through a local proxy, covering streaming, tool use,
  thinking, compaction and skills. The recordings show every endpoint, field,
  beta and tool type the clients use, and this ADR and ADR 0018 are corrected
  from them.
- **Review each field and beta they send.** Those that change neither where
  data goes nor what it costs are allowed for every tenant. Those that do stay
  refused or behind a feature, such as web search and the long-context beta,
  and the client is checked to still work without them.
- **Serve the endpoints they call.** An endpoint a recording shows that
  Tollgate lacks goes through the same guards, redaction and audit as a model
  call, or gets its own ADR. Token counting was the expected gap, but Claude
  Code 2.1.322 never called it.
- **Fixtures keep the shape, not the content.** Each recording is reduced to
  its field names, value types, betas and tool types, with every text,
  identifier and key replaced. The clients' own system prompts and tool
  descriptions are their vendors', and the sessions hold the recorder's
  prompts and account IDs, so none of that is committed.
- **The fixtures run in the build,** one per client, replayed against a stub
  provider. A field Tollgate would refuse fails the build, and a client update
  that adds one is caught by recording again.
- **A setup page per client** in the docs: the base URL, the key, the data
  class header, and MCP servers through Tollgate (ADR 0019).

### The clients and what each needs

| Client | Speaks | Through Tollgate |
|---|---|---|
| Claude Code | Anthropic messages | Recorded and reviewed below |
| Codex | OpenAI Responses API only | Needs ADR 0018 first |
| GitHub Copilot | OpenAI chat completions, through its custom endpoint in VS Code and its own key in the CLI | To record; chat and agent mode only, as inline completions always go to GitHub |
| Gemini CLI | Google's Gemini API | Needs a Gemini format, in its own ADR |
| Claude Agent SDK, OpenAI Agents SDK | Anthropic messages; Responses API | To record |

The ChatGPT and Claude apps can't be pointed at another endpoint, so they
can't go through Tollgate.

### What Claude Code sends

A session with Claude Code 2.1.322 covered tool use, thinking, web search, a
subagent, a skill and compaction. Every request was refused before this
review, and a tool added mid-conversation was refused even with
`unreviewed-fields`. The review:

- **Allowed:** the fields `context_management` (clearing old thinking from the
  context), `output_config` (effort and structured output) and `safeguards`;
  `tool_addition` and `tool_removal` blocks, and `tool_reference` blocks in
  tool results; and the betas `advanced-tool-use`, `advisor-tool`, `afk-mode`,
  `claude-code`, `context-management`, `dangerous-tool-use`, `effort`,
  `extended-cache-ttl`, `fallback-credit`, `inline-tools`,
  `interleaved-thinking`, `mid-conversation-tool-changes`, `per-turn-control`,
  `redact-thinking`, `structured-outputs`, `thinking-display-updates` and
  `thinking-token-count`.
- **A tool added mid-conversation is checked like any other tool.** A
  `tool_addition` block that carries a whole definition goes through the same
  tool-type check as `tools`, so it can't bring in a tool the provider runs.
  The advisor tool, which calls a second and dearer model, stays refused; its
  beta only makes it available.
- **`safeguards` is redacted.** It carries a classifier's context: working
  folder, home folder, git remotes, permission rules and a user identity.
  Redaction reads every text in it, like the arguments of a tool call.
- **Cache writes kept for an hour are priced as such.** Claude Code caches its
  system prompt for an hour, which Anthropic charges at twice the input price
  rather than 1.25 times. Prices gain `cacheWrite1h`, which defaults to twice
  the input price for models on an `anthropic` provider and to `cacheWrite`
  for the rest. The worst-case reservation uses it too.
- **Two betas aren't publicly documented:** `afk-mode` and
  `per-turn-control`. They are allowed because refusing them would refuse
  every request, and they bring no field or tool of their own. They are the
  first to revisit if a recording shows a field that goes with them.
- **`oauth-2025-04-20` stays unreviewed.** Claude Code sends it only when
  signed in with a subscription; through Tollgate it signs in with a tenant
  key.

## Alternatives considered

- **Allowing `unreviewed-fields` for agent clients.** It works today, but it
  also lets through every field a future client version sends, including
  those that change where data goes.
- **Recognising agent clients by their user agent** and relaxing the review
  for them. Any client can send any user agent.
- **Committing the recordings as they are.** Simplest to replay, but it would
  publish other vendors' prompts and the recorder's own data.
- **Allowing skills in the Anthropic API now,** behind a `code-execution`
  feature. A skill's work lives in a container and comes back as files, both
  kept at the provider under Tollgate's shared key. Without a record of which
  tenant owns each, every request would need a new container and no file could
  be fetched, which leaves little of the feature. It gets its own ADR with the
  shared storage tier, as stored responses do (ADR 0018).

## Consequences

- **Claude Code works through Tollgate with the review on,** skills
  included. Codex, Copilot, Gemini CLI and the agent SDKs follow as each is
  recorded, with the endpoints and formats they need. Pointing a client at
  Tollgate is a base URL, a key and, with ADR 0019, one MCP URL.
- **Each supported client is a fixture to keep current,** and new client
  versions need recording again before their new fields are allowed.
- **Skills that run at the provider stay refused** until the storage tier.
