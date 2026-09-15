#!/usr/bin/env bash
# Merges the per-target RFC evidence manifests of one hosted run into the single
# checked-in manifest, collecting the retained artifact files each recorder
# wrote into its workspace tree. Deterministic: the same inputs produce the
# same manifest bytes regardless of merge order.
set -euo pipefail

cd "$(dirname "$0")/../.."

downloaded="build/rfc-evidence/downloaded"
artifacts_root="build/rfc-evidence/artifacts"
inputs=()
mkdir -p "$artifacts_root"

for bundle in "$downloaded"/*/; do
  [ -d "$bundle" ] || continue
  manifest=$(find "$bundle" -maxdepth 5 -name 'manifest-*.json' | head -1)
  if [ -z "$manifest" ]; then
    echo "No per-target manifest found in $bundle" >&2
    exit 1
  fi
  inputs+=("$manifest")
  if [ -d "$bundle/docs/rfcs/evidence/artifacts" ]; then
    cp -R "$bundle/docs/rfcs/evidence/artifacts/"* "$artifacts_root/"
  fi
done

if [ "${#inputs[@]}" -lt 2 ]; then
  echo "At least two per-target manifests are required to merge." >&2
  exit 1
fi

./gradlew --no-daemon :kweb-rfc-governance:rfcEvidenceMerge --args="
  merge ${inputs[*]}
  --output build/rfc-evidence/manifest.json"

echo "Merged RFC evidence manifest at build/rfc-evidence/manifest.json"
