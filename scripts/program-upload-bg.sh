#!/usr/bin/env bash
# Starts the devnet upgrade fully detached from the caller (so a tool timeout cannot kill it) and returns at once.
# Progress: tail ~/vowed-upload.log (local file, no RPC traffic).
nohup bash "$(dirname "$0")/program-deploy-devnet.sh" > "$HOME/vowed-upload.log" 2>&1 < /dev/null &
echo "started pid $!; log: ~/vowed-upload.log"
