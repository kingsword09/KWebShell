#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -eq 0 ]]; then
  echo "A verification command is required." >&2
  exit 2
fi

openbox_log="${RUNNER_TEMP:-/tmp}/kwebshell-openbox.log"
openbox >"$openbox_log" 2>&1 &
openbox_pid=$!
trap 'kill "$openbox_pid" 2>/dev/null || true; wait "$openbox_pid" 2>/dev/null || true' EXIT

for _ in {1..300}; do
  if [[ "$(xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null)" == *"window id"* ]]; then
    "$@"
    exit
  fi
  if ! kill -0 "$openbox_pid" 2>/dev/null; then
    echo "Openbox exited before publishing its root window:" >&2
    cat "$openbox_log" >&2
    exit 1
  fi
  sleep 0.1
done

echo "Openbox did not publish its root window before verification:" >&2
cat "$openbox_log" >&2
exit 1
