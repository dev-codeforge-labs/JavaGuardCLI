# JavaGuardCLI

**A quality gate for AI coding agents working on Java code.**

JavaGuardCLI plugs into the hook systems of **Claude Code**, **Gemini CLI** and
**Codex**. Each time the agent edits Java code it checks the change. It then
returns short, ranked feedback that the agent uses to fix its own mistakes before
moving on.

```
agent edits a .java file ──► POST_EDIT gate (seconds)
                               syntax · security · reliability · performance ·
                               concurrency · Spring/JPA · resilience · test integrity ·
                               AI debris · migrations · configuration ·
                               in-process compile + Error Prone + PMD
agent finishes its turn  ──► END_OF_TURN gate
                               Maven/Gradle compile · affected JUnit tests ·
                               SpotBugs/FindSecBugs · dependencies (OSV) ·
                               API and HTTP/JSON compatibility · configuration keys
agent stuck in a loop    ──► strategy hint ─► second-model consultation ─► halt + notify + handoff
```

## Principles

- Checks what matters (compilation, tests, security, performance, reliability)
  and **never** style or naming.
- Reports only problems **introduced by the current session**, not legacy debt.
- Detects agents that **weaken tests** to make them pass.
- Never modifies your code; never runs state-changing git commands.
- Fail-open: a JavaGuard problem never blocks your agent.

## Why

AI coding agents write Java fast and confidently, and fail in recognisable ways: SQL built by concatenation, swallowed
exceptions, code that does not compile in the module next door, tests "fixed" by deleting the assertion, dependencies that do
not exist, an edited database migration that breaks every deployment, and the same wrong fix tried five times in a row.
JavaGuardCLI catches those at the moment they happen and tells the agent, in a short ranked report it can act on, what is wrong
and how to fix it, so you review working code instead of repairing it.

## Quick start

1. Download `javaguard-<version>-windows-x64.zip` (no Java needed) or `javaguard-<version>-portable.zip` (Java 21+) and unzip it.
2. In your project:

   ```
   javaguard install --agent claude      # or gemini, codex, all
   javaguard warmup                      # classpath cache for in-process compilation
   javaguard doctor                      # verify the setup
   ```

3. Work with your agent as usual. Blocking problems come back to the agent as hook feedback; you see suppressions it adds,
   summaries at the end of its turns and, if it gets stuck, a notification and `AI_HANDOFF_CONTEXT.md`.

Everything else - the hooks of each agent, commands, every configuration key, false positives and suppressions,
troubleshooting, the rule list - is in the user manual, [USAGE.md](javaguard-dist/src/main/dist/USAGE.md), which also ships
in both zips.

## How it works

### Supported agents

| | Claude Code | Gemini CLI | Codex |
|---|---|---|---|
| Hook configuration written by `install` | `.claude/settings.json` | `.gemini/settings.json` | `.codex/hooks.json` |
| Before an edit (optional snapshot) | `PreToolUse` | `BeforeTool` | `PreToolUse` |
| After an edit (POST_EDIT) | `PostToolUse` | `AfterTool` | `PostToolUse` |
| End of the turn (END_OF_TURN) | `Stop` | `AfterAgent` | `Stop` |
| Edit tools watched | `Edit`, `Write`, `MultiEdit` | `write_file`, `replace` | `apply_patch` |
| A block is expressed as | exit code 2 + report | `{"decision":"deny"}` | `{"decision":"block"}` |

Codex edits made through the shell (`sed`, `cat >`) never reach an edit hook; the END_OF_TURN gate finds them by comparing
the working tree with the state it recorded.

### Verdicts

Each gate ends in **PASS**, **ADVISE** (the agent keeps going and is told), **BLOCK** (the agent must fix the findings) or
**HALT** (the agent is stopped). Findings have a severity - BLOCKER, CRITICAL, MAJOR, INFO - and the gate decides:

- **After an edit** only the fast, high-precision findings block: BLOCKERs, syntax errors and serious test-weakening.
  Everything else advises, so a half-finished edit is not interrupted.
