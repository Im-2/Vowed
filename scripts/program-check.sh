# Fast loop: sync sources to the Linux filesystem, then run what is passed (default: unit tests of the program crate).
. "$(dirname "$0")/wsl-env.sh"
SRC="$(cd "$(dirname "$0")/../programs/vowed" && pwd)"; WORK="$HOME/vowed-program"; mkdir -p "$WORK"
rsync -a --delete --exclude target --exclude .anchor "$SRC"/ "$WORK"/
cd "$WORK"
if [ "$#" -eq 0 ]; then cargo test -p vowed --lib 2>&1 | tail -40; else "$@" 2>&1 | tail -80; fi
