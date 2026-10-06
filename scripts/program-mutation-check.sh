#!/usr/bin/env bash
# Mutation sanity check: break one guard at a time in a scratch copy and confirm the tests catch it.
# Never touches the repo sources. Prints CAUGHT or SURVIVED for each mutation.
. "$(dirname "$0")/wsl-env.sh"
SRC="$(cd "$(dirname "$0")/../programs/vowed" && pwd)"
REPO="$(cd "$(dirname "$0")/.." && pwd)"
W="$HOME/vowed-mutant"
rm -rf "$W"; mkdir -p "$W/target/deploy"
run_mutant() {
  name="$1"; file="$2"; from="$3"; to="$4"
  rsync -a --delete --exclude target --exclude .anchor "$SRC"/ "$W"/
  mkdir -p "$W/shared" && rsync -a --delete "$REPO/shared/" "$W/shared/"
  cp "$HOME/.config/solana/vowed-program-keypair.json" "$W/target/deploy/vowed-keypair.json"
  python3 - "$W/$file" "$from" "$to" <<'PY'
import sys
p, a, b = sys.argv[1:4]
s = open(p).read()
assert a in s, "mutation target not found: " + a
open(p, "w").write(s.replace(a, b, 1))
PY
  (cd "$W" && anchor build >/dev/null 2>&1 && cargo test --workspace 2>&1 | grep -E "^test result: FAILED|^test .* FAILED" | head -4)
  if (cd "$W" && cargo test --workspace >/dev/null 2>&1); then echo "SURVIVED: $name"; else echo "CAUGHT:   $name"; fi
}
run_mutant "oracle check removed" programs/vowed/src/instructions/play.rs "        has_one = oracle @ VowedError::Unauthorized" ""
run_mutant "settle grace check removed" programs/vowed/src/instructions/play.rs "require!(now >= pool.settle_after_ts, VowedError::NotEnded);" ""
run_mutant "claim does not mark Claimed" programs/vowed/src/instructions/play.rs "    p.status = ParticipationStatus::Claimed;" ""
