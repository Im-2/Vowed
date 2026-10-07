#!/usr/bin/env bash
# Scans the working tree and the WHOLE git history for secrets before anything is pushed. Exit 1 on any finding.
# Run from Git Bash:  bash scripts/secret-scan.sh
# A 64-byte base58 string is only a failure if it decodes to a valid Solana secret key (transaction signatures have the same shape).
cd "$(dirname "$0")/.." || exit 1
fail=0
REVS=$(git rev-list --all)

echo "== sensitive file names ever committed"
bad=$(git log --all --name-only --pretty=format: | sort -u | grep -iE '(^|/)(\.env$|\.env\.[^e]|id\.json$|.*-keypair\.json$|.*\.keystore$|.*\.jks$|.*\.p12$|.*\.sqlite|\.devnet/|google-services\.json$|local\.properties$)' )
if [ -n "$bad" ]; then echo "FOUND:"; echo "$bad"; fail=1; else echo "none"; fi

scan() {
  label="$1"; shift
  out=$(git grep -I -n -E "$@" $REVS 2>/dev/null | sed -E 's/^[0-9a-f]{40}://' | sort -u | cut -c1-160)
  if [ -n "$out" ]; then echo "FOUND $label:"; echo "$out" | head -8; fail=1; else echo "none: $label"; fi
}
scan "PEM private keys"                 -e '-----BEGIN ([A-Z ]+ )?PRIVATE KEY-----'
scan "64-number secret-key arrays"      -e '\[ *([0-9]{1,3} *, *){63}[0-9]{1,3} *\]'
scan "Google API keys"                  -e 'AIza[0-9A-Za-z_-]{30,}'
scan "Google API keys (AQ. format)"     -e '(^|[^A-Za-z0-9_.])AQ\.[A-Za-z0-9_-]{30,}'
scan "OpenAI/Anthropic-style keys"      -e 'sk-(ant-)?[A-Za-z0-9_-]{20,}'
scan "GitHub/Slack/AWS tokens"          -e '(gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|xox[abprs]-[A-Za-z0-9-]{10,}|AKIA[0-9A-Z]{16})'
scan "JWT-shaped tokens"                -e 'eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{10,}'
scan "service-account private_key"      -e '"private_key" *: *"-----'
scan "long hex assigned to secret names" -e '(SECRET|PRIVATE|TOKEN|PASSWORD|API_?KEY)[A-Z_]* *[=:] *"?[0-9a-fA-F]{40,}'
scan "12/24-word lowercase lines (seed phrase heuristic)" -e '^[[:space:]]*([a-z]{3,8} ){11}[a-z]{3,8}[[:space:]]*$|^[[:space:]]*([a-z]{3,8} ){23}[a-z]{3,8}[[:space:]]*$'

echo "== the exact local secrets (never printed): gemini key"
if [ -f backend/.devnet/gemini-key.txt ]; then
  K=$(tr -d '
 ' < backend/.devnet/gemini-key.txt)
  if [ -n "$K" ]; then
    if git grep -I -q -F -e "$K" $REVS 2>/dev/null || git grep -I -q -F -e "$K" 2>/dev/null; then echo "FOUND the local Gemini key in the repository (working tree or history)"; fail=1; else echo "none"; fi
    # also untracked, non-ignored files that are about to be added
    if git ls-files --others --exclude-standard | xargs -r grep -I -l -F -e "$K" 2>/dev/null | grep -q .; then echo "FOUND the local Gemini key in an untracked file"; fail=1; fi
  fi
else echo "no local key file"; fi

echo "== 64-byte base58 strings (checked: only valid secret keys fail)"
cands=$(git grep -I -h -o -E '[1-9A-HJ-NP-Za-km-z]{85,90}' $REVS 2>/dev/null | sort -u)
if [ -z "$cands" ]; then echo "none"; else
  for c in $cands; do
    res=$(cd backend && V="$c" node -e "
      const b=require('bs58'),{Keypair}=require('@solana/web3.js');
      try{Keypair.fromSecretKey((b.default||b).decode(process.env.V));console.log('KEY')}catch{console.log('ok')}" 2>/dev/null)
    if [ "$res" = "KEY" ]; then echo "FOUND a valid Solana secret key in history"; fail=1; else echo "ok: not a key (${c:0:8}...)"; fi
  done
fi

echo "== untracked files that are not ignored (would be committed next)"
git status --porcelain | grep '^??' || echo "none"

if [ $fail -ne 0 ]; then echo; echo "SECRET SCAN FAILED: do not push."; exit 1; fi
echo; echo "SECRET SCAN CLEAN"