- **At the end of the turn** the real build is the judge: compile errors, failing tests related to the change, and findings of
  CRITICAL or above block; the rest is summarised for you.

A finding is reported only if the session introduced it: JavaGuard compares each file with the version before the agent first
touched it (a pre-edit snapshot, or git `HEAD`) and keeps what is new. An agent can suppress a finding with a reason
(`// javaguard:ignore JG-XXX-nnn -- why`); every suppression it adds is shown to you, and one without a reason is a finding
itself.

### What it checks

More than 130 rules plus the compiler (`javaguard rules` prints them all). The families:

| Family | Rules | Examples |
|---|---|---|
| `JG-SEC` Security | 15 | SQL built by concatenation, command injection, unsafe deserialization, disabled TLS verification, hard-coded secrets, path from request input |
| `JG-PERF` Performance | 9 | Regex compiled in a loop, string concatenation in a loop, linear lookup in a loop, unbuffered stream I/O, busy waits |
| `JG-REL` Reliability | 18 | Swallowed exceptions, resources not closed, lost cause, `Optional.get`, `BigDecimal` misuse, `toMap` duplicates, unchecked first element of a query |
| `JG-CONC` Concurrency | 11 | Thread-unsafe shared formatters, double-checked locking without `volatile`, mutable state in singleton beans, a lock not released on every path, a `ThreadLocal` never removed |
| `JG-SPR` Spring / JPA | 17 | Queries inside loops (N+1), `@Transactional` pitfalls, derived queries and JPQL on properties the entity does not have, `javax` in a Jakarta project, unpaged `findAll` |
| `JG-RES` Resilience | 6 | HTTP clients without timeouts, remote calls inside transactions or loops, unbounded thread pools and waits |
| `JG-TST` Test integrity | 14 | Removed or emptied tests, assertions removed, disabled tests, expected values changed, tests skipped by the build, the class under test mocked |
| `JG-DB` Migrations | 3 | An applied Flyway/Liquibase migration modified, renamed or deleted; duplicate versions |
| `JG-CFG` Configuration | 3 | Secrets in `application*.yml/properties`, keys the code needs that no file defines, hard-coded hosts |
| `JG-API` Compatibility | 4 | Public API changed, HTTP endpoint removed, new required parameter, JSON field renamed or removed |
| `JG-DEP` Dependencies | 5 | Dependency added, known vulnerability (OSV), artifact that does not exist, unpinned or downgraded version |
| `JG-AI` AI debris | 6 | Console output, `printStackTrace`, unimplemented stubs, commented-out code, placeholders |
| `JG-ARCH`, `JG-SUP`, `JG-SYN` | 1 + 2 + 1 | Forbidden package dependencies you configure, suppressions, syntax errors |
| PMD, Error Prone, SpotBugs + FindSecBugs | 24 + curated | A curated subset of each engine, merged under the JG ids when they overlap |

Rules are syntactic by design (no symbol resolution), which keeps the post-edit gate fast and predictable; where precision needs
more, the rule reports only what it can prove (for Spring Data and JPQL it builds a model of the module's entities and
stays silent on anything outside it). `javaguard rules` lists every rule with its effective severity.

### Stuck agents

JavaGuard watches for an agent that repeats the same blocked change, oscillates between versions or makes no progress. It
escalates in steps: a hint that changes the approach, optionally a second opinion from another model that is run in the
background, and finally a stop with a notification (bell, sound, toast, optional ntfy) and an `AI_HANDOFF_CONTEXT.md` another
model or you can pick up.

## Status

Version 1.0.0: the implementation plan is complete, including resilience rules, Spring/JPA correctness, database migrations,
configuration files, HTTP/JSON contract changes and authorization consistency.

**Tested.** About 1,650 unit and integration tests, plus end-to-end scenarios, hook-payload replays and a performance suite run
against the packaged executable (`mvn -Pdist verify`). The two checks most prone to false alarms were validated against the
real tools: derived queries and JPQL against Spring Data JPA and Hibernate (72 cases), and migration edits against Flyway and
Liquibase (30 scenarios) - JavaGuard never reports what the real tool accepts.

