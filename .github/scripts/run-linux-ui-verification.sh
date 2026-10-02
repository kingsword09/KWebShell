#!/usr/bin/env bash
set -euo pipefail

if [[ "$#" -eq 0 ]]; then
  echo "A verification command is required." >&2
  exit 2
fi

openbox_log="${RUNNER_TEMP:-/tmp}/kwebshell-openbox.log"
openbox >"$openbox_log" 2>&1 &
openbox_pid=$!
notification_fixture_pid=""
notification_fixture_log="${RUNNER_TEMP:-/tmp}/kwebshell-notification-fixture.log"
if [[ -n "${KWEB_NOTIFICATIONS_FIXTURE_BINARY:-}" ]]; then
  "$KWEB_NOTIFICATIONS_FIXTURE_BINARY" >"$notification_fixture_log" 2>&1 &
  notification_fixture_pid=$!
  trap 'kill "$notification_fixture_pid" 2>/dev/null || true; wait "$notification_fixture_pid" 2>/dev/null || true; kill "$openbox_pid" 2>/dev/null || true; wait "$openbox_pid" 2>/dev/null || true' EXIT
  notification_fixture_ready=false
  for _ in {1..100}; do
    if gdbus introspect --session --dest org.freedesktop.Notifications --object-path /org/freedesktop/Notifications >/dev/null 2>&1; then
      notification_fixture_ready=true
      break
    fi
    if ! kill -0 "$notification_fixture_pid" 2>/dev/null; then
      echo "Notification fixture exited before claiming org.freedesktop.Notifications:" >&2
      cat "$notification_fixture_log" >&2
      exit 1
    fi
    sleep 0.1
  done
  if [[ "$notification_fixture_ready" != true ]]; then
    echo "Notification fixture did not publish org.freedesktop.Notifications:" >&2
    cat "$notification_fixture_log" >&2
    exit 1
  fi
else
  trap 'kill "$openbox_pid" 2>/dev/null || true; wait "$openbox_pid" 2>/dev/null || true' EXIT
fi

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
