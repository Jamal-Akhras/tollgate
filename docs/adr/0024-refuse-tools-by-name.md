# ADR 0024: Refuse tools by name

Date: 2026-11-13 · Status: Accepted

## Context

The request review checks what kind of tool a request defines (ADR 0008): a
tool the client runs is allowed, a tool the provider runs is refused. It
doesn't look at which tool it is. A function named `get_weather` and one named
`run_command` are the same to it.

Chat applications now ship with tools switched on. Open WebUI 0.13 sends its
built-in tools with ordinary chats and can add a terminal whose tool is called
`run_command`. An operator who wants chat without a shell has no way to say
so in Tollgate, and no record of which tenants ask for one.

## Decision

### Config

A new guard type, `tool-names`, in a policy's `guards:` list:

```yaml
policies:
  chat:
    guards:
      - type: data-class
        regions: { internal: [local] }
      - type: tool-names
        refuse: [run_command, "write_*", "delete_*"]
  locked-down:
    guards:
      - type: tool-names
        allow: [get_weather, "search_*"]
```

- **`refuse` lists names a request may not define.** A `*` stands for any run
  of characters, and a name must match from start to end. Matching is
  case-sensitive, as tool names are.
- **`allow` lists the only names a request may define.** Any other tool is
  refused. A request with no tools passes.
- **Both may be given.** A tool must then match `allow` and not match
  `refuse`, so `allow: ["file_*"]` with `refuse: [file_delete]` works.
- **A guard with neither, or with an empty list, fails the config load.**
- **It is per policy,** and tenants choose a policy, so one tenant can be
  given a tool another is refused. There is no global list.

### Behaviour

- **It checks tool definitions.** For an OpenAI request these are the names
  under `tools` and the older `functions` field. For an Anthropic request they
  are the names under `tools`, and the definition in a `tool_addition` block.
- **One refused name refuses the request,** with `403` `policy_denied` in the
  endpoint's own error format: `The tool 'run_command' is not allowed by this
  policy`. The same message is used whether the name matched `refuse` or
  missed `allow`. Nothing is removed from a request to make it pass, since Tollgate
  changes only the model and the output cap (ADR 0005).
- **It runs where it is listed,** after the built-in request review, and again
  for each fallback attempt, like the other guards.
- **It can be monitored.** `mode: monitor` records what it would have refused
  and lets the request through (ADR 0022).
- **The audit step is named `tool-names`.** A refusal's message holds the
  tool's name, which is the client's own text.
- **Tool calls and tool results are not checked.** A call in the conversation
  refers to a tool that an earlier request defined, and refusing it would
  break chats that started before a rule changed. Calls in the model's answer
  wait for output guards.

### What it can't see

- **Tools described in the prompt.** Open WebUI's `legacy` function calling,
  and any client that does the same, writes the tool list into a system
  message and reads the model's choice out of its text. To Tollgate that is
  text, and no tool is defined.
- **A renamed tool.** The client chooses the names. The guard holds an
  application to its operator's rules; it doesn't stop a tenant that writes
  its own client and calls its shell `get_weather`.

## Alternatives considered

- **A refuse list only.** Simpler, but an operator who wants "this tenant may
  use these two tools and nothing else" would have to guess every name a
  client might add. With `allow`, a new tool in a client update is refused
  until someone lists it, which is the point of an allow list; `mode: monitor`
  shows which names would be refused before it is enforced.
- **A setting on the policy next to `features`,** not a guard. It would be
  enforced like the request review, with no shadow mode. A rule about names
  is exactly the kind that needs trying on real traffic first.
- **Removing refused tools and forwarding the rest.** The chat would keep
  working, but the model would be answering a different request than the
  client sent, and the client would never know.
- **Rules per tenant instead of per policy.** Tenants already get their rules
  through a policy; a second place for rules would be a second place to look.
- **Checking tool calls in history and in answers.** See above: the first
  breaks old chats, the second needs output guards.
- **Regular expressions.** More power than names need, and one more place for
  a slow pattern.

## Consequences

- **An operator can keep named tools away from a tenant,** and see in the
  audit trail and the `tollgate.guards.monitored` metric who asks for them.
- **A refused tool refuses every request that defines it.** In a chat
  application that means every chat until the tool is switched off there,
  which is the point, and `mode: monitor` shows it coming.
- **Tool names join the request review's output,** so later guards can use
  them too.
- **The guard is a rule about names, not about what a tool does.**
