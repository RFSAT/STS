#!/usr/bin/env bash
# ---------------------------------------------------------------------------
#  Type-check every activity, view and adapter, offline.
# ---------------------------------------------------------------------------
#
#  The activities are excluded from run.sh because they need the Android
#  framework, and three separate compile failures reached CI through that gap:
#  a helper called by another activity's name, a call to a function that
#  existed nowhere, and a missing import. None could be caught by the tests.
#
#  This compiles EVERY source file against hand-written framework stubs,
#  view-binding classes generated from the real layouts, and an R generated
#  from the real resources. It runs nothing — the point is only that the
#  compiler resolves every name.
#
#  The stubs are deliberately NARROW: each class carries only the members the
#  app actually uses, because a stub that answered to anything would resolve a
#  typo as readily as a real name. Using a new framework API for the first
#  time means adding a line to tools/offline/Stub*.kt. That is the intended
#  cost, and small beside finding out from CI.
#
#  Run it SEPARATELY from run.sh, not after it: two compiler invocations back
#  to back contend badly, turning a forty-second check into several minutes.
#
#      npm install --prefix /tmp/kotlinc kotlin-compiler
#      tools/offline/typecheck_ui.sh /tmp/kotlinc/node_modules/kotlin-compiler/bin
set -euo pipefail
BIN="${1:-}"
[ -n "$BIN" ] && export PATH="$BIN:$PATH"
command -v kotlinc >/dev/null || { echo "kotlinc not on PATH; pass its bin directory as \$1"; exit 2; }

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
UI="$(mktemp -d -p "${STS_TMP:-/tmp}")"
trap 'rm -rf "$UI"' EXIT
mkdir -p "$UI/src" "$UI/stub"

cp -r "$ROOT/app/src/main/java/com/rfsat/sts" "$UI/src/"
cp "$ROOT"/tools/offline/Stub*.kt "$UI/stub/"
python3 "$ROOT/tools/offline/gen_bindings.py" "$ROOT/app/src/main/res/layout" "$UI/stub/Bindings.kt"
python3 "$ROOT/tools/offline/gen_r.py" "$ROOT/app/src/main/res" "$UI/stub/R.kt"

n=$(find "$UI/src" -name '*.kt' | wc -l)
echo "type-checking $n source files against generated bindings and framework stubs..."
if kotlinc -nowarn $(find "$UI/src" "$UI/stub" -name '*.kt') -d "$UI/out" 2>"$UI/err"; then
  echo "UI type-check: OK"
else
  echo "UI type-check FAILED:"
  grep "error:" "$UI/err" | sed "s|$UI/src/|  |" | head -40
  exit 1
fi
