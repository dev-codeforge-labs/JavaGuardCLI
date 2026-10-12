# Maven output samples

Text fixtures for `MavenOutputParserTest` and `MavenBuildRunnerFallbackTest`. `@ROOT@` stands for the project root
and is replaced by the test.

## Real captures

`maven-<version>-*.txt` were **captured** from the real tool on Windows, Java 21, on a copy of the
`maven-multimodule` fixture:

| Version | Compiler plugin | Notes |
|---|---|---|
| Apache Maven 3.8.9 | 3.8.1 (pinned: the 3.1 default does not understand `release`) | |
| Apache Maven 3.9.4 | 3.11.0 | |
| Apache Maven 4.0.0-rc-7 | 3.16.0 | Release candidate, not a final release. |

Each version has five samples: `compile-error-quiet` / `compile-error-verbose` (`Foo.value()` renamed in module-a,
breaking module-b; `-q -B` and plain `-B`), `dependency-missing` (online, a hallucinated artifact in module-b),
`dependency-offline` (the same with `-o`) and `selection-failure` (`-pl does-not-exist`).

What the three versions taught the parser:

* The compile-error block is the same in all three.
* A missing dependency reads `Could not find artifact ... in central` in 3.8.9, `The following artifacts could not be
  resolved: ... (absent)` in 3.9.4, and a remote-repository-filter message (`prefixes: Path ... NOT allowed from
  central`) in 4.0.0-rc-7.
* **Maven 4 words an offline miss exactly like an online one**, so an offline run can never be believed about a
  missing artifact; the runner always confirms online.
* The reactor-selection failure reads `Could not find the selected project in the reactor` in 3.x and
  `The requested required projects ... do not exist` in 4.

## Synthetic samples

`synthetic-*.txt` are **hand-written** edge cases, not captures: `synthetic-two-errors-posix-paths.txt` (two errors in
one file, POSIX paths) and `synthetic-ansi-colours.txt` (ANSI escapes inside the log markers).