**Measured on real projects.** Every one of the newest rules was run on seven open-source projects (Spring PetClinic and its REST and
microservices variants, two JHipster sample apps, a Spring Boot RealWorld app, and Apache Fineract with about 7,000 Java files)
and every finding was classified. The false positives it exposed were fixed, and three rules that stayed noisy were demoted
to INFO.

**Performance** (median over several runs through the packaged executable, Windows):

| Scenario | Target | Measured |
|---|---|---|
| JVM start to main (`--version`) | 250 ms | 258 ms |
| Pre-edit snapshot | 300 ms | 307 ms |
| Post-edit, 300-line file, no classpath cache | 800 ms | 707 ms |
| Post-edit, 300-line file, warm cache (p95) | 1.5 s | 1.03 s |
| End of turn, nothing changed | 500 ms | 445 ms |
| End-of-turn overhead over `mvn test` alone | 1.0 s | 1.36 s |

Start-up and the end-of-turn overhead are slightly above their targets (the executable launches a bundled JVM and the gate adds
analysis to the build); the suite's asserted limits are 300 ms, 350 ms and 1.5 s.

**Verified with real agents (Windows, October 2026).** Claude Code and Gemini CLI were run non-interactively, and Codex
interactively (0.162.1), with the hooks installed by `javaguard install`: a seeded SQL injection is blocked, the report reaches
the model, the agent fixes it, and the gates then pass. In Codex, which wrote its file with a PowerShell command no edit hook sees, the end-of-turn gate of the very first turn
blocked it for SQL built by concatenation and for a byte order mark that javac rejects (with advice on how to save the file),
then for a statement never closed, until the code was right.
The real hook payloads of Claude Code and Gemini CLI match the documented contracts. Testing this found and fixed the Windows
hook command of Gemini and Codex (both run hooks through PowerShell) and a gap in the SQL concatenation rule.

**Linux (Docker, Ubuntu, JDK 21, October 2026).** The whole suite, including the integration profile with real Maven and
Gradle builds and the Spring Data, Flyway and Liquibase oracles, passes (about 1,800 tests; only the Windows-executable tests
are skipped), and the portable zip works: launcher permissions, `install` for the three agents, `doctor`, `check`, `warmup`,
and the hook commands run through `sh` with a space in the project path. Testing it also found and fixed two silent failures: a
project reached through a symbolic link (`/tmp` is `/private/tmp` on macOS) and a repository git refuses to read because it belongs
to another user, which now produces a warning and a `doctor` row instead of "nothing changed". Under the POSIX locale (`LANG=C`, typical in containers) the JVM cannot handle a path with accents at all: the launcher now
switches to `C.UTF-8` when the system has it, and a hook that still cannot start exits 0 instead of 5. The end-of-turn gate also
honours its time budget and leaves no Maven or test processes behind when a test never ends.

**Not yet verified.** macOS, and the three agents running for real on Linux or macOS. Codex payloads were captured on
Windows only (shell commands, `apply_patch`, prompts and Stop). A turn-start hook records the uncommitted files when each turn begins, so edits a shell command makes
(Codex does this often) are caught at the end of the very first turn; without git they are not detected.

## Building from source

Java 21 (a full JDK), Maven 3.9; Gradle 8 only for the Gradle integration tests.

```
mvn verify                    # unit tests
mvn -Pintegration verify      # plus real Maven/Gradle fixture builds and the Spring Data / Flyway / Liquibase oracles
mvn -Pdist verify             # Windows: build both zips and test the packaged executable
```

## License

Copyright 2026 Alejandro Alberca Manzaneque. Licensed under the [Apache License, Version 2.0](LICENSE); see [NOTICE](NOTICE). The binary
distributions bundle third-party components under their own licenses (`THIRD-PARTY.txt`).
