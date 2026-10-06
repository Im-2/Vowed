. "$(dirname "$0")/wsl-env.sh"
SRC="$(cd "$(dirname "$0")/../programs/vowed" && pwd)"; WORK="$HOME/vowed-program"; mkdir -p "$WORK"
rsync -a --delete --exclude target --exclude .anchor "$SRC"/ "$WORK"/
cd "$WORK" && rm -f Cargo.lock && cargo update 2>&1 | tail -15
