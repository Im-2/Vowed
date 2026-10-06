#!/usr/bin/env bash
# Mutation sanity check for the backend: break one guard at a time in a scratch copy and confirm the test suite fails.
# Never touches the repo sources. Needs scripts/backend-test.sh to have run once (installs node_modules).
. "$(dirname "$0")/wsl-env.sh"
SRC="$(cd "$(dirname "$0")/../backend" && pwd)"
BASE="$HOME/vowed-backend"
W="$HOME/vowed-backend-mutant"
export VOWED_SO="$HOME/vowed-program/target/deploy/vowed.so"
export VOWED_SHARED="$HOME/vowed-shared"
mkdir -p "$W"
run_mutant() {
  name="$1"; file="$2"; from="$3"; to="$4"
  rsync -a --delete --exclude node_modules --exclude '*.sqlite*' --exclude .devnet "$SRC"/ "$W"/
  ln -sfn "$BASE/node_modules" "$W/node_modules"
  python3 - "$W/$file" "$from" "$to" <<'PY'
import sys
p, a, b = sys.argv[1:4]
s = open(p).read()
assert a in s, "mutation target not found: " + a
open(p, "w").write(s.replace(a, b, 1))
PY
  if (cd "$W" && npx vitest run >/dev/null 2>&1); then echo "SURVIVED: $name"; else echo "CAUGHT:   $name"; fi
}
run_mutant "device signature not checked" src/proofs/service.ts 'if (!verifyDeviceSignature(device.pubkey, canonicalPackageBytes(pkg), pkg.signature))' 'if (false)'
run_mutant "proof nonce binding removed" src/proofs/service.ts '!same(pkg.nonce, session.nonce) ||' 'false ||'
run_mutant "replay no longer idempotent" src/proofs/service.ts 'if (session.status === "passed" && session.result_json) {' 'if (false) {'
run_mutant "stake cap by trust tier removed" src/http/challenges.ts 'if (stake > cap)' 'if (false)'
run_mutant "auth nonce reusable" src/http/auth.ts 'if (Number(used.changes) !== 1) throw unauthorized("nonce unknown, used or expired");' ''
run_mutant "squad membership not enforced" src/http/squads.ts 'if (!m) throw forbidden("not_member", "you are not a member of this squad");' ''
run_mutant "wallet signature on device registration not checked" src/http/devices.ts '!nacl.sign.detached.verify(msg, sig, new PublicKey(wallet).toBytes())' 'false'
run_mutant "attestation challenge not compared" src/devices/attestation.ts 'if (a.length !== b.length || !timingSafeEqual(a, b)) return fail("attestation challenge does not match");' ''
run_mutant "day window not enforced for sessions" src/proofs/service.ts 'if (!checkinWindowOk(now, c.start_ts, p.tz_offset_minutes, day))' 'if (false)'
run_mutant "proof session never marked in-flight (race)" src/proofs/service.ts "UPDATE proof_sessions SET status='processing' WHERE id = ? AND status = 'open'" "UPDATE proof_sessions SET status='open' WHERE id = ? AND status = 'open'"
