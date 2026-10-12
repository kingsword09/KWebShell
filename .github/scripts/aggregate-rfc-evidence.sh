#!/usr/bin/env bash
# Records the hosted RFC evidence records for all three targets from
# the retained artifacts of this run's verification jobs. Runs on the
# aggregation job's LF checkout: each record binds the committed LF contract
# bytes and one target's retained artifact, and the final manifest lands where
# the check-in commit expects it.
set -euo pipefail

cd "$(dirname "$0")/../.."

downloaded="build/rfc-evidence/downloaded"
input="docs/rfcs/evidence/manifest.json"
catalog="docs/rfcs"
batch_arguments="build/rfc-evidence/batch-arguments.bin"
batch="build/rfc-evidence/batch.json"
selection="${1:-}"
if [[ "$#" -gt 1 ]]; then
  echo "Expected at most one execution plan." >&2
  exit 2
fi
if [[ -n "$selection" ]]; then
  python3 .github/scripts/ci_plan.py validate --plan "$selection"
  if [[ "$(jq '.refreshRfcs | length' "$selection")" == 0 ]]; then
    echo "No RFC evidence refresh selected."
    exit 0
  fi
  jq -e '.refreshRfcs - ["0001","0002","0003","0004","0006","0007","0008","0009","0010","0011","0012","0013","0014","0015","0017","0018","0019","0020","0027","0030"] | length == 0' "$selection" > /dev/null
fi

selected() {
  [[ -z "$selection" ]] || jq -e --arg rfc "$1" '.refreshRfcs | index($rfc) != null' "$selection" > /dev/null
}

notifications_selected() {
  [[ -z "$selection" ]] || jq -e '.families | index("electron-migration") != null' "$selection" > /dev/null
}

# RFC 0016 remains Accepted. Its reports are mandatory diagnostic artifacts,
# not Implemented support records. Validate every target before updating the
# manifest; retain the reports unchanged in the electron-migration artifacts.
if notifications_selected; then
for target in macos-arm64 windows-x64 linux-x64; do
  file=$(find "$downloaded" -type f -name migration-notifications-evidence.json -path "*electron-migration-${target}-*" | head -1)
  if [ -z "$file" ] || [ ! -f "$file" ]; then
    echo "Missing notifications evidence file for $target" >&2
    exit 1
  fi
  if ! jq -e --arg target "$target" '
    if $target == "linux-x64" then
      .actionVerificationMode == "os-ui" and
      .actionActivationObserved == true and .actionActivationCount == 1
    else
      .actionVerificationMode == "contract" and
      .actionActivationObserved == false and .actionActivationCount == 0
    end
  ' "$file" > /dev/null 2>&1; then
    echo "Invalid notification action evidence for $target: Linux requires one OS action; Windows/macOS require contract mode with zero activations." >&2
    exit 1
  fi
done
fi

if selected 0027; then
  python3 .github/scripts/validate_image_evidence.py --downloaded "$downloaded"
fi

windows_msix_proof="build/rfc-evidence/windows-msix-proof.zip"
if selected 0030; then
  python3 .github/scripts/retain_windows_msix_evidence.py \
    --downloaded "$downloaded" --output "$windows_msix_proof" \
    --source-revision "$GITHUB_SHA"
fi

mkdir -p build/rfc-evidence
: > "$batch_arguments"

