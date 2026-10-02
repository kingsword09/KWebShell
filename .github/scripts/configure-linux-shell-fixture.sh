#!/usr/bin/env bash
set -euo pipefail

# This is a hosted-CI fixture for the real Linux GIO provider. It registers a
# disposable desktop application as the default URI/MIME handler; it is not a
# product fallback or an alternate shell implementation.
fixture_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/kwebshell-linux-shell-fixture"
user_home="$(getent passwd "$(id -u)" | cut -d: -f6)"
data_home="${XDG_DATA_HOME:-$user_home/.local/share}"
config_home="${XDG_CONFIG_HOME:-$user_home/.config}"
handler="$fixture_root/kwebshell-shell-handler"
handler_log="$fixture_root/handler.log"
desktop_file="$data_home/applications/kwebshell-hosted-shell-fixture.desktop"
file_manager_source="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/linux-file-manager-fixture.c"
file_manager_binary="$fixture_root/kwebshell-file-manager-fixture"
file_manager_service_dir="$data_home/dbus-1/services"
file_manager_service="$file_manager_service_dir/org.freedesktop.FileManager1.service"
notification_source="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/linux-notification-fixture.c"
notification_binary="$fixture_root/kwebshell-notification-fixture"

mkdir -p "$fixture_root" "$data_home/applications" "$file_manager_service_dir" "$config_home"

cat > "$handler" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$@" >> "${KWEB_SHELL_FIXTURE_LOG:?}"
EOF
chmod 755 "$handler"

gio_flags=( $(pkg-config --cflags --libs gio-2.0) )
cc -std=c11 -Wall -Wextra -Werror "$file_manager_source" "${gio_flags[@]}" -o "$file_manager_binary"
chmod 755 "$file_manager_binary"
cc -std=c11 -Wall -Wextra -Werror "$notification_source" "${gio_flags[@]}" -o "$notification_binary"
chmod 755 "$notification_binary"

cat > "$file_manager_service" <<EOF
[D-BUS Service]
Name=org.freedesktop.FileManager1
Exec=$file_manager_binary
EOF

cat > "$desktop_file" <<EOF
[Desktop Entry]
Type=Application
Name=KWebShell hosted shell fixture
Comment=Disposable handler used by hosted KWebShell verification
Exec=$handler %u
Terminal=false
NoDisplay=true
MimeType=x-scheme-handler/http;x-scheme-handler/https;text/plain;application/octet-stream;inode/directory;
EOF

cat > "$config_home/mimeapps.list" <<'EOF'
[Default Applications]
x-scheme-handler/http=kwebshell-hosted-shell-fixture.desktop
x-scheme-handler/https=kwebshell-hosted-shell-fixture.desktop
text/plain=kwebshell-hosted-shell-fixture.desktop
application/octet-stream=kwebshell-hosted-shell-fixture.desktop
inode/directory=kwebshell-hosted-shell-fixture.desktop

[Added Associations]
x-scheme-handler/http=kwebshell-hosted-shell-fixture.desktop;
x-scheme-handler/https=kwebshell-hosted-shell-fixture.desktop;
text/plain=kwebshell-hosted-shell-fixture.desktop;
application/octet-stream=kwebshell-hosted-shell-fixture.desktop;
inode/directory=kwebshell-hosted-shell-fixture.desktop;
EOF

desktop-file-validate "$desktop_file"
update-desktop-database "$data_home/applications"

XDG_DATA_HOME="$data_home" dbus-run-session -- \
  gdbus introspect \
    --session \
    --dest org.freedesktop.FileManager1 \
    --object-path /org/freedesktop/FileManager1 \
    >/dev/null

export XDG_CURRENT_DESKTOP=GNOME
export KWEB_SHELL_FIXTURE_LOG="$handler_log"

{
  echo "KWEB_SHELL_FIXTURE_LOG=$handler_log"
  echo "KWEB_NOTIFICATIONS_FIXTURE_BINARY=$notification_binary"
} >> "${GITHUB_ENV:?}"

for mime_type in x-scheme-handler/http x-scheme-handler/https text/plain; do
  gio mime "$mime_type" | grep -Fq "kwebshell-hosted-shell-fixture.desktop"
done
