#!/usr/bin/env bash
# Local CI for checks that only run on this machine, posted as GitHub commit statuses (signoff/*).
# The ONLY supported way to post signoff/* statuses: never post them by hand.
#
#   scripts/ci.sh              run everything that applies, post evidence + statuses
#   scripts/ci.sh --dry-run    run the checks, print the evidence, post nothing
#
# Contexts:
#   signoff/emulator  unit tests + Light-builder simulation + emulator round trip on the Chess AVD
#                     (docs-only diffs: posted green as "not applicable: docs-only")
#
# The emulator is the one running AVD $CHESS_AVD (default LightPhone3-chess, on emulator-5556), found
# by name through scripts/chess-emu.sh. It never falls back to another emulator: the Reader's
# LightPhone3 on emulator-5554 is not ours. The Light Phone III round trip (signoff/lp3) joins once the
# board takes input (v1 PR 3), under the phone lease in the umbrella PLATFORM.md.
#
# Evidence is public (a PR comment). It carries generic facts only: commit, test counts, SDK ref,
# round-trip lines. Never serials, hostnames, or local paths.
set -uo pipefail

case "${1:-}" in
  "") post=1 ;;
  --dry-run) post=0 ;;
  *) echo "usage: scripts/ci.sh [--dry-run]" >&2; exit 2 ;;
esac
repo=$(git rev-parse --show-toplevel) && cd "$repo" || exit 1
pkg=com.yarosz.chess
started=$(date +%s)
evidence=()
note() { evidence+=("$1"); echo "ci: $1"; }
die() { echo "ci: FAIL $1" >&2; exit 1; }
emu() { scripts/chess-emu.sh "$@"; }

# --- Preflight: the statuses attest to one exact, pushed commit.
# Untracked files count: the tests and APKs build from the live tree, so it must equal the commit.
[ -z "$(git status --porcelain)" ] || die "working tree differs from HEAD (modified or untracked files); commit or remove them"
head=$(git rev-parse HEAD)
if git remote get-url origin >/dev/null 2>&1; then
  git fetch --quiet origin
  if [ "$post" = 1 ] && [ -z "$(git branch -r --contains "$head")" ]; then die "HEAD is not pushed"; fi
  base=$(git merge-base origin/main HEAD)
else
  [ "$post" = 0 ] || die "no origin remote to post statuses to (use --dry-run)"
  base=$(git rev-list --max-parents=0 HEAD | tail -n1)