record() {
  local target="$1" rfc="$2" provider="$3" artifact="$4" file="$5" extra_name="${6:-}" extra_file="${7:-}"
  if [ -z "$file" ] || [ ! -f "$file" ]; then
    echo "Missing $artifact evidence file for $target" >&2
    exit 1
  fi
  local arguments=(--rfc "$rfc" --provider "$provider" --electron-major 44 --target "$target" --artifact "$artifact=$file")
  if [ "$rfc" = "0001" ]; then
    local batch_tests
    batch_tests=$(find "$downloaded" -type f -name TEST-io.github.kingsword09.kwebshell.rfc.KWebRfcEvidenceBatchTest.xml -path "*rfc-governance-${target}-*" | head -1)
    if [ -z "$batch_tests" ] || [ ! -f "$batch_tests" ]; then
      echo "Missing batch recorder test evidence for $target" >&2
      exit 1
    fi
    arguments+=(--artifact "governance-batch-tests=$batch_tests" --artifact "batch-plan=$batch")
    if [[ -n "$selection" ]]; then
      policy_tests=$(find "$downloaded" -type f -name tests.txt -path '*ci-policy-tests-*' | head -1)
      task_audit=$(find "$downloaded" -type f -name audit.json -path "*ci-audit-${target}-*" | head -1)
      if [[ -z "$policy_tests" || -z "$task_audit" ]]; then
        echo "Missing CI policy tests or platform task audit for $target" >&2
        exit 1
      fi
      arguments+=(--artifact "ci-plan=$selection" --artifact "ci-policy-tests=$policy_tests" --artifact "ci-task-audit=$task_audit")
      if [[ "$(jq -r '.full' "$selection")" == true ]]; then
        probe_plan=$(find "$downloaded" -type f -name tray-selection-probe.json -path "*ci-audit-${target}-*" | head -1)
        probe_log=$(find "$downloaded" -type f -name tray-selection-probe.log -path "*ci-audit-${target}-*" | head -1)
        if [[ -z "$probe_plan" || -z "$probe_log" ]]; then
          echo "Missing native selection probe for $target" >&2
          exit 1
        fi
        arguments+=(--artifact "ci-native-selection=$probe_plan" --artifact "ci-native-selection-log=$probe_log")
      fi
    fi
  fi
  if [ -n "$extra_name" ]; then
    if [ -z "$extra_file" ] || [ ! -f "$extra_file" ]; then
      echo "Missing $extra_name evidence file for $target" >&2
      exit 1
    fi
    arguments+=(--artifact "$extra_name=$extra_file")
  fi
  if [ "$rfc" = "0008" ]; then
    arguments+=(--matrix-row web-contents-reload)
    arguments+=(--matrix-row web-contents-before-unload)
    arguments+=(--matrix-row window-open-handler)
    arguments+=(--matrix-row renderer-process-state)
  fi
  if [ "$rfc" = "0009" ]; then
    arguments+=(--matrix-row session-cookies)
    arguments+=(--matrix-row session-cache-clear)
    arguments+=(--matrix-row session-storage-clear)
    arguments+=(--matrix-row session-storage-usage)
    arguments+=(--matrix-row session-spellcheck)
    arguments+=(--matrix-row session-flush)
  fi
  if [ "$rfc" = "0010" ]; then
    arguments+=(--matrix-row session-web-request)
  fi
  if [ "$rfc" = "0011" ]; then
    arguments+=(--matrix-row certificate-error)
    arguments+=(--matrix-row select-client-certificate)
  fi
  if [ "$rfc" = "0012" ]; then
    arguments+=(--matrix-row downloads)
  fi
  if [ "$rfc" = "0013" ]; then
    arguments+=(--matrix-row node-fs)
  fi
  if [ "$rfc" = "0014" ]; then
    arguments+=(--matrix-row clipboard)
    clipboard_report=$(find "$downloaded" -type f -name compatibility.json -path "*electron-migration-${target}-*" | head -1)
    if [ -z "$clipboard_report" ] || [ ! -f "$clipboard_report" ]; then
      echo "Missing clipboard migration compatibility report for $target" >&2
      exit 1
    fi
    arguments+=(--service clipboard --from-compatibility-report "$clipboard_report")
  fi
  if [ "$rfc" = "0015" ]; then
    arguments+=(--service shell --matrix-row shell --from-compatibility-report "$extra_file")
  fi
  if [ "$rfc" = "0017" ]; then
    arguments+=(--service menus --matrix-row menu --from-compatibility-report "$extra_file")
    for menus_spec in \
      "menus-native-tests|menus-native-tests.xml|menus-integration" \
      "menus-contract-tests|TEST-io.github.kingsword09.kwebshell.service.menus.KWebMenusContractTest.xml|menus-integration" \
      "menus-bridge-tests|TEST-io.github.kingsword09.kwebshell.service.menus.KWebMenusBridgeTest.xml|menus-integration" \
      "menus-package|native-menus-package.json|menus-integration" \
      "menus-runtime|kweb-service-menus-1.0.0-${target}.zip|menus-integration" \
      "context-menu|context-menu-evidence.json|engine-integration"; do
      IFS='|' read -r menus_key menus_name menus_family <<< "$menus_spec"
      menus_file=$(find "$downloaded" -type f -name "$menus_name" -path "*${menus_family}-${target}-*" | head -1)
      arguments+=(--artifact "$menus_key=$menus_file")
    done
  fi
  if [ "$rfc" = "0018" ]; then
    arguments+=(--service tray --matrix-row tray --from-compatibility-report "$extra_file")
    for tray_spec in \
      "tray-native-tests|tray-native-tests.xml|tray-integration" \
      "tray-contract-tests|TEST-io.github.kingsword09.kwebshell.service.tray.KWebTraysContractTest.xml|tray-integration" \
      "tray-package|native-tray-package.json|tray-integration" \
      "tray-runtime|kweb-service-tray-1.0.0-${target}.zip|tray-integration"; do
      IFS='|' read -r tray_key tray_name tray_family <<< "$tray_spec"
      tray_file=$(find "$downloaded" -type f -name "$tray_name" -path "*${tray_family}-${target}-*" | head -1)
      arguments+=(--artifact "$tray_key=$tray_file")
    done
  fi
  if [ "$rfc" = "0019" ]; then
    arguments+=(--service system-preferences --matrix-row native-theme --matrix-row system-preferences --from-compatibility-report "$extra_file")
    for preferences_spec in \
      "preferences-native-tests|preferences-native-tests.xml|preferences-integration" \
      "preferences-contract-tests|TEST-io.github.kingsword09.kwebshell.service.preferences.KWebSystemPreferencesContractTest.xml|preferences-integration" \
      "preferences-bridge-tests|TEST-io.github.kingsword09.kwebshell.service.preferences.KWebSystemPreferencesBridgeTest.xml|preferences-integration" \
      "preferences-package|native-preferences-package.json|preferences-integration" \
      "preferences-runtime|kweb-service-system-preferences-1.0.0-${target}.zip|preferences-integration" \
      "theme-probe|theme-evidence.json|engine-integration"; do
      IFS='|' read -r preferences_key preferences_name preferences_family <<< "$preferences_spec"
      preferences_file=$(find "$downloaded" -type f -name "$preferences_name" -path "*${preferences_family}-${target}-*" | head -1)
      arguments+=(--artifact "$preferences_key=$preferences_file")
    done
  fi
  if [ "$rfc" = "0020" ]; then
    arguments+=(--service screen --matrix-row screen --from-compatibility-report "$extra_file")
    for screen_spec in \
      "screen-native-tests|screen-native-tests.xml|screen-integration" \
      "screen-contract-tests|TEST-io.github.kingsword09.kwebshell.service.screen.KWebScreensContractTest.xml|screen-integration" \
      "screen-bridge-tests|TEST-io.github.kingsword09.kwebshell.service.screen.KWebScreensBridgeTest.xml|screen-integration" \
      "screen-package|native-screen-package.json|screen-integration" \
      "screen-runtime|kweb-service-screen-1.0.0-${target}.zip|screen-integration"; do
      IFS='|' read -r screen_key screen_name screen_family <<< "$screen_spec"
      screen_file=$(find "$downloaded" -type f -name "$screen_name" -path "*${screen_family}-${target}-*" | head -1)
      arguments+=(--artifact "$screen_key=$screen_file")
    done
    # The Wayland session is verified on the Linux target only.
    if [ "$target" = "linux-x64" ]; then
      screen_wayland=$(find "$downloaded" -type f -name "screen-wayland-transcript.txt" -path "*screen-integration-${target}-*" | head -1)
      if [ -z "$screen_wayland" ] || [ ! -f "$screen_wayland" ]; then
        echo "Missing Wayland screen transcript for $target" >&2
        exit 1
      fi
      arguments+=(--artifact "screen-wayland=$screen_wayland")
    fi
  fi
  if [ "$rfc" = "0027" ]; then
    arguments+=(--service native-image --matrix-row native-image --from-compatibility-report "$extra_file")
    for image_spec in \
      "migration-image|migration-image-evidence.json|electron-migration" \
      "image-package|native-image-package.json|image-integration" \
      "image-runtime|kweb-service-image-1.0.0-${target}.zip|image-integration" \
      "image-native-tests|image-native-tests.xml|image-integration" \
      "image-codec-tests|TEST-io.github.kingsword09.kwebshell.service.image.JvmKWebImageCodecTest.xml|image-integration" \
      "image-contract-tests|TEST-io.github.kingsword09.kwebshell.service.image.KWebImageContractTest.xml|image-integration"; do
      IFS='|' read -r image_key image_name image_family <<< "$image_spec"
      image_file=$(find "$downloaded" -type f -name "$image_name" -path "*${image_family}-${target}-*" | head -1)
      arguments+=(--artifact "$image_key=$image_file")
    done
  fi
  printf '%s\0' "${arguments[@]}" >> "$batch_arguments"
  printf '\0' >> "$batch_arguments"
}

