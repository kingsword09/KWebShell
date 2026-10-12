#!/usr/bin/env bash
set -euo pipefail
roots=(runtimeCheck :kweb-example-app-benchmark:applicationBenchmarkIntegrationTest :kweb-example-html5-lab:html5TestSiteRun)
while IFS= read -r task; do
  roots+=("$task")
done < <(jq -r '.modules[][]' .github/ci/suites.json)
./gradlew --no-daemon --no-configuration-cache --dry-run \
  -I .github/ci/export-graph.gradle "${roots[@]}" \
  -PkwebBenchmarkMachineClass=github-hosted-windows-2022-x64
