# JavaGuardCLI - user manual

JavaGuardCLI is a quality gate for AI coding agents working on Java code. It runs as a **hook** of the agent - a command the
agent calls by itself around its edits and at the end of its turn - and tells the agent, in the agent's own protocol, what is
wrong with what it just wrote. The supported agents are **Claude Code**, **Gemini CLI** and **Codex**.

It checks, in two steps:

- **After every edit** (the *post-edit gate*, about a second): parse, security and reliability rules, PMD, in-process
  compilation with Error Prone, test-integrity checks (the agent weakening a test to make it pass), Spring/JPA pitfalls,
  resilience rules, secrets in configuration files, database-migration edits.
- **At the end of the agent's turn** (the *end-of-turn gate*): Maven or Gradle compile of the changed modules and their
  dependents, the affected JUnit tests, SpotBugs + FindSecBugs, dependency checks against OSV, public-API and HTTP-contract
  compatibility, configuration keys, and everything the agent changed through the shell, which no edit hook can see.

And it watches the agent itself: when it goes round in circles, JavaGuard suggests a change of approach, optionally asks a
second model, and finally stops it with a handoff document another model (or you) can pick up.

Four guarantees: it reports only what the session introduced (legacy findings are not blamed on the agent); it never
modifies your code; it never runs a state-changing git command; and it **fails open** - if JavaGuard itself breaks, the agent
carries on.

## Contents

