# yama: Yet Another Micro Agent

A tiny, deliberately readable AI agent in Scala 3 and Cats Effect, wrapped in a REPL.

```
read an instruction  →  give it to the agent  →  print the answer
```

Everything the agent does in between is logged: every round of the loop, every model call with its token
cost, every tool call with its arguments and result, and the nested loop of a worker agent. The code is small
on purpose. It exists to show what an agent *is*: **an LLM in a loop, with tools, where the model chooses
the control flow.**

It is the companion code of the talk *The Art of the Agentic Loop* at Art of Scala 2026 (Warsaw, 3 December 2026).

## Quick start

Requirements: JDK 21+, sbt, Node 20+ (for the files MCP server).

```bash
npm install --prefix mcp/files-mcp      # the local MCP server (TypeScript)
cp .env.template .env                   # put your API key(s) in it
set -a; source .env; set +a             # the JVM does not read .env files (or use direnv)
sbt "yama-cli/run --config config.json"  # or: sbt yama-cli/assembly && java -jar projects/yama-cli/target/scala-3.*/yama.jar
```

Or download `yama` from the [releases](https://github.com/ssledz/yama/releases): an executable fat jar
(`chmod +x yama && ./yama`, or `java -jar yama`; JDK 21+). `sbt yama-cli/assembly` builds the same file as
`projects/yama-cli/target/scala-3.*/yama.jar`.

Without `--config`, yama reads `~/.yama/config.json`. The first run creates it from the defaults bundled in the
jar (this repo's `config.json`). If `npx` is on the `PATH`, the files server in it runs the published package,
pinned to the version built with this yama: `npx -y --package @ssledz/files-mcp@<version> files-mcp`. Without
`npx`, set the files server's `cwd` to an absolute path to an `mcp/files-mcp` checkout (relative paths in the
file are resolved against the file's directory).

`mcp/files-mcp` is published to npmjs as [`@ssledz/files-mcp`](https://www.npmjs.com/package/@ssledz/files-mcp)
by `.github/workflows/publish-files-mcp.yml` whenever it changes on `main` and its `version` is not on npm yet
(bump `version` in its `package.json` to release; the workflow needs the `NPM_TOKEN` secret).

```
yama> download https://example.com and save a short summary as example.md
yama> research what Art of Scala 2026 is about and save a short summary as aos.md
```

The second one needs `TAVILY_API_KEY`: the orchestrator delegates to `research_agent`, a worker agent that
searches the web over a remote MCP server.

## What an agent is

The whole idea fits in [
`components/agent/.../internal/Loop.scala`](components/agent/src/main/scala/yama/agent/internal/Loop.scala):

1. send the conversation to the model
2. the model answers with text → done
3. the model asks for tools → run them (in parallel), append the results, go to 1
4. stop after `maxRounds` so a confused model cannot loop forever

The conversation *is* the state: a `Vector[Item]` that keeps growing (`/history` shows it). Tool failures are
data (`{"error": ...}`), fed back so the model can recover. Everything else in this repo is plumbing around
that loop.

## Tools

The model cannot tell these apart: they all implement the same `Tool[F]` trait (schema + handler).

| tool                                            | kind                                | what it does                                                      |
|-------------------------------------------------|-------------------------------------|-------------------------------------------------------------------|
| `wget`                                          | native (in-process)                 | downloads a URL into the workspace or session folder              |
| `fs_read`, `fs_search`, `fs_write`, `fs_manage` | MCP, local (`mcp/files-mcp`, stdio) | sandboxed file access                                             |
| `activate_skill`, `read_skill_resource`         | skills                              | load a skill's instructions / files on demand                     |
| `research_agent`                                | subagent                            | a whole agent behind a tool: web research via Tavily (remote MCP) |

The `wget` schema is derived from a case class at compile time:

```scala
final case class WgetArgs(url: String, outputPath: String) derives ToolSchema, Decoder
```

## Providers

One component, `chat-openai-compat`, speaks the OpenAI **Responses API** (`POST {baseUrl}/responses`),
stateless (full transcript every round, `store: false`). Every provider that exposes it works; a provider is
just data in `config.json`:

| provider           | base URL                       | key                                                   |
|--------------------|--------------------------------|-------------------------------------------------------|
| `openai` (default) | `https://api.openai.com/v1`    | `OPENAI_API_KEY`                                      |
| `openrouter`       | `https://openrouter.ai/api/v1` | `OPENROUTER_API_KEY` (pick a model with tool support) |
| `deepseek`         | `https://api.deepseek.com`     | `DEEPSEEK_API_KEY`                                    |
| `ollama`           | `http://localhost:11434/v1`    | none (Ollama ≥ 0.13.3)                                |

Switch with a flag, no code change: `sbt "yama-cli/run --provider openrouter --model openai/gpt-4.1"`.

Rate limits and 5xx are retried *outside* the loop by `Chat.retrying` (cats-retry: exponential backoff, full
jitter, capped, honouring `Retry-After`). The agent never learns a retry happened.

## REPL

| command                       |                                                                                  |
|-------------------------------|----------------------------------------------------------------------------------|
| `/help`                       | list commands                                                                    |
| `/tools`                      | the tools the agent can call                                                     |
| `/prompt`                     | the system instructions, after placeholder substitution, with the skills catalog |
| `/history`                    | the raw conversation the model sees, including tool calls and results            |
| `/stats`                      | token usage so far, per agent                                                    |
| `/skills`                     | discovered skills and discovery diagnostics                                      |
| `/skill <name> <instruction>` | run an instruction with a skill pre-loaded                                       |
| `/reset`                      | forget the conversation, reset counters                                          |
| `/exit`                       | quit (ctrl-d works too)                                                          |

## Configuration

`config.json` holds everything except secrets; secrets come only from env vars (ciris `Secret`, redacted when
printed). Precedence: CLI flag > env var (`YAMA_PROVIDER`, `YAMA_MODEL`) > `config.json`.

```
yama [--config-dir ~/.yama] [--config config.json] [--provider openrouter] [--model …] [--sandbox .] [--max-rounds 25] [--skills-path …]…
```

yama's home is `--config-dir`, else `$YAMA_CONFIG_DIR`, else `~/.yama`:

```
~/.yama/config.json                              read when --config is not given; created on first run
~/.yama/skills/                                  user-level skills
~/.yama/sessions/<encoded cwd>/<session id>/     one folder per run, e.g. sessions/-Users-me-git-yama/20261124-093015-3fa9c2
```

A session folder is created only when the agent first writes into it (`fs_write`, `fs_manage` copy/move,
`wget`); a run that writes nothing leaves nothing behind. An explicit `--config` that does not exist is an
error, never bootstrapped.

- `chat`: provider, model, retry policy. `providers`: base URL, key env var, headers, capability flags.
- `agent`: the system prompt, `maxRounds`, and `mcpServers`: which servers' tools the orchestrator gets (the
  rest are for workers only). Substituted in the prompt: `{{MOUNT}}`/`{{SANDBOX_DIR}}` (the workspace) and
  `{{SESSION_MOUNT}}`/`{{SESSION_DIR}}` (this run's session folder).
- The agent can write to exactly two directories: the **workspace** (`sandbox.dir` or `--sandbox`; default: the
  directory yama runs in) and the **session folder**. Both are injected into the files MCP server as
  `FS_ROOTS`, and `wget` gets the same list, so they can never disagree. Each is addressed by its folder name.
- `mcpServers`: no `type` = a local process over stdio; `"type": "http"` = a remote streamable-HTTP server.
  A local server runs as one long-lived process; if it dies it is respawned automatically (exponential
  backoff, gives up after 5 quick crashes in a row). A call in flight at the crash fails fast and is not retried.
  `apiKeyEnv` names an env var sent as `Authorization: Bearer …`; without it the server is skipped.
- `skills`: extra skill `paths` (relative to the config file) and `trustProject`.
- `subagents`: workers exposed to the orchestrator as tools.

## Skills

yama implements the [Agent Skills](https://agentskills.io/specification) format: a skill is a folder with a
`SKILL.md` (YAML frontmatter + markdown instructions) and optional `scripts/`, `references/`, `assets/`.

- **Discovery**: `<project>/.yama/skills`, `<project>/.agents/skills`, `<config-dir>/skills`, `~/.agents/skills`,
  plus `skills.paths`, then the built-in skills. Project beats user, user beats built-in on a name collision.
- **Trust**: project-level skills inject instructions, so they load only with `skills.trustProject = true`
  (the default in this repo's `config.json`).
- **Progressive disclosure**: the catalog (name + description) goes into the system prompt; the body is loaded
  by `activate_skill`; resources are read by `read_skill_resource`, confined to the skill's directory.
- Validation is lenient: rule violations are warnings (see `/skills`); a missing description or broken YAML
  skips the skill.
- Built in: everything under [`skills/`](skills/), e.g. [`markdown-summary`](skills/markdown-summary/SKILL.md).
  The build copies the folder onto the classpath (`yama-skills/`), so the fat jar carries it and reads it
  from the classpath, not from the repo.
- Out of scope: running `scripts/` (yama has no shell tool), enforcing `allowed-tools` (experimental in the
  spec; parsed, not enforced), compaction.

## Subagents: orchestrator–worker

A subagent is a tool whose implementation is another agent: `SubAgent.asTool(spec, makeAgent): Tool[F]`, with
schema `{ input: string }`. The worker has its own instructions, its own tools (picked by name), optionally its
own model and its own `maxRounds`, and a fresh conversation per call. Only its condensed answer reaches the
orchestrator. Workers never get subagent tools (depth = 1). The orchestrator here *cannot* search the web: it
can only delegate.

## Workspace: Polylith on sbt

```
components/   one sbt subproject per brick; interface = package yama.<name>, implementation = yama.<name>.internal
bases/cli     the REPL, decline CLI, logback config and Main (the only place IO appears)
projects/     yama-cli: no code, base + components → fat jar
development/  the root project: all bricks in one IDE/REPL session
```

| brick                | what                                                                          |
|----------------------|-------------------------------------------------------------------------------|
| `conversation`       | `enum Item`, `ToolName`, `CallId`, `Answer`                                   |
| `config`             | `Config` (circe), `Secrets` (ciris), `Config.load`                            |
| `log`                | `AgentLog[F]` (the verbose loop log), `Stats[F]`                              |
| `chat`               | `Chat[F]`, `ToolSpec`, `Reply`, `ChatError`, `Chat.retrying`                  |
| `chat-openai-compat` | Responses API wire format + http4s call                                       |
| `tool`               | `Tool[F]`, `ToolSchema` (Mirror derivation), `ToolBox[F]`                     |
| `wget`               | native download tool with a sandbox check                                     |
| `mcp-client`         | thin CE wrapper over the MCP Java SDK; local servers spawned with fs2 process |
| `mcp-tools`          | MCP tools → `Tool[F]`                                                         |
| `skills`             | Agent Skills discovery, catalog, `activate_skill`, `read_skill_resource`      |
| `agent`              | `Agent[F]` and the loop                                                       |
| `subagent`           | an `Agent[F]` wrapped as a `Tool[F]`                                          |

Components depend only on other components' interfaces; implementations are `private[<brick>]`.
`scripts/check-bricks.sh` is the extra CI guard.

## Tests

```bash
sbt test
```

The loop is fully deterministic in tests: `Chat[F]` is a trait, so `ScriptedChat` replays scripted replies and
records every request (`AgentSpec`, `SubAgentToolSpec`). `chat-openai-compat` has golden-JSON tests for the
wire format. `McpClientSpec` is an integration test that spawns `mcp/files-mcp` (run `npm install --prefix
mcp/files-mcp` first).

## Branches and releases

Work happens on `develop` (CI runs on every push and PR). `main` only receives pull requests, and only a merged
PR into `main` can publish a release.

The version lives in `version.sbt` (semver, `X.Y.Z-SNAPSHOT` on `develop`). To release, on a clean `develop`:

```bash
sbt release            # asks for the release and next version; `sbt "release with-defaults"` takes 0.1.0 / 0.2.0-SNAPSHOT
```

It runs the tests, commits `X.Y.Z` on a new branch `release/vX.Y.Z`, bumps `develop` to the next minor snapshot,
pushes both and opens the PR `release/vX.Y.Z` → `main` (with `gh`; otherwise it prints the link). Merging that PR
runs `.github/workflows/release.yml`: it builds the executable fat jar, tags the merge commit `vX.Y.Z` and
publishes a GitHub release with the jar attached as `yama`. A merge into `main` whose `version.sbt` is a snapshot
or already tagged releases nothing. Pick versions explicitly with
`sbt "release release-version 1.0.0 next-version 1.1.0-SNAPSHOT"`.

## Credits

`mcp/files-mcp` is a sandboxed filesystem MCP server written in TypeScript by
[overment](https://x.com/_overment) (MIT, see its `package.json`).