for target in macos-arm64 windows-x64 linux-x64; do
  specs=(
    "0001|governance.hosted|governance-contract-tests|TEST-io.github.kingsword09.kwebshell.rfc.KWebRfcGovernanceCheckerTest.xml|rfc-governance"
    "0002|governance.hosted|provider-lifecycle-report|provider-lifecycle-report.json|provider-lifecycle"
    "0003|governance.hosted|dialogs-consent|consent-status.json|native-dialogs"
    "0004|governance.hosted|stream-conformance|stream-conformance.json|engine-integration"
    "0030|packaging.hosted|application-package|application-package-report.json|application-package"
    "0006|application.lifecycle.hosted|application-lifecycle|application-lifecycle-report.json|application-lifecycle|application-shutdown|application-shutdown.json|engine-integration"
    "0008|page.lifecycle.hosted|page-lifecycle|page-lifecycle-evidence.json|engine-integration|renderer-lifecycle|renderer-lifecycle-evidence.json|engine-integration"
    "0009|profile.data.hosted|profile-data|profile-data-evidence.json|engine-integration"
    "0010|network.policy.hosted|network-policy|network-policy-evidence.json|engine-integration"
    "0011|security.hosted|security-challenge|security-challenge-evidence.json|engine-integration|mtls-probe|mtls-probe-evidence.json|engine-integration"
    "0012|downloads.hosted|downloads|downloads-evidence.json|engine-integration"
    "0013|files.hosted|files|files-evidence.json|engine-integration|migration-files|compatibility.json|electron-migration"
    "0014|clipboard.hosted|clipboard|clipboard-evidence.json|clipboard|migration-clipboard|migration-clipboard-evidence.json|electron-migration"
    "0015|shell.hosted|shell|migration-shell-evidence.json|electron-migration|migration-shell|compatibility.json|electron-migration"
    "0017|menus.hosted|menus-integration|menus-integration.json|menus-integration|compatibility|compatibility.json|electron-migration"
    "0018|tray.hosted|tray-integration|tray-integration.json|tray-integration|compatibility|compatibility.json|electron-migration"
    "0019|preferences.hosted|preferences-integration|preferences-integration.json|preferences-integration|compatibility|compatibility.json|electron-migration"
    "0020|screen.hosted|screen-integration|screen-integration.json|screen-integration|compatibility|compatibility.json|electron-migration"
    "0027|image.hosted|native-image|native-image-evidence.json|image-integration|compatibility|compatibility.json|electron-migration"
    "0007|window-controls.hosted|window-controls-report|window-controls-report.json|provider-lifecycle"
  )
  for spec in "${specs[@]}"; do
    IFS='|' read -r rfc provider artifact name family extra_name extra_file_name extra_family <<< "$spec"
    if ! selected "$rfc"; then
      continue
    fi
    file=$(find "$downloaded" -type f -name "$name" -path "*${family}-${target}-*" | head -1)
    extra_file=""
    if [ -n "${extra_name:-}" ]; then
      extra_file=$(find "$downloaded" -type f -name "$extra_file_name" -path "*${extra_family}-${target}-*" | head -1)
    fi
    if [ "$rfc" = "0030" ] && [ "$target" = "windows-x64" ]; then
      record "$target" "$rfc" "$provider" "$artifact" "$file" "windows-msix-proof" "$windows_msix_proof"
    else
      record "$target" "$rfc" "$provider" "$artifact" "$file" "${extra_name:-}" "${extra_file:-}"
    fi
  done
