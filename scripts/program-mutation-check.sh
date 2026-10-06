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
  # optional filter: ONLY="demo" runs just the mutants whose name contains that text
  if [ -n "$ONLY" ] && [[ "$name" != *"$ONLY"* ]]; then return; fi
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
  # one build and one test run per mutant; a failed build also counts as caught only if the tests did not compile for a different reason
  if (cd "$W" && anchor build >/dev/null 2>&1 && cargo test --workspace >/dev/null 2>&1); then echo "SURVIVED: $name"; else echo "CAUGHT:   $name"; fi
}
run_mutant "oracle check removed" programs/vowed/src/instructions/play.rs "        has_one = oracle @ VowedError::Unauthorized" ""
run_mutant "settle grace check removed" programs/vowed/src/instructions/play.rs "require!(now >= pool.settle_after_ts, VowedError::NotEnded);" ""
run_mutant "claim does not mark Claimed" programs/vowed/src/instructions/play.rs "    p.status = ParticipationStatus::Claimed;" ""
run_mutant "demo pools allowed for any allowed token" programs/vowed/src/instructions/pool.rs "require!(config.is_demo_mint(&mint.key()), VowedError::DemoMintNotAllowed);" ""
run_mutant "demo pools use the normal stake cap" programs/vowed/src/instructions/pool.rs "require!(stake <= config.demo_max_stake, VowedError::StakeTooLarge);" ""
run_mutant "demo participants not capped" programs/vowed/src/instructions/pool.rs "require!(params.max_participants <= DEMO_MAX_PARTICIPANTS, VowedError::InvalidPoolParams);" ""
run_mutant "demo pools can be created while disabled" programs/vowed/src/instructions/pool.rs "require!(config.demo_enabled, VowedError::DemoDisabled);" ""
run_mutant "demo day length unbounded" programs/vowed/src/instructions/pool.rs "(DEMO_MIN_DAY_SECS..=DEMO_MAX_DAY_SECS).contains(&params.demo_day_secs)" "true"
run_mutant "demo pools use real-day check-in windows" programs/vowed/src/instructions/play.rs "if pool.is_demo {" "if false {"
run_mutant "demo cap may exceed the normal cap" programs/vowed/src/instructions/admin.rs "require!(params.demo_max_stake <= params.max_stake, VowedError::InvalidConfig);" ""
