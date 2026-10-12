# Hook payload fixtures

Golden inputs for the agent adapters (plan Phase 1, contracts
`.private/spec/03-HOOK-CONTRACTS.md`).

- `<agent>/<name>.json` — payload exactly as the agent would send it on stdin.
- `<agent>/<name>.expected.json` — the `HookEvent` the adapter must produce.

Placeholders, substituted by the test harness so fixtures are portable and
still exercise native path separators:

| Placeholder | Payload file | Expected file |
|---|---|---|
| `${PROJECT}` | temp project dir, native separators, JSON-escaped | literal `${PROJECT}` (the harness rewrites actual paths to it) |
| `${SEP}` | native separator, JSON-escaped (`\\` on Windows) | not used; expected paths always use `/` |

`receivedAt` is not compared.

## Captured vs. hand-written

Files whose name starts with `captured-` were recorded from a real agent with
`javaguard capture` and anonymized; all others are hand-written from the
vendor documentation (verified 2026-09-27). When a captured payload
contradicts a hand-written one, the captured payload wins and the contracts
document is updated in the same change.