done

# NUL-delimited arguments preserve paths containing spaces without shell parsing.
python3 - "$batch_arguments" "$batch" <<'PYTHON'
import json
from pathlib import Path
import sys

fields = {"--rfc": "rfcId", "--provider": "providerId", "--target": "target",
          "--electron-major": "electronFixtureMajor", "--service": "serviceId",
          "--from-compatibility-report": "compatibilityReportPath"}
records = []
for group in Path(sys.argv[1]).read_bytes().split(b"\0\0"):
    if not group:
        continue
    arguments = group.decode("utf-8").split("\0")
    if len(arguments) % 2:
        raise ValueError("Batch arguments must be option/value pairs")
    record = {"artifacts": [], "matrixRowIds": []}
    for option, value in zip(arguments[::2], arguments[1::2]):
        if option == "--artifact":
            name, path = value.split("=", 1)
            record["artifacts"].append({"name": name, "path": path})
        elif option == "--matrix-row":
            record["matrixRowIds"].append(value)
        else:
            key = fields[option]
            if key in record:
                raise ValueError("Duplicate batch option: " + option)
            record[key] = int(value) if option == "--electron-major" else value
    records.append(record)
Path(sys.argv[2]).write_text(json.dumps({"schemaVersion": 1, "records": records}) + "\n")
PYTHON

./gradlew --no-daemon :kweb-rfc-governance:rfcEvidenceRecord \
  -PrfcEvidenceArguments="recordBatch $input $input --catalog $catalog --runtime runtime/cef-runtime.json --contracts docs/rfcs/evidence/contracts.json --repository-root . --batch $batch"

echo "Hosted RFC evidence recorded into $input"
