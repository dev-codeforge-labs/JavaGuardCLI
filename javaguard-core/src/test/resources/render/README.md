# Adapter render goldens

Exact bytes each adapter must produce for every gate x verdict case
(contracts §3.3, §4.3, §5.3). The cases and their `AgentFeedback` inputs are
defined in `AdapterRenderGoldenTest`.

Format:

```
=== <case id>
exit: <code>
--- stdout
<stdout lines>
--- stderr
<stderr lines>
```

A stream with lines is compared as those lines joined by `\n` plus a final
`\n`; a stream with no lines must be empty. Lines starting with `#` before the
first `===` are comments.

JSON key order is part of the contract (deterministic output):
`continue, stopReason, decision, reason, systemMessage, terminalSequence, hookSpecificOutput`.

Regenerate deliberately with `-Djavaguard.updateGoldens=true`, then review the diff.