fi
changed=$(git diff --name-only "$base" HEAD)
# Docs-only = a non-empty diff touching nothing that ships or builds. Anything under tool/ ships
# (Light's extractor takes .md assets too), and an empty diff (e.g. main itself) is not docs-only.
docs_only=1
[ -z "$changed" ] && docs_only=0
while IFS= read -r f; do
  [ -z "$f" ] && continue
  case "$f" in
    tool/*|light-sdk*|scripts/*|*.gradle.kts|gradle*|mise.toml|.github/workflows/*) docs_only=0 ;;
    *.md|docs/*|.github/PULL_REQUEST_TEMPLATE*|.github/ISSUE_TEMPLATE/*) ;;
    *) docs_only=0 ;;
  esac
done <<<"$changed"
note "commit \`${head:0:12}\`; files changed vs base: $(grep -c . <<<"$changed")"

provenance="local ci via scripts/ci.sh (agent session)"

post_status() {  # state, context, description [, url]; GitHub rejects descriptions over 140 characters
  local desc=$3
  [ "${#desc}" -le 140 ] || desc="${desc:0:139}…"
  gh api -X POST "repos/{owner}/{repo}/statuses/$head" -f state="$1" -f context="signoff/$2" \
    -f description="$desc" ${4:+-f target_url="$4"} >/dev/null
}

fail_ctx() {  # context, step description
  echo "ci: FAIL [$1] $2" >&2
  if [ "$post" = 1 ]; then
    post_status failure "$1" "local ci: $2" || echo "ci: could not post the failure status for signoff/$1" >&2
  fi
  exit 1
}

# Round trip: install, launch, and require that the Tool holds the window focus and shows its board.
roundtrip() {  # apk -> prints one evidence line, returns 1 on failure
  local focus=""
  emu install -r "$1" >/dev/null || { echo "install failed"; return 1; }
  emu shell am force-stop $pkg || { echo "force-stop failed"; return 1; }
  emu shell monkey -p $pkg 1 >/dev/null 2>&1 || { echo "launch failed"; return 1; }
  for _ in $(seq 1 20); do
    focus=$(emu shell dumpsys window | grep -m1 mCurrentFocus | tr -d '\r')
    grep -q "$pkg" <<<"$focus" && break
    sleep 1
  done
  grep -q "$pkg" <<<"$focus" || { echo "focus is not the Tool: ${focus##* }"; return 1; }
  mise run ui wait "Chess" >/dev/null 2>&1 || { echo "the Tool never showed its screen"; return 1; }
  echo "installed, launched, window focus is $pkg, screen shows \"Chess\""
}

# --- signoff/emulator
if [ "$docs_only" = 1 ]; then
  note "emulator: not applicable (docs-only change)"
else
  ./gradlew -q --console=plain :tool:testDebugUnitTest || fail_ctx emulator "unit tests"
  tests=$(cat tool/build/test-results/testDebugUnitTest/*.xml | grep -oE '<testsuite [^>]*tests="[0-9]+"' | grep -oE 'tests="[0-9]+"' | grep -oE '[0-9]+' | paste -sd+ - | bc)
  note "unit + property tests: $tests passed"
  ./gradlew -q --console=plain :tool:testBenchmarkUnitTest --tests com.yarosz.chess.bench.BenchSuiteJvmTest \
    || fail_ctx emulator "benchmark JVM test"
  scripts/bench-test.sh >/dev/null || fail_ctx emulator "bench.sh check"
  note "benchmark harness: JVM test and bench.sh check passed"

  lblog=$(mktemp)
  if ! scripts/light-build.sh >"$lblog" 2>&1; then
    tail -25 "$lblog" >&2
    fail_ctx emulator "Light-builder simulation"
  fi
  lb=$(grep '^light-build:' "$lblog"); rm -f "$lblog"
  note "Light-builder simulation (lightbuilder prepare + unsigned minified release): ${lb#light-build: }"

  emu serial >/dev/null || fail_ctx emulator "no emulator running AVD ${CHESS_AVD:-LightPhone3-chess} (mise run emu)"
  # Android letterboxes a portrait-locked app whose area is shorter than wide; the LP3 gives
  # 1080x1168 at 480 dpi, so an emulator that differs lays out a board no phone shows.
  app=$(emu shell dumpsys window displays | grep -m1 ' app=' | tr -d '\r' | grep -oE 'app=[0-9]+x[0-9]+')
  dpi=$(emu shell wm density | tr -d '\r' | tail -1 | grep -oE '[0-9]+$')
  if [ "$app" != app=1080x1168 ] || [ "$dpi" != 480 ]; then
    echo "ci: emulator shows ${app:-app=?} at ${dpi:-?}dpi; the LP3 is app=1080x1168 at 480dpi (scripts/emulator/RECIPE.md)" >&2
    fail_ctx emulator "emulator app area is not the LP3's (need 1080x1168 at 480 dpi)"
  fi
  # The emulator talks to the LightOS emulator app, not LightOS: swap the server package for this build.
  scripts/emulator-build.sh ./gradlew -q --console=plain :tool:assembleDebug || fail_ctx emulator "assembleDebug"
  line=$(roundtrip tool/build/outputs/apk/debug/tool-debug.apk) || fail_ctx emulator "round trip: $line"
  note "emulator round trip: $line"
fi

note "run: $(( $(date +%s) - started ))s by \`scripts/ci.sh\` (local CI, agent session)"

body=$(printf '**Local CI** for `%s`\n\n' "${head:0:12}"; printf -- '- %s\n' "${evidence[@]}")
if [ "$post" = 0 ]; then printf '\n%s\n' "$body"; exit 0; fi

# Attest to exactly what was tested: the tree must still be clean and HEAD unchanged, and statuses are
# pinned to the tested commit, not to whatever HEAD is at post time.
[ -z "$(git status --porcelain)" ] || die "working tree changed during the run; not posting"
[ "$(git rev-parse HEAD)" = "$head" ] || die "HEAD moved during the run; not posting"

url=""
if pr=$(gh pr view --json number -q .number 2>/dev/null); then
  url=$(gh pr comment "$pr" --body "$body" 2>/dev/null | grep -oE 'https://github.com/[^ ]+' | tail -1)
fi
post_status success emulator "$provenance" "$url" || die "posting signoff/emulator"
echo "ci: posted signoff/emulator on ${head:0:12}${url:+ ($url)}"
