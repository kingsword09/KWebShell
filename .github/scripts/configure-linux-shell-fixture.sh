#!/usr/bin/env bash
set -euo pipefail

# This is a hosted-CI fixture for the real Linux GIO provider. It registers a
# disposable desktop application as the default URI/MIME handler; it is not a
# product fallback or an alternate shell implementation.
fixture_root="${RUNNER_TEMP:-${TMPDIR:-/tmp}}/kwebshell-linux-shell-fixture"
data_home="$fixture_root/xdg-data"
config_home="$fixture_root/xdg-config"
cache_home="$fixture_root/xdg-cache"
handler="$fixture_root/kwebshell-shell-handler"
handler_log="$fixture_root/handler.log"
desktop_file="$data_home/applications/kwebshell-hosted-shell-fixture.desktop"

mkdir -p "$data_home/applications" "$config_home" "$cache_home"

cat > "$handler" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

printf '%s\n' "$@" >> "${KWEB_SHELL_FIXTURE_LOG:?}"
EOF
chmod 755 "$handler"

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

{
  echo "XDG_DATA_HOME=$data_home"
  echo "XDG_CONFIG_HOME=$config_home"
  echo "XDG_CACHE_HOME=$cache_home"
  echo "XDG_CURRENT_DESKTOP=GNOME"
  echo "KWEB_SHELL_FIXTURE_LOG=$handler_log"
} >> "${GITHUB_ENV:?}"

for mime_type in x-scheme-handler/http x-scheme-handler/https text/plain; do
  gio mime "$mime_type" | grep -Fq "kwebshell-hosted-shell-fixture.desktop"
done
