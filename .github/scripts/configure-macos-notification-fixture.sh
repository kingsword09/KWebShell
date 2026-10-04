#!/usr/bin/env bash
set -euo pipefail

# GitHub's macOS image unloads this agent. Local notification permission and
# action UI require it even though the fixture does not use remote push.
user_id="$(id -u)"
domain="gui/$user_id"
plist="/System/Library/LaunchAgents/com.apple.notificationcenterui.plist"
label="$(plutil -extract Label raw -o - "$plist")"
service="$domain/$label"

launchctl print "$domain" >/dev/null
launchctl enable "$service"
if ! launchctl print "$service" >/dev/null 2>&1; then
  launchctl bootstrap "$domain" "$plist"
fi
launchctl kickstart "$service"

for ((attempt = 0; attempt < 50; attempt++)); do
  if pgrep -u "$user_id" -x NotificationCenter >/dev/null; then
    printf '%s\n' 'Notification fixture: macOS Notification Center agent is running.'
    exit 0
  fi
  sleep 0.2
done

printf '%s\n' 'Notification fixture: macOS Notification Center agent did not start.' >&2
exit 1
