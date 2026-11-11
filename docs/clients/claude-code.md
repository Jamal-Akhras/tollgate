# Claude Code

Claude Code works through Tollgate with the request review on. A recorded
session is replayed in the build, so a field Tollgate would refuse fails a
test before it reaches you ([ADR 0020](../adr/0020-agent-clients-and-skills.md)).

## Point it at Tollgate

Set these before starting Claude Code, or put them in the `env` block of
`~/.claude/settings.json`:

```sh
ANTHROPIC_BASE_URL=https://tollgate.example.com/anthropic
ANTHROPIC_AUTH_TOKEN=<the tenant key from keys create>
```

Claude Code adds `/v1/messages` to the base URL. The key goes in the
`Authorization` header, and a subscription sign-in is not used while it is set.

To make requests stricter than the tenant's default data class:

```sh
ANTHROPIC_CUSTOM_HEADERS="X-Tollgate-Data-Class: confidential"
```

## Models

Claude Code asks for models by their Anthropic names, such as
`claude-opus-5-5`, and uses a smaller model for background work. Give the
tenant models with those names, or choose names with `ANTHROPIC_MODEL` and
`ANTHROPIC_DEFAULT_HAIKU_MODEL`:

```yaml
models:
  claude-opus-5-5: { provider: anthropic, upstream: claude-opus-5-5 }
  claude-haiku-4-5: { provider: anthropic, upstream: claude-haiku-4-5 }

tenants:
  developers:
    models: [claude-opus-5-5, claude-haiku-4-5]
    policy: coding
```

The prices come from the bundled catalog. Claude Code keeps its system prompt
in the cache for an hour, and Tollgate charges those cache writes at the
`cacheWrite1h` rate: twice the input price unless you set it.

## Policy

Claude Code's web search runs at Anthropic, so it is refused unless the
tenant's policy allows `web-search` for the data class:

```yaml
policies:
  coding:
    features:
      web-search: [internal]
```

Everything else Claude Code sends is reviewed and allowed, including tools
added mid-conversation, which are checked like any other tool. The context
Claude Code sends to Anthropic's tool-use safeguards, such as folder names,
git remotes and a user identity, goes through redaction like the prompt.
