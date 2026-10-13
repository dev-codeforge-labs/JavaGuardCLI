#!/usr/bin/env bash
# Smoke test of the portable zip on a Unix machine: what a user does in the first five minutes, and the two things that failed
# silently the first time this was tried on Linux (a project reached through a symbolic link, a shell edit in the first turn).
#
#   bash .github/scripts/portable-smoke.sh path/to/javaguard-<version>-portable.zip
#
# Needs Java 21, git and Maven on the PATH. Exit code 0 when every check passed.
set -u

ZIP=${1:?usage: portable-smoke.sh <portable zip>}
FAILED=0
pass() { echo "ok    - $1"; }
fail() { echo "FAIL  - $1"; FAILED=1; }
check() {  # check "description" command...
  local description=$1; shift
  if "$@" >/dev/null 2>&1; then pass "$description"; else fail "$description"; fi
}

WORK=$(mktemp -d)   # on macOS this is under /var, a symbolic link to /private/var: exactly the case worth testing
trap 'rm -rf "$WORK"' EXIT
unzip -q "$ZIP" -d "$WORK/dist" || { echo "cannot unzip $ZIP"; exit 1; }
JG=$(find "$WORK/dist" -maxdepth 2 -name javaguard -type f | head -n 1)
[ -n "$JG" ] || { echo "no launcher script in the zip"; exit 1; }
echo "launcher: $JG"
echo "work dir: $WORK ($(cd "$WORK" && pwd -P))"

check "launcher is executable" test -x "$JG"
VERSION=$("$JG" --version 2>&1)
echo "$VERSION" | grep -q '^javaguard [0-9]' && pass "--version ($VERSION)" || fail "--version ($VERSION)"
check "FindSecBugs plugin is shipped" test -f "$(dirname "$JG")/plugins/findsecbugs-plugin.jar"

# --- a project with a committed class, and an agent's shell edit that adds SQL built by concatenation -------------------
P="$WORK/proj dir"           # a space on purpose
mkdir -p "$P/src/main/java/com/acme"
cat > "$P/pom.xml" <<'EOF'
<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>com.acme</groupId><artifactId>demo</artifactId><version>1.0.0</version>
<properties><maven.compiler.release>21</maven.compiler.release></properties></project>
EOF
printf 'package com.acme;\n\npublic class Dao {\n}\n' > "$P/src/main/java/com/acme/Dao.java"
( cd "$P" && git init -q && git -c user.name=ci -c user.email=ci@example.invalid add -A \
    && git -c user.name=ci -c user.email=ci@example.invalid commit -qm init )

for agent in claude gemini codex; do
  "$JG" install --agent "$agent" --command "$JG" --project-dir "$P" >/dev/null 2>&1 \
    && pass "install --agent $agent" || fail "install --agent $agent"
done
if grep -rq 'commandWindows' "$P/.codex/hooks.json" 2>/dev/null; then fail "codex hooks have no commandWindows on Unix"; else pass "codex hooks have no commandWindows on Unix"; fi
if grep -h '"command"' "$P/.gemini/settings.json" | grep -q '"& '; then fail "gemini command has no PowerShell call operator"; else pass "gemini command has no PowerShell call operator"; fi
grep -q 'gate turn-start' "$P/.claude/settings.json" && pass "turn-start hook installed" || fail "turn-start hook installed"

DOCTOR=$("$JG" doctor --project-dir "$P" 2>&1)
echo "$DOCTOR" | grep -q '\[OK  \] Java runtime' && pass "doctor: Java runtime" || fail "doctor: Java runtime"
echo "$DOCTOR" | grep -q '\[OK  \] Claude Code hooks' && pass "doctor: Claude Code hooks" || fail "doctor: Claude Code hooks"

# --- the first turn, through the path as the agent reports it ------------------------------------------------------------
run_hook() {  # run_hook <gate> <json payload>  (as an agent would: through sh -c)
  echo "$2" | sh -c "\"$JG\" hook --agent claude --gate $1" >"$WORK/out.txt" 2>"$WORK/err.txt"
}
LINK="$WORK/link"
ln -s "$P" "$LINK"            # the agent reports its cwd through a symbolic link
CWD="$LINK"
run_hook turn-start "{\"hook_event_name\":\"UserPromptSubmit\",\"session_id\":\"smoke\",\"cwd\":\"$CWD\",\"prompt\":\"p\"}"
[ $? -eq 0 ] && [ ! -s "$WORK/out.txt" ] && pass "turn-start is silent, exit 0" || fail "turn-start is silent, exit 0"

cat > "$P/src/main/java/com/acme/Dao.java" <<'EOF'
package com.acme;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public class Dao {
    PreparedStatement find(Connection conn, String id) throws SQLException {
        return conn.prepareStatement("SELECT * FROM t WHERE id = " + id);
    }
}
EOF
run_hook end-of-turn "{\"hook_event_name\":\"Stop\",\"session_id\":\"smoke\",\"cwd\":\"$CWD\",\"stop_hook_active\":false}"
CODE=$?
[ "$CODE" -eq 2 ] && pass "first-turn shell edit through a symlinked cwd is blocked (exit 2)" \
  || { fail "first-turn shell edit through a symlinked cwd is blocked (exit $CODE, expected 2)"; head -5 "$WORK/err.txt"; }
grep -q 'JG-SEC-001' "$WORK/err.txt" && pass "the report names JG-SEC-001" || fail "the report names JG-SEC-001"
# BSD grep (macOS) has no -P: delete every printable ASCII byte, tab and newline, and see what is left.
if [ -n "$(LC_ALL=C tr -d '\11\12\15\40-\176' < "$WORK/err.txt")" ]; then fail "agent-facing report is ASCII"; else pass "agent-facing report is ASCII"; fi

echo
if [ "$FAILED" -eq 0 ]; then echo "portable smoke test: all checks passed"; else echo "portable smoke test: FAILED"; fi
exit "$FAILED"