1. [Install](#1-install)
2. [Set up a project](#2-set-up-a-project)
3. [The hooks, agent by agent](#3-the-hooks-agent-by-agent)
4. [What the agent sees](#4-what-the-agent-sees)
5. [When the agent is stuck: escalation, handoff, second opinion, notifications](#5-when-the-agent-is-stuck)
6. [Commands](#6-commands)
7. [Configuration](#7-configuration)
8. [False positives and suppressions (FAQ)](#8-false-positives-and-suppressions-faq)
9. [Troubleshooting](#9-troubleshooting)
10. [Rules](#10-rules)
11. [License](#11-license)

## 1. Install

**Windows** - `javaguard-<version>-windows-x64.zip`: unzip anywhere and keep the files together (`javaguard.exe`,
`runtime\`, `javaguard-<version>-all.jar`, `plugins\`). No Java installation is needed.
Install it in a short path: Windows cannot start a program whose full path has 260 characters or more, so keep the folder
under about 200 characters. Spaces and accented characters in the path are fine.

**macOS, Linux, or Windows without the exe** - `javaguard-<version>-portable.zip`: needs Java 21 or newer (`JAVA_HOME` or
`java` on `PATH`). Use `javaguard` (macOS/Linux) or `javaguard.cmd` (Windows). The `javaguard` script must be executable
(`chmod +x javaguard` if your unzip tool dropped the permission). Linux has been tested; macOS has not been run by hand yet,
and a zip downloaded with a browser may carry the quarantine attribute (`xattr -dr com.apple.quarantine <folder>`).

Put the folder on `PATH`, or pass the full path to `--command` when installing the hooks. Check with `javaguard --version`.

Under the POSIX locale (`LANG=C`, usual in containers and cron) the JVM cannot handle a path with accents; the `javaguard`
script switches to a `C.UTF-8` locale for its own process when the system has one.

The project being checked needs Maven or Gradle (an executable or the wrapper) for the end-of-turn gate; the post-edit gate
needs nothing but the sources.

## 2. Set up a project

From the project root:

```
javaguard install --agent claude --command C:\tools\javaguard\javaguard.exe     # Windows
javaguard install --agent claude --command /opt/javaguard/javaguard             # Linux, macOS
javaguard warmup
javaguard doctor
```

1. **`install`** merges JavaGuard's hooks into the agent's configuration file (after a backup; your own hooks and settings
   are kept, and running it again changes nothing) and creates a commented `javaguard.yml` with every key at its default.
   It also builds the start-up archive (`app.jsa`, next to the jar) that makes every hook about 0.4 s faster. The archive records
   where the distribution lives, so after moving the folder run `javaguard optimize --force` (or `install` again); a missing or
   stale archive costs speed, never correctness, and `doctor` reports it.
2. **`warmup`** builds the classpath cache of every module that has sources (the whole reactor of a multi-module build, one
   Maven or Gradle run per module) so edited files are compiled in-process. Without it the post-edit gate skips compilation
   and says so; the end-of-turn gate rebuilds the cache by itself.
3. **`doctor`** checks the Java runtime, configuration, project, build tool, classpath cache, git, the hooks of each agent,
   SpotBugs/FindSecBugs and the second-opinion setup.

Options of `install` (and of `uninstall`, which removes only JavaGuard's entries):

| Option | Meaning |
|---|---|
| `--agent claude\|gemini\|codex\|all` | Which agent(s) to configure (required) |
| `--scope project\|user` | `project` (default) writes inside the project; `user` writes to your home directory and applies to every project |
| `--pre-edit auto\|always\|never` | The optional pre-edit snapshot hook (see below). `auto`: only for projects without git |
| `--command <path>` | The `javaguard` executable to write into the hook command line (default: the one you are running) |
| `--dry-run` | Print the resulting configuration instead of writing it |

To switch JavaGuard off temporarily without uninstalling: set `JAVAGUARD_DISABLED=1`, or create the file
`.javaguard/disabled` in the project.

## 3. The hooks, agent by agent

JavaGuard uses at most **four hooks** per agent, one per *gate* (the fourth, `turn-start`, only records state). Every hook runs
the same command,
`javaguard hook --agent <agent> --gate <gate>`; the agent passes a JSON description of the event on **stdin** and reads
JavaGuard's answer from **stdout**/**stderr** and the exit code. What differs between agents is only the event names, the
tool names to match, the units of the timeout and the way a "no" is expressed.

| Gate | Fires | What JavaGuard does | Timeout written by `install` | JavaGuard's own budget |
|---|---|---|---|---|
| `pre-edit` (optional) | Before the agent edits a file | Takes a snapshot of the file as it is, so later gates compare against it. Never speaks | 10 s | 2 s |
| `post-edit` | Right after the agent edited a file | The fast checks on that file (section 4) | 30 s | `fastGate.budgetMillis`, 3 s |
| `end-of-turn` | When the agent is about to finish its turn | Build, affected tests, deep analysis, plus everything changed outside the edit tools | 600 s | `fullGate.budgetSeconds`, 540 s |
| `turn-start` | When the user sends a prompt | Records which relevant files are already uncommitted (see below). Never speaks | 10 s | a `git status` |

JavaGuard enforces its own budget below the agent's timeout, so it always answers before being killed; a budget that runs out
cancels the running checks and reports what was done, with a note, but never blocks by itself.

The **pre-edit** hook is optional because the change tracker can usually reconstruct what a file looked like before: from the
edit itself, or from git `HEAD`. It matters in projects *without* git, which is why `--pre-edit auto` installs it only there.

The **turn-start** hook is silent and costs a few milliseconds: when a turn begins it records which relevant files are already
uncommitted, so that at the end of the turn whatever a shell command wrote (`sed`, `cat >`, PowerShell's `Set-Content`; Codex
edits this way as often as with `apply_patch`) is told apart from work that was already there. It prints nothing, so it adds
nothing to the prompt. The JSON examples below leave it out for brevity; `install` writes it for every agent. Without git there
is nothing to compare with and shell edits are not detected.

### 3.1 Claude Code

| Gate | Event | Matcher |
|---|---|---|
| pre-edit | `PreToolUse` | `Edit\|Write\|MultiEdit` |
| post-edit | `PostToolUse` | `Edit\|Write\|MultiEdit` |
| end-of-turn | `Stop` | (none) |
| turn-start | `UserPromptSubmit` | (none) |

File written: `.claude/settings.json` in the project (or `~/.claude/settings.json` with `--scope user`). Timeouts in seconds.
This is what `javaguard install --agent claude --pre-edit always` writes (key order aside):

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Edit|Write|MultiEdit",
        "hooks": [
          {
            "type": "command",
            "command": "\"C:/tools/javaguard/javaguard.exe\" hook --agent claude --gate pre-edit",
            "timeout": 10,
            "statusMessage": "JavaGuard: taking a baseline of the file..."
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Edit|Write|MultiEdit",
        "hooks": [
          {
            "type": "command",
            "command": "\"C:/tools/javaguard/javaguard.exe\" hook --agent claude --gate post-edit",
            "timeout": 30,
            "statusMessage": "JavaGuard: checking edited Java file..."
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "\"C:/tools/javaguard/javaguard.exe\" hook --agent claude --gate end-of-turn",
            "timeout": 600,
            "statusMessage": "JavaGuard: compiling and running affected tests..."
          }
        ]
      }
    ]
  }
}
```

What JavaGuard reads from Claude Code: `session_id`, `transcript_path`, `cwd` and `hook_event_name`; for the edit events the
tool (`Write`: `file_path`, `content`; `Edit`: `file_path`, `old_string`, `new_string`; `MultiEdit`: `file_path` and its
`edits`), and for `Write` whether the file was created (`tool_response.type`). The project root also comes from
`CLAUDE_PROJECT_DIR`. `MultiEdit` stays in the matcher for older versions of Claude Code; an unknown tool name in a matcher is
harmless.

### 3.2 Gemini CLI

| Gate | Event | Matcher |
|---|---|---|
| pre-edit | `BeforeTool` | `write_file\|replace` |
| post-edit | `AfterTool` | `write_file\|replace` |
| end-of-turn | `AfterAgent` | (none) |
| turn-start | `BeforeAgent` | (none) |

File written: `.gemini/settings.json` (or `~/.gemini/settings.json`). **Timeouts are in milliseconds** in Gemini CLI, whose
default is 60 s - too short for the end-of-turn gate - so `install` always writes an explicit value. Each hook is named
(`javaguard-<gate>`). **On Windows Gemini CLI runs hook commands through PowerShell**, where a quoted program path at the start
of a command is only a string, so `install` writes the call operator first (`& "C:/..."`); on macOS and Linux the command has
no `&`. The example below is the Windows form. If you installed an earlier build on Windows, `javaguard doctor` warns about the
old form and running `install` again rewrites it. Gemini CLI also loads a project's hooks only for a trusted folder: trust it
once in interactive mode, or set `GEMINI_CLI_TRUST_WORKSPACE=true` in unattended runs.

```json
{
  "hooks": {
    "BeforeTool": [
      {
        "matcher": "write_file|replace",
        "hooks": [
          {
            "name": "javaguard-pre-edit",
            "type": "command",
            "command": "& \"C:/tools/javaguard/javaguard.exe\" hook --agent gemini --gate pre-edit",
            "timeout": 10000
          }
        ]
      }
    ],
    "AfterTool": [
      {
        "matcher": "write_file|replace",
        "hooks": [
          {
            "name": "javaguard-post-edit",
            "type": "command",
            "command": "& \"C:/tools/javaguard/javaguard.exe\" hook --agent gemini --gate post-edit",
            "timeout": 30000
          }
        ]
      }
    ],
    "AfterAgent": [
      {
        "hooks": [
          {
            "name": "javaguard-end-of-turn",
            "type": "command",
            "command": "& \"C:/tools/javaguard/javaguard.exe\" hook --agent gemini --gate end-of-turn",
            "timeout": 600000
          }
        ]
      }
    ]
  }
}
```

What JavaGuard reads: the common fields (`session_id`, `transcript_path`, `cwd`, `hook_event_name`); for `write_file`,
`file_path` and `content`; for `replace`, `file_path`, `old_string` and `new_string`; an `AfterTool` whose `tool_response`
carries an `error` is ignored (the edit did not happen). The project root also comes from `GEMINI_PROJECT_DIR`. Gemini CLI
rejects any stdout that is not JSON, so JavaGuard always answers with exit code 0 and a JSON document.

### 3.3 Codex

| Gate | Event | Matcher |
|---|---|---|
| pre-edit | `PreToolUse` | `^apply_patch$` |
| post-edit | `PostToolUse` | `^apply_patch$` |
| end-of-turn | `Stop` | (none) |
| turn-start | `UserPromptSubmit` | (none) |

File written: `.codex/hooks.json` (or `~/.codex/hooks.json`). Timeouts in seconds. On Windows `install` writes two commands:
a portable `command` (the bare program name) and a `commandWindows` with the full path you gave, prefixed with PowerShell's call operator `&` (Codex runs it through PowerShell on Windows; without the `&` the hook fails with "hook exited with code 1"):

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "^apply_patch$",
        "hooks": [
          {
            "type": "command",
            "command": "javaguard hook --agent codex --gate pre-edit",
            "commandWindows": "& \"C:/tools/javaguard/javaguard.exe\" hook --agent codex --gate pre-edit",
            "timeout": 10,
            "statusMessage": "JavaGuard: taking a baseline..."
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "^apply_patch$",
        "hooks": [
          {
            "type": "command",
            "command": "javaguard hook --agent codex --gate post-edit",
            "commandWindows": "& \"C:/tools/javaguard/javaguard.exe\" hook --agent codex --gate post-edit",
            "timeout": 30,
            "statusMessage": "JavaGuard: checking patch..."
          }
        ]
      }
    ],
    "Stop": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "javaguard hook --agent codex --gate end-of-turn",
            "commandWindows": "& \"C:/tools/javaguard/javaguard.exe\" hook --agent codex --gate end-of-turn",
            "timeout": 600,
            "statusMessage": "JavaGuard: compiling and running affected tests..."
          }
        ]
      }
    ]
  }
}
```

Codex hooks must be enabled (`[features] hooks = true` in Codex's configuration, the default in current versions);
`doctor` warns when they are off. Codex edits files with the `apply_patch` tool, whose input is a patch, not a file path:
JavaGuard reads the patch headers (`*** Add File:`, `*** Update File:` with an optional `*** Move to:`, `*** Delete File:`) and
resolves the paths against `cwd`. Codex can also edit through shell commands (`sed`, `cat >`), which never reach an edit
hook: the end-of-turn gate finds those by comparing the working tree with the state the `turn-start` hook recorded when the
turn began (Codex often writes its files this way, so it matters here more than for the other agents).

### 3.4 Summary

| | Claude Code | Gemini CLI | Codex |
|---|---|---|---|
| Configuration file | `.claude/settings.json` | `.gemini/settings.json` | `.codex/hooks.json` |
| pre-edit (optional) | `PreToolUse` | `BeforeTool` | `PreToolUse` |
| post-edit | `PostToolUse` | `AfterTool` | `PostToolUse` |
| end-of-turn | `Stop` | `AfterAgent` | `Stop` |
| turn-start | `UserPromptSubmit` | `BeforeAgent` | `UserPromptSubmit` |
| Edit tools matched | `Edit\|Write\|MultiEdit` | `write_file\|replace` | `^apply_patch$` |
| Timeout unit | seconds | **milliseconds** | seconds |
| Says "no" with | exit code 2 + report on stderr | `{"decision":"deny"}` | `{"decision":"block"}` |
| Project root variable | `CLAUDE_PROJECT_DIR` | `GEMINI_PROJECT_DIR` | (uses `cwd`) |

`--agent` can be omitted when running `hook` by hand: JavaGuard then recognises the agent from the payload. `install` always
writes it explicitly.

### 3.5 Calling the jar directly (when `install` cannot be used)

`javaguard install` only writes the hook entries; the agents run whatever command they find there. When the installer or the
launcher gives trouble (a restricted machine, a proxy or VPN that needs a specific setup, a folder you cannot add to the `PATH`),
write the entries by hand and call the jar through Java. Nothing else is needed: no executable, no `PATH` entry, no start-up archive.

The command is the same for every hook; only `--agent` (`claude`, `gemini`, `codex`) and `--gate` (`pre-edit`, `post-edit`,
`end-of-turn`) change. Use forward slashes and single quotes around the paths.

| Agent | File | `command` |
|---|---|---|
| Gemini CLI (Windows) | `.gemini/settings.json` | `& 'C:/path/to/java.exe' -jar 'C:/path/to/javaguard-1.0.0-all.jar' hook --agent gemini --gate post-edit` |
| Codex (Windows) | `.codex/hooks.json`, in `commandWindows` | `& 'C:/path/to/java.exe' -jar 'C:/path/to/javaguard-1.0.0-all.jar' hook --agent codex --gate post-edit` |
| Claude Code | `.claude/settings.json` | `java -jar "/path/to/javaguard-1.0.0-all.jar" hook --agent claude --gate post-edit` |

Gemini CLI and Codex run the command through PowerShell on Windows, so it must start with the call operator `&` (section 9).
Keep the timeouts of the generated entries: 10 s for `pre-edit`, 30 s for `post-edit` and 600 s for `end-of-turn` (in milliseconds for
Gemini CLI). Gemini CLI example:

```json
"AfterTool": [
  {
    "matcher": "write_file|replace",
    "hooks": [
      {
        "name": "javaguard-post-edit",
        "type": "command",
        "command": "& 'C:/tools/java/bin/java.exe' -jar 'C:/tools/javaguard-1.0.0/javaguard-1.0.0-all.jar' hook --agent gemini --gate post-edit",
        "timeout": 30000
      }
    ]
  }
]
```

Keep `plugins/` next to the jar so SpotBugs works. Without the start-up archive each hook takes about 0.4 s longer; run
`javaguard optimize` and add `-XX:SharedArchiveFile=<folder>/app.jsa -Xlog:disable` after `java` to get it back. Entries written this way
are protected like the generated ones (JG-SUP-003) as long as `hook --agent <agent> --gate <gate>` stays in the command.

## 4. What the agent sees

Every gate ends in one of four verdicts:

- **PASS** - nothing is said.
- **ADVISE** - findings worth knowing; the agent keeps going.
- **BLOCK** - blocking findings, ranked, each with the offending line, the problem and the fix. At the end of a turn this
  makes the agent continue and fix them.
- **HALT** - the agent was stopped after failing the same way again and again (section 5).

How each verdict reaches each agent:

| Verdict | Claude Code | Gemini CLI | Codex |
|---|---|---|---|
| PASS | exit 0, no output | `{}` | exit 0, no output |
| ADVISE after an edit | `additionalContext` added to the model's context | `additionalContext` added to the model's context | `additionalContext` added to the model's context |
| ADVISE at end of turn | `systemMessage`: a one-line summary for you | same | same |
| BLOCK | exit 2, report on stderr (a `Stop` with exit 2 keeps Claude working) | `{"decision":"deny","reason":"<report>"}` (at end of turn it triggers a retry turn) | `{"decision":"block","reason":"<report>"}` |
| HALT | `{"continue":false,"stopReason":"..."}` | same | same |

The pre-edit hook never speaks. Suppressions the agent added during the session (section 8) are always shown to you in a
`systemMessage`, even on PASS.

**Which findings block?** Not every serious finding stops the agent; severity (section 10) decides, and it depends on the gate:

| Gate | Blocks | Everything else |
|---|---|---|
| After an edit (post-edit) | BLOCKER findings, source that does not parse (`JG-SYN-001`), and test-integrity findings (`JG-TST-*`) of CRITICAL or higher | ADVISE: the model is told, the agent keeps going |
| End of turn | Compile errors, a dependency that cannot be resolved (`JG-DEP-003`), findings of CRITICAL or higher, test-integrity findings of MAJOR or higher, and failing tests | ADVISE: a one-line summary for you |

So a CRITICAL rule such as `JG-SEC-002` (command built from input) is only advice right after the edit. A finding SpotBugs reports at the end
of the turn carries SpotBugs' own rank, which can be lower than the rule's catalog severity; the `Severity` line of the full report
(`.javaguard/reports/last-report.md`) is the one that was applied. To make a rule block in both gates, raise its severity in
`javaguard.yml`:

```yaml
rules:
  JG-SEC-002: { severity: BLOCKER }
  JG-PERF-002: { severity: BLOCKER }
```

The report ends with a guidance paragraph for the agent: fix the root cause and rewrite the code so the finding disappears while keeping the
behavior that was asked for; do not touch JavaGuard's configuration or the tests; and, when the only way to satisfy a rule contradicts what
you asked for (or the finding looks wrong), **stop and explain it to you, citing the rule, instead of retrying**. That last sentence is the
way out of a blocking rule the agent cannot satisfy; if it keeps failing anyway, the escalation of section 5 ends in a HALT.

Every non-PASS run also writes a full human report under `.javaguard/reports/`, and the agent's message is capped
(`report.maxFindings`, `report.maxChars`; blocking findings are never cut). Formats other than the agent text - Markdown,
JSON, SARIF - are available from `javaguard check`.

## 5. When the agent is stuck

JavaGuard recognises an agent that repeats the same blocked change, oscillates between two versions, or makes no progress, and
escalates in steps (thresholds in `loop:`):

1. **A change-of-approach hint** is added to the report, specific to the kind of finding (for example: for a compile error
   "read the declaring class instead of guessing"; for an edited migration "restore it and write a new one").
2. **A second opinion** (optional, `consult.enabled: true`): another model - the first of `consult.order` that is not the
   current agent and is on `PATH` - is run in the background with the handoff document and asked for a plan. Its answer
   is delivered to the agent once, at its next hook call.
3. **HALT**: the agent is stopped, you are notified, and `AI_HANDOFF_CONTEXT.md` is written in the project root, in seven
   sections - summary, environment, the problem, the relevant source and diffs, the attempts so far, excerpts of the agent's
   reasoning, and what has been ruled out - with secrets and absolute paths redacted. Give it to another model, or fix the
   problem yourself. `javaguard state clear --session <id>` starts over.

`javaguard handoff [--session <id>] [--consult claude|gemini|codex]` writes the same document on demand.

**Notifications** (`notify:`): terminal bell (Claude Code only), sound, desktop toast, optional [ntfy](https://ntfy.sh)
push; sent on HALT and, if `notify.consultation` is on, when a consultation starts and ends.

## 6. Commands

| Command | What it does |
|---|---|
| `install`, `uninstall` | Add or remove JavaGuard's hooks (section 2) |
| `hook --agent <a> --gate <g>` | What the agents call; reads the event on stdin |
| `check [files]` | The post-edit checks by hand on the given files or directories, or on the files changed against `HEAD`; `--format text\|md\|json\|sarif`, `--output <file>`; exit 1 on BLOCK |
| `check --full [files]` | The end-of-turn pipeline by hand (compile, affected tests, deep analysis), e.g. in CI |
| `doctor` | Environment and installation report |
| `warmup` | Build the classpath cache of every module that has sources (the whole reactor of a multi-module build, one Maven or Gradle run per module) |
| `optimize [--force]` | Build the start-up archive (`app.jsa`) next to the jar of the packaged distribution; `install` does it too. Hooks start about 0.4 s faster with it |
| `handoff [--session id] [--consult a]` | Write the handoff of a session; optionally ask a second model and wait for its answer |
| `rules [--category c]` | Every rule with its effective configuration |
| `state clear --session id\|--all`, `baseline reset` | Forget session state or baselines (`baseline reset` also accepts the current JavaGuard configuration as the one of the session) |

Global options: `--project-dir <dir>`, `--config <file>`, `--verbose` (log at FINE level to `.javaguard/logs/javaguard.log`).

Exit codes of the manual commands: 0 success, 1 blocking findings (`check`), 2 usage error, 3 invalid configuration, 4
environment problem, 5 internal error. Hook mode does not use them: its exit codes belong to each agent's protocol (section 3),
and an internal error always exits 0.

## 7. Configuration

`javaguard.yml` in the project root (committable), `~/.javaguard/config.yml` for your defaults, environment variables
`JAVAGUARD_<SECTION>_<KEY>` (e.g. `JAVAGUARD_FASTGATE_BUDGETMILLIS=5000`) and command-line options, in increasing order of
precedence. Unknown keys are reported by `doctor`; invalid values stop manual commands (exit 3) and fall back to defaults in
hooks. Every key with its default:

```yaml
exclude: ["**/generated/**", "**/target/**", "**/build/**"]   # globs never analyzed

build:
  tool: auto                 # auto | maven | gradle
  maven: { useDaemon: true, offlineFirst: true, extraArgs: [] }   # offlineFirst also applies to Gradle
  gradle: { extraArgs: [] }

fastGate:                    # post-edit
  enabled: true
  budgetMillis: 3000
  compileErrors: advise      # advise | block (the end-of-turn gate always blocks on them)
  inProcessCompile: true

fullGate:                    # end-of-turn
  enabled: true
  budgetSeconds: 540
  maxConsecutiveBlocks: 3

tests:
  scope: affected            # affected | module | all | none
  maxClasses: 50             # above it, the affected modules' whole test suites run
  includeIntegration: false
  unrelatedFailures: advise  # advise | block - failures in tests the change is not related to

errorprone: { enabled: true }
pmd: { enabled: true }
spotbugs: { enabled: true }

rules:                       # per-rule overrides
  JG-PERF-005: { enabled: false }
  JG-AI-001: { severity: INFO, exclude: ["**/cli/**"] }

suppressions:
  allowAgentAdded: true      # false: a javaguard:ignore the agent adds is itself a BLOCKER (JG-SUP-002)

deps:
  osv: { enabled: true, timeoutSeconds: 5 }   # only coordinates are sent to api.osv.dev

api:
  publicPackages: []         # e.g. ["com.acme.api.**"]: JG-API-001 even without reactor dependents

arch:
  rules: []                  # [{ from: "com.acme.domain..", mustNotDependOn: ["com.acme.web.."] }]

report:
  maxFindings: 15
  maxChars: 6000
  ascii: true
  adviseOnPostEdit: true
  sarif: false

loop:                        # stuck-agent detection
  repeatThreshold: 3
  oscillationWindow: 6
  noProgressWindow: 5
  maxBlockedRuns: 12
  maxSnapshots: 20

handoff:
  copyToProjectRoot: true    # AI_HANDOFF_CONTEXT.md
  maxFileLines: 400
  maxDiffLines: 300
  transcriptMessages: 6
  redactPatterns: []         # extra regexes to redact, besides secrets and absolute paths

consult:                     # second-model consultation (costs tokens; opt-in)
  enabled: false
  order: [gemini, codex, claude]   # first one that is not the current agent and is on PATH
  timeoutSeconds: 300
  claude: { model: "claude-opus-5-5" }
  commands: {}               # e.g. gemini: 'gemini -p "<instruction>" --approval-mode plan'

notify:
  terminalBell: true         # Claude Code only
  sound: true
  toast: true
  consultation: false        # also notify when a consultation starts and ends
  ntfy: { enabled: false, url: "" }

state:
  retentionDays: 7
```

An environment variable overrides the same key in a file, so `JAVAGUARD_FASTGATE_BUDGETMILLIS=60000` wins over a
`fastGate.budgetMillis` in `javaguard.yml`. On a slow or shared machine (a CI runner, a container with little CPU) raise
`fastGate.budgetMillis`: when the budget runs out the gate answers with a partial result and the note "Fast-gate budget ...
exhausted", which is correct but means some files were not analyzed.

`JAVAGUARD_DISABLED=1`, or a file `.javaguard/disabled`, switches JavaGuard off. Everything it keeps is under `.javaguard/`
in the project, which ignores itself in git.

## 8. False positives and suppressions (FAQ)

**A finding is wrong. What do I (or the agent) do?** Add a suppression with a reason, on the line or the line above:

```java
// javaguard:ignore JG-SEC-001 -- the column name comes from a fixed enum, never from input
String sql = "SELECT " + column.sqlName() + " FROM orders";
```

or on a declaration: `@SuppressWarnings("javaguard:JG-PERF-001") // built once at startup`. Any rule id works, including
`PMD:<rule>`, `EP:<check>` and `SB:<pattern>`.

**Can the agent silence JavaGuard that way?** It can add a suppression, but you are told about every suppression the session
adds, every time a gate runs, and one without a reason is a finding itself (JG-SUP-001). With
`suppressions.allowAgentAdded: false` an agent-added suppression blocks (JG-SUP-002).

**Can the agent switch JavaGuard off by editing its configuration?** No. At the first hook of a session JavaGuard records your
`javaguard.yml`, `~/.javaguard/config.yml` and its own hook entries in `.claude/settings.json`, `.gemini/settings.json` and
`.codex/hooks.json`. For the rest of the session it reads the recorded copy of the configuration, so an edit changes nothing, and
any change to those files is a BLOCKER (JG-SUP-003) until the agent restores them. Other settings in the agent files (permissions,
theme) are not compared. A change of yours that you do want mid-session is accepted by `javaguard baseline reset` or by starting a
new session; edit the files before opening the session. Shell edits are caught at the end of the turn.

**Who turns a rule off?** Only you. When the only way out of a finding is to change the configuration (a rule that does not fit,
console output that is fine in a prototype, an old migration that was never applied), the report tells the agent to stop and ask you
instead of editing `javaguard.yml`. For console output (JG-AI-001) in a project without a logger, for example, the agent offers to add
SLF4J or asks whether `System.out` is fine for this program; in the second case you add the override yourself.

**A rule does not fit my project at all.** `rules: { <id>: { enabled: false } }`, or restrict it with `exclude` globs, or lower
its `severity` (BLOCKER, CRITICAL, MAJOR, INFO). `javaguard rules` shows the effective configuration.

**Legacy code is full of findings.** Only what the session introduced is reported: findings are compared with the file before
the session touched it (git `HEAD`, or the pre-edit snapshot).

**A test failure has nothing to do with my change.** Failures in tests unrelated to the changed classes are reported as
"possibly pre-existing" and only advise, unless `tests.unrelatedFailures: block`.

**JavaGuard blocks an edit of a migration that was never deployed.** A Flyway or Liquibase migration counts as applied when
it existed before the session and is committed in git. On a feature branch whose migrations have not reached any shared
database yet, disable the rule for that path (`rules: { JG-DB-001: { enabled: false } }` while the branch is open, or an
`exclude` glob for the folder). A suppression comment cannot be used: it would change the migration's checksum itself.

**A secret is reported in `application-dev.yml`.** Literal secrets are BLOCKER in production configuration and MAJOR in test
resources and development profiles (`dev`, `local`, `test`, `docker`, `ci`, `sample`...). Prefer `${ENV_VAR}` references
even there; the finding never repeats the value.

**A configuration key is reported as never defined, but the environment provides it.** JG-CFG-002 is advisory: keys can come
from environment variables or a config server it cannot see. Give the placeholder a default (`${key:value}`) if it is
optional, or disable the rule for the project.

**A derived query or JPQL query is reported.** JG-SPR-015 and JG-SPR-016 only report what the entity model shows to be
missing; anything they cannot resolve (an entity of another module, a type from a library) is left alone. If the entity
lives in another module of the build, the finding means the name really is unknown there too.

## 9. Troubleshooting

| Symptom | What to do |
|---|---|
| `doctor`: "Startup archive ... not built" or "stale" | Run `javaguard optimize --force`. Hooks work without it but start about 0.4 s slower; the archive must be rebuilt after the folder is moved or the Java runtime changes. |
| "In-process compilation skipped ... the classpath cache is missing" | Run `javaguard warmup` (or let one end-of-turn run rebuild it). |
| "git refuses to read this repository ... dubious ownership" | The repository belongs to another user (typical in containers and shared folders), so git will not answer and edits made through a shell cannot be detected. If you trust it: `git config --global --add safe.directory <path>`. `javaguard doctor` shows the same warning. |
| "No Maven executable or wrapper found" | Install Maven/Gradle or add the wrapper (`mvnw`, `gradlew`); `doctor` shows what was found. |
| "Offline dependency resolution failed; retried online" | Normal on the first build of a machine; later builds stay offline. |
| "Maven could not resolve dependencies (no network, or a repository is unreachable)" | An infrastructure note, never a block: the repository gave no usable "not found" answer (offline, a proxy or mirror, a transfer error), so JavaGuard cannot say the artifact does not exist. Fix the network or the mirror configuration and it reports JG-DEP-003 for a real hallucinated dependency. |
| "Fast-gate budget (3000 ms) exhausted: N file(s) were not analyzed" | The machine was too slow for the post-edit budget. Raise `fastGate.budgetMillis` (section 7). |
| Codex or Gemini CLI on Windows: "hook exited with code 1" | The hook command must start with PowerShell's call operator (`& "C:/..."`) because both agents run hooks through PowerShell. `javaguard doctor` detects the old form; run `javaguard install` again. |
| javac: "illegal character: '\ufeff'" | The file starts with a UTF-8 byte order mark, which javac rejects (Windows PowerShell's `Out-File`, `Set-Content -Encoding UTF8` and `>` add one). Save it as UTF-8 without BOM. The report says so itself. |
| The agent stops with a HALT | Read `AI_HANDOFF_CONTEXT.md`, give it to another model or fix the problem; `javaguard state clear --session <id>` starts over. |
| A hook seems to do nothing | `javaguard doctor`; check `JAVAGUARD_DISABLED` and `.javaguard/disabled`; run with `--verbose` and read `.javaguard/logs/javaguard.log`. |
| "JavaGuard internal error" in the agent | JavaGuard failed open; the log has the details. Please report it. |
| SpotBugs row of `doctor` warns | Keep `plugins/findsecbugs-plugin.jar` next to the jar or executable. |
| The antivirus flags `javaguard.exe` | The executable is not code-signed; use the portable zip. |

## 10. Rules

[rules.md](rules.md) ([Español](rules_es.md), [Français](rules_fr.md)) explains every rule in detail: what it catches, why it matters in production and how the agent is told to fix it.

`javaguard rules` prints every rule with its effective configuration. Severity: B = BLOCKER, C = CRITICAL, M = MAJOR,
I = INFO. Gate: F = post-edit and end-of-turn, E = end-of-turn only. Besides these, the post-edit gate reports PMD
(`PMD:<rule>`) and Error Prone (`EP:<check>`) findings and javac errors (`JAVAC`); the end-of-turn gate reports test failures
(`TEST`) and SpotBugs patterns (`SB:<pattern>`, or the JG id they overlap with). The full catalog, with what each rule
detects, is in the project's documentation (`02-RULES-CATALOG.md` in its specification).

| Id | Title | Sev | Gate |
|---|---|---|---|
| JG-SYN-001 | Source does not parse | B | F |
| JG-SEC-001 | SQL built by concatenation (B when a method parameter feeds the SQL text, M when the pieces have no visible origin) | B | F |
| JG-SEC-002 | Command built from variable input | C | F |
| JG-SEC-003 | Unsafe deserialization | C | F |
| JG-SEC-004 | XML parser open to XXE | C | F |
| JG-SEC-005 | Weak cryptography | C | F |
| JG-SEC-006 | Predictable randomness for secrets | M | F |
| JG-SEC-007 | TLS verification disabled | B | F |
| JG-SEC-008 | Hard-coded secret | B | F |
| JG-SEC-009 | Path from request input | M | F |
| JG-SEC-010 | Sensitive data logged | M | F |
| JG-SEC-011 | Web security weakened | M | F |
| JG-SEC-012 | Permissive CORS with credentials | C | F |
| JG-SEC-013 | LDAP/XPath/EL built by concatenation | M | F |
| JG-SEC-014 | Open redirect | M | F |
| JG-SEC-015 | Endpoint without authorization where its siblings have it | M | F |
| JG-PERF-001 | String concatenation in loop | M | F |
| JG-PERF-002 | Regex compiled in loop | M | F |
| JG-PERF-003 | Expensive object created repeatedly | M | F |
| JG-PERF-004 | Unbuffered stream I/O | M | F |
| JG-PERF-005 | Linear lookup in loop | I | F |
| JG-PERF-006 | Head removal on ArrayList in loop | I | F |
| JG-PERF-007 | Boxed accumulator in loop | I | F |
| JG-PERF-008 | Busy wait | M | F |
| JG-PERF-009 | Whole-file/stream materialized | I | F |
| JG-REL-001 | Exception swallowed | M (C for `Exception`/`Throwable`/`RuntimeException`) | F |
| JG-REL-002 | Catching Throwable/Error | M | F |
| JG-REL-003 | Resource not closed | C | F |
| JG-REL-004 | Cause dropped when rethrowing | M | F |
| JG-REL-005 | Unchecked Optional.get | M | F |
| JG-REL-006 | Null returned as Optional/collection | C | F |
| JG-REL-007 | String compared with == | C | F |
| JG-REL-008 | BigDecimal misuse | M | F |
| JG-REL-009 | Floating point for money | M | F |
| JG-REL-010 | Interrupt status lost | M | F |
| JG-REL-011 | Control flow in finally | C | F |
| JG-REL-012 | System.exit in non-main code | M | F |
| JG-REL-013 | equals without hashCode | M | F |
| JG-REL-014 | Mutable static state exposed | M | F |
| JG-REL-015 | toMap without merge function | I | F |
| JG-REL-016 | Unboxing a possibly absent map value | I | F |
| JG-REL-017 | First element of a query result | M | F |
| JG-REL-018 | Exception logged without the exception | M | F |
| JG-REL-019 | Log placeholders do not match | M | F |
| JG-CONC-001 | Thread-unsafe shared formatter | C | F |
| JG-CONC-002 | Mutable state in singleton bean | M | F |
| JG-CONC-003 | Double-checked locking without volatile | C | F |
| JG-CONC-004 | Bad lock object | M | F |
| JG-CONC-005 | Check-then-act on concurrent map | M | F |
| JG-CONC-006 | Unmanaged threads/executors | M | F |
| JG-CONC-007 | wait/notify misuse | M | F |
| JG-CONC-008 | Side effects in parallel stream | C | F |
| JG-CONC-009 | Non-atomic update of shared field | M | F |
| JG-CONC-010 | Lock not released on every path | C | F |
| JG-CONC-011 | ThreadLocal not removed | M | F |
| JG-SPR-001 | Proxy annotation has no effect | C | F |
| JG-SPR-002 | Self-invocation bypasses proxy | M | F |
| JG-SPR-003 | Query inside loop (N+1) | M | F |
| JG-SPR-004 | Eager to-many association | M | F |
| JG-SPR-005 | Load all then filter in memory | M | F |
| JG-SPR-006 | Exception swallowed in transaction | M | F |
| JG-SPR-007 | @Async with non-future return | M | F |
| JG-SPR-008 | Entity equality on generated id / @Data on entity | M | F |
| JG-SPR-009 | Blocking call in reactive pipeline | M | F |
| JG-SPR-010 | Entity exposed/bound directly | I | F |
| JG-SPR-011 | Checked exception without rollback | M | F |
| JG-SPR-012 | Write in a read-only transaction | M | F |
| JG-SPR-013 | javax API in a Jakarta project | C | F |
| JG-SPR-014 | Request body not validated | M | F |
| JG-SPR-015 | Derived query on an unknown property | B | F + E |
| JG-SPR-016 | JPQL on an unknown entity or attribute | C | F + E |
| JG-SPR-017 | Unpaged findAll() returned to the client | M | F |
| JG-DB-001 | Applied migration modified | B | F |
| JG-DB-002 | Migration renamed or deleted | B | F |
| JG-DB-003 | Duplicate migration version | C | F |
| JG-CFG-001 | Secret in configuration | B | F |
| JG-CFG-002 | Configuration key never defined | M | E |
| JG-CFG-003 | Hard-coded host | M | F |
| JG-TST-001 | Test method removed | C | F |
| JG-TST-002 | Test disabled | B | F |
| JG-TST-003 | Assertions removed | C | F |
| JG-TST-004 | Expected value changed | M | F |
| JG-TST-005 | Trivial assertion | C | F |
| JG-TST-006 | Test body emptied | C | F |
| JG-TST-007 | Assertion failure swallowed | C | F |
| JG-TST-008 | Test file deleted | C | F |
| JG-TST-009 | Tests skipped by build config | B | F |
| JG-TST-010 | Assumption added | M | F |
| JG-TST-011 | Timeout greatly increased | I | F |
| JG-TST-012 | Expected-exception test neutralized | M | F |
| JG-TST-013 | Production code detects tests | C | F |
| JG-TST-014 | Class under test mocked | M | F |
| JG-AI-001 | Console output in production code | M | F |
| JG-AI-002 | printStackTrace | M | F |
| JG-AI-003 | Unimplemented stub | C | F |
| JG-AI-004 | Commented-out code added | I | F |
| JG-AI-005 | Placeholder literal | M | F |
| JG-AI-006 | Broad warning suppression added | I | F |
| JG-AI-007 | Unused code added | I | F |
| JG-RES-001 | HTTP client without timeouts | C | F |
| JG-RES-002 | Unbounded wait | M | F |
| JG-RES-003 | Remote call inside a transaction | M | F |
| JG-RES-004 | Remote call in loop | M | F |
| JG-RES-005 | Unbounded thread pool or queue | M (INFO for fixed/single) | F |
| JG-RES-006 | Blocking work on the common pool | M | F |
| JG-DEP-001 | Dependency added | I | F |
| JG-DEP-002 | Known vulnerability | from CVSS | E |
| JG-DEP-003 | Dependency cannot be resolved (possibly hallucinated) | B | E |
| JG-DEP-004 | Unpinned / snapshot version | M | F |
| JG-DEP-005 | Dependency downgraded | M | F |
| JG-API-001 | Public API changed | M | E |
| JG-API-002 | HTTP endpoint removed or moved | M | E |
| JG-API-003 | Endpoint requires a new parameter | M | E |
| JG-API-004 | JSON field renamed or removed | M | E |
| JG-ARCH-001 | Forbidden dependency | M | F |
| JG-SUP-001 | Suppression without reason | M | F |
| JG-SUP-002 | Agent-added suppression (when disallowed) | B | F |
| JG-SUP-003 | JavaGuard configuration or hooks changed during the session | B | F |

## 11. License

JavaGuardCLI is Copyright 2026 Alejandro Alberca Manzaneque, licensed under the Apache License, Version 2.0 (`LICENSE`, `NOTICE`).
Bundled third-party components keep their own licenses (`THIRD-PARTY.txt`).
