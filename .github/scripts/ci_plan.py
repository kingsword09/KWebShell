import argparse
from collections import deque
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


TARGETS = ("macos-arm64", "windows-x64", "linux-x64")
PLAN_FIELDS = {"schemaVersion", "revision", "base", "changedPaths", "full", "modules",
               "roots", "tasks", "refreshRfcs", "families", "features", "reasons"}
FEATURES = {"cef", "native", "node", "notifications", "shell", "screen", "msix",
            "benchmark", "liveHtml5"}
GLOBAL_FILES = {"build.gradle.kts", "settings.gradle.kts", "gradle.properties",
                "gradlew", "gradlew.bat", "package.json", "package-lock.json",
                ".gitattributes", ".gitignore", "AGENTS.md"}
GLOBAL_PREFIXES = ("gradle/", "runtime/", ".github/", "build-logic/")


class PlanError(Exception):
    def __init__(self, kind, message):
        super().__init__(message)
        self.code = "ci." + kind


class StrictParser(argparse.ArgumentParser):
    def error(self, message):
        raise PlanError("invalid-options", "The CI command options do not match the declared interface.")


def require(condition, kind, message):
    if not condition:
        raise PlanError(kind, message)


def keys(value, expected, kind):
    require(isinstance(value, dict) and set(value) == set(expected), kind,
            "JSON fields must match the versioned contract exactly.")


def strings(value, maximum, kind):
    require(isinstance(value, list) and len(value) <= maximum and
            all(isinstance(item, str) and item for item in value) and
            len(set(value)) == len(value), kind, "Expected a bounded unique string array.")


def safe_path(value):
    require(isinstance(value, str) and value and not any(character in value for character in "\\\0\r\n:")
            and not PurePosixPath(value).is_absolute() and
            all(part not in ("", ".", "..") for part in value.split("/")),
            "unsafe-path", "Expected a safe repository-relative path.")
    return value


def load(path):
    def unique(pairs):
        result = {}
        for name, value in pairs:
            require(name not in result, "invalid-input", "Duplicate JSON fields are not allowed.")
            result[name] = value
        return result
    try:
        return json.loads(Path(path).read_text(), object_pairs_hook=unique)
    except (ValueError, OSError) as error:
        raise PlanError("invalid-input", "Cannot read valid JSON input.") from error


def write(path, value):
    destination = Path(path)
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(destination.name + ".tmp")
    temporary.write_text(json.dumps(value, sort_keys=True, indent=2) + "\n")
    temporary.replace(destination)


def git(root, *arguments):
    try:
        return subprocess.run(["git", "-C", str(root), *arguments], check=True,
                              capture_output=True, timeout=60).stdout
    except (OSError, subprocess.SubprocessError) as error:
        raise PlanError("git-input", "Git revision or tracked input is unavailable.") from error


def revision(root, value):
    require(bool(re.fullmatch(r"[0-9a-f]{40}", value or "")), "git-input", "A full Git revision is required.")
    return git(root, "rev-parse", "--verify", value + "^{commit}").decode().strip()


def changes(root, event, event_name):
    head = revision(root, git(root, "rev-parse", "HEAD").decode().strip())
    full = event_name in ("schedule", "workflow_dispatch")
    base = head
    if event_name == "pull_request":
        source = revision(root, event["pull_request"]["head"]["sha"])
        base = revision(root, event["pull_request"]["base"]["sha"])
        base = git(root, "merge-base", base, source).decode().strip()
    elif event_name == "push":
        source = revision(root, event["after"])
        require(source == head, "git-input", "Push checkout does not match the event revision.")
        before = event["before"]
        if before == "0" * 40:
            full = True
        else:
            base = revision(root, before)
    else:
        require(full, "git-input", "This event is not supported by the CI planner.")
        source = head
    paths = git(root, "diff", "--name-only", "--no-renames", "-z", base, source).decode().split("\0")
    paths = sorted(filter(None, paths))
    strings(paths, 20000, "invalid-input")
    for path in paths:
        safe_path(path)
    return {"schemaVersion": 1, "revision": head, "sourceRevision": source, "base": base, "paths": paths, "full": full}


def only_documentation(changed):
    return not changed["full"] and all(path.endswith(".md") or
        (path.startswith("docs/rfcs/evidence/") and path != "docs/rfcs/evidence/contracts.json") or
        path.startswith("docs/rfcs/0005-migrate-cli-evidence/") for path in changed["paths"])


def documentation_plan(changed):
    keys(changed, {"schemaVersion", "revision", "sourceRevision", "base", "paths", "full"}, "invalid-input")
    require(type(changed["schemaVersion"]) is int and changed["schemaVersion"] == 1 and
            type(changed["full"]) is bool and only_documentation(changed),
            "invalid-input", "Only a documentation/evidence event can omit platform graphs.")
    strings(changed["paths"], 20000, "invalid-input")
    value = {"schemaVersion": 1, "revision": changed["revision"], "base": changed["base"],
             "changedPaths": changed["paths"], "full": False, "modules": [], "roots": [],
             "tasks": [], "refreshRfcs": [], "families": [], "features": feature_set(set()),
             "reasons": ["documentation-only:strict-governance-required"]}
    return validate_plan(value)


def validate_graph(graph, registry):
    keys(graph, {"schemaVersion", "modules", "tasks"}, "invalid-graph")
    keys(registry, {"schemaVersion", "modules", "rfcs", "families"}, "invalid-registry")
    require(type(graph["schemaVersion"]) is int and graph["schemaVersion"] == 1 and
            type(registry["schemaVersion"]) is int and registry["schemaVersion"] == 1,
            "invalid-input", "Only schema version 1 is supported.")
    modules, tasks = graph["modules"], graph["tasks"]
    require(isinstance(modules, dict) and 1 <= len(modules) <= 256 and
            isinstance(tasks, dict) and 1 <= len(tasks) <= 50000,
            "invalid-graph", "The Gradle graph exceeds its supported bounds.")
    require(isinstance(registry["modules"], dict) and set(modules) == set(registry["modules"]),
            "unregistered-module", "Every included module must register verification roots; removed modules must remove their rule.")
    for module, metadata in modules.items():
        require(bool(re.fullmatch(r":[a-z0-9-]+", module)), "invalid-graph", "Invalid project identity.")
        keys(metadata, {"directory", "dependencies"}, "invalid-graph")
        safe_path(metadata["directory"])
        strings(metadata["dependencies"], 256, "invalid-graph")
        require(set(metadata["dependencies"]) <= set(modules), "invalid-graph", "A project dependency is unresolved.")
        strings(registry["modules"][module], 256, "invalid-registry")
        require(registry["modules"][module] and all(task in tasks for task in registry["modules"][module]),
                "missing-task", "A module verification root is absent from the actual task graph.")
    for task, dependencies in tasks.items():
        require(isinstance(task, str) and bool(re.fullmatch(r"(?::[A-Za-z0-9_-]+)+", task)),
                "invalid-graph", "Invalid task identity.")
        strings(dependencies, 50000, "invalid-graph")
        require(set(dependencies) <= set(tasks), "invalid-graph", "A scheduled task dependency is unresolved.")
    require(isinstance(registry["rfcs"], dict) and len(registry["rfcs"]) <= 256 and
            isinstance(registry["families"], dict), "invalid-registry", "Invalid evidence producer registry.")
    for rfc, producers in registry["rfcs"].items():
        require(bool(re.fullmatch(r"[0-9]{4}", rfc)), "invalid-registry", "Invalid RFC identity.")
        keys(producers, {"modules", "families"}, "invalid-registry")
        for name in ("modules", "families"):
            strings(producers[name], 256, "invalid-registry")
            require(producers[name], "invalid-registry", "An RFC producer rule cannot be empty.")
        require(set(producers["modules"]) <= set(modules) and
                set(producers["families"]) <= set(registry["families"]),
                "invalid-registry", "An RFC evidence producer is unregistered.")
    for family, task in registry["families"].items():
        require(bool(re.fullmatch(r"[a-z][a-z-]+", family)) and task in tasks,
                "missing-task", "An artifact family must identify an actual producing task.")


def merge_graphs(graphs):
    require(len(graphs) == 3, "invalid-graph", "All three platform graphs are required.")
    merged = {"schemaVersion": 1, "modules": {}, "tasks": {}}
    for graph in graphs:
        keys(graph, {"schemaVersion", "modules", "tasks"}, "invalid-graph")
        require(type(graph["schemaVersion"]) is int and graph["schemaVersion"] == 1, "invalid-graph", "Unsupported graph version.")
        if merged["modules"]:
            require(set(merged["modules"]) == set(graph["modules"]), "graph-drift", "Platform project sets differ.")
        for module, metadata in graph["modules"].items():
            previous = merged["modules"].setdefault(module, {"directory": metadata["directory"], "dependencies": []})
            require(previous["directory"] == metadata["directory"], "graph-drift", "Platform project directories differ.")
            previous["dependencies"] = sorted(set(previous["dependencies"]) | set(metadata["dependencies"]))
        for task, dependencies in graph["tasks"].items():
            merged["tasks"][task] = sorted(set(merged["tasks"].get(task, [])) | set(dependencies))
    return merged


def closure(tasks, roots):
    selected = set()
    pending = deque(roots)
    while pending:
        task = pending.popleft()
        require(task in tasks, "missing-task", "A required verification task is absent.")
        if task not in selected:
            selected.add(task)
            pending.extend(tasks[task])
    return selected


def reverse_consumers(graph, selected):
    reverse = {module: set() for module in graph["modules"]}
    for module, metadata in graph["modules"].items():
        for dependency in metadata["dependencies"]:
            reverse[dependency].add(module)
    for task, dependencies in graph["tasks"].items():
        consumer = task.rsplit(":", 1)[0]
        for dependency in dependencies:
            producer = dependency.rsplit(":", 1)[0]
            if consumer in reverse and producer in reverse and consumer != producer:
                reverse[producer].add(consumer)
    affected = set(selected)
    pending = deque(sorted(selected))
    while pending:
        for consumer in sorted(reverse[pending.popleft()]):
            if consumer not in affected:
                affected.add(consumer)
                pending.append(consumer)
    return affected


def tracked_digest(root, paths, tracked):
    members = set()
    for configured in paths:
        safe_path(configured)
        path = root / configured
        require(path.exists() and not path.is_symlink(), "contract-input", "A bound contract path is missing or redirected.")
        matched = {name for name in tracked if name == configured or name.startswith(configured + "/")}
        require(matched, "contract-input", "A bound contract path has no tracked files.")
        members.update(matched)
    digest = hashlib.sha256()
    for name in sorted(members):
        path = root / name
        require(path.is_file() and not any((root / parent).is_symlink() for parent in PurePosixPath(name).parents)
                and not path.is_symlink(), "contract-input", "A tracked contract file is missing or redirected.")
        digest.update((name + "\0" + hashlib.sha256(path.read_bytes()).hexdigest() + "\n").encode())
    return digest.hexdigest()


def evidence_refresh(root, registry, full):
    manifest = load(root / "docs/rfcs/evidence/manifest.json")
    bindings = load(root / "docs/rfcs/evidence/contracts.json")
    runtime = load(root / "runtime/cef-runtime.json")
    require(manifest.get("schemaVersion") == 2 and isinstance(manifest.get("records"), list),
            "evidence-input", "Expected a version-2 evidence manifest.")
    tracked = set(filter(None, git(root, "ls-files", "-z").decode().split("\0")))
    bound = {entry["rfcId"]: entry["paths"] for entry in bindings["bindings"]}
    implemented = {}
    for path in sorted((root / "docs/rfcs").glob("[0-9][0-9][0-9][0-9]-*.md")):
        text = path.read_text()
        if re.search(r"^- Status: Implemented$", text, re.MULTILINE):
            targets = re.search(r"^- Platform targets: (.+)$", text, re.MULTILINE)
            implemented[path.name[:4]] = targets.group(1).split(", ") if targets else list(TARGETS)
    require(set(implemented) <= set(registry["rfcs"]) and set(implemented) <= set(bound),
            "unregistered-rfc", "An Implemented RFC must register its contract and complete raw producers.")
    refresh = set()
    for rfc, targets in implemented.items():
        require(set(targets) <= set(TARGETS) and targets, "evidence-input", "Invalid RFC platform targets.")
        digest = tracked_digest(root, bound[rfc], tracked)
        for target in targets:
            records = [record for record in manifest["records"] if record["rfcId"] == rfc and record["target"] == target]
            if full or not records or any(record["contractSha256"] != digest or
                                         record["cefVersion"] != runtime["cefVersion"] or
                                         record["chromiumVersion"] != runtime["chromiumVersion"] for record in records):
                refresh.add(rfc)
    return refresh


def feature_set(tasks):
    cef = any(task.startswith(":kweb-cef-native:") for task in tasks)
    return {
        "cef": cef,
        "native": cef or any(task.endswith(":buildNative") or task.endswith(":nativeTest") for task in tasks),
        "node": any("Typescript" in task or "Javascript" in task or "BridgeTypescript" in task for task in tasks),
        "notifications": ":kweb-electron-migration:electronMigrationIntegrationTest" in tasks or
                         ":kweb-service-notifications:nativeTest" in tasks,
        "shell": ":kweb-electron-migration:electronMigrationIntegrationTest" in tasks or
                 any(task.startswith(":kweb-service-shell:") and "IntegrationTest" in task for task in tasks),
        "screen": ":kweb-service-screen:nativeScreenIntegrationTest" in tasks,
        "msix": ":kweb-runtime-pack:applicationPackageIntegrationTest" in tasks,
        "benchmark": ":kweb-example-app-benchmark:applicationBenchmarkIntegrationTest" in tasks,
        "liveHtml5": ":kweb-example-html5-lab:html5TestSiteRun" in tasks,
    }


def import_paths(root, changed, refresh):
    if refresh or changed["full"]:
        return changed["paths"]
    commits = git(root, "rev-list", "--first-parent", changed["base"] + ".." + changed["sourceRevision"]).decode().splitlines()
    require(len(commits) <= 4096, "git-input", "The PR history exceeds the supported planning bound.")
    if not commits:
        return changed["paths"]
    def documentation(path):
        return path.endswith(".md") or (path.startswith("docs/rfcs/evidence/") and path != "docs/rfcs/evidence/contracts.json")
    latest = git(root, "diff-tree", "--no-commit-id", "--name-only", "-r", "--no-renames", "-z", commits[0]).decode().split("\0")
    if not all(documentation(path) for path in latest if path):
        return changed["paths"]
    for commit in commits[1:]:
        paths = list(filter(None, git(root, "diff-tree", "--no-commit-id", "--name-only", "-r", "--no-renames", "-z", commit).decode().split("\0")))
        if any(not documentation(path) for path in paths):
            remaining = list(filter(None, git(root, "diff", "--name-only", "--no-renames", "-z", commit, changed["sourceRevision"]).decode().split("\0")))
            if not all(documentation(path) for path in remaining) or not any(path.startswith("docs/rfcs/evidence/") for path in remaining):
                return changed["paths"]
            bindings = load(root / "docs/rfcs/evidence/contracts.json")["bindings"]
            registered = load(root / ".github/ci/suites.json")["rfcs"]
            bound = [path for entry in bindings if entry["rfcId"] in registered for path in entry["paths"]]
            implementation = [path for path in changed["paths"] if not documentation(path)]
            if all(any(path == binding or path.startswith(binding + "/") for binding in bound) for path in implementation):
                return sorted(remaining)
            return changed["paths"]
    return changed["paths"]


def new_module_registration(root, changed, graph, registry):
    if root is None or not {"settings.gradle.kts", ".github/ci/suites.json"} <= set(changed["paths"]):
        return set()
    previous_settings = git(root, "show", changed["base"] + ":settings.gradle.kts").decode()
    settings = (root / "settings.gradle.kts").read_text()
    pattern = r'^include\("(:[a-z0-9-]+)"\)\s*$'
    previous = set(re.findall(pattern, previous_settings, re.MULTILINE))
    current = set(re.findall(pattern, settings, re.MULTILINE))
    added = current - previous
    if not added or not previous <= current or not added <= set(graph["modules"]):
        return set()
    without_includes = lambda text: re.sub(pattern, "", text, flags=re.MULTILINE).strip()
    if without_includes(settings) != without_includes(previous_settings):
        return set()
    before = json.loads(git(root, "show", changed["base"] + ":.github/ci/suites.json"))
    if before["schemaVersion"] != registry["schemaVersion"]:
        return set()
    for field in ("modules", "rfcs", "families"):
        if not all(registry[field].get(name) == rule for name, rule in before[field].items()):
            return set()
    if set(registry["modules"]) - set(before["modules"]) != added:
        return set()
    return added


def plan(root, graph, registry, changed, full=False, refresh=None):
    validate_graph(graph, registry)
    keys(changed, {"schemaVersion", "revision", "sourceRevision", "base", "paths", "full"}, "invalid-input")
    require(type(changed["schemaVersion"]) is int and changed["schemaVersion"] == 1 and
            type(changed["full"]) is bool, "invalid-input", "Invalid change document version or mode.")
    for field in ("revision", "sourceRevision", "base"):
        require(bool(re.fullmatch(r"[0-9a-f]{40}", changed[field])), "invalid-input", "Change revisions must be full commit IDs.")
    strings(changed["paths"], 20000, "invalid-input")
    affected, reasons = set(), []
    full = full or changed["full"]
    refresh = evidence_refresh(root, registry, full or changed["full"]) if refresh is None else set(refresh)
    docs_only = all(path.endswith(".md") or (path.startswith("docs/rfcs/evidence/") and path != "docs/rfcs/evidence/contracts.json") or
                    path.startswith("docs/rfcs/0005-migrate-cli-evidence/") for path in changed["paths"])
    if docs_only and not full and not changed["full"]:
        if refresh:
            reasons.append("stale-document-contract:strict-governance-required")
        refresh = set()
    chosen_paths = import_paths(root, changed, refresh) if root is not None and not full else changed["paths"]
    new_modules = new_module_registration(root, changed, graph, registry)
    if chosen_paths != changed["paths"]:
        reasons.append("current-evidence-import:bound-implementation-already-verified")
    for path in changed["paths"]:
        safe_path(path)
        if root is not None:
            require(not (root / path).is_symlink() and
                    not any((root / parent).is_symlink() for parent in PurePosixPath(path).parents),
                    "unsafe-path", "Changed paths must not traverse a redirected input.")
    for path in chosen_paths:
        module = next((name for name, metadata in graph["modules"].items()
                       if path == metadata["directory"] or path.startswith(metadata["directory"] + "/")), None)
        if module:
            if not path.endswith(".md"):
                affected.add(module)
                reasons.append("module:" + module + ":" + path)
        elif path == "docs/rfcs/evidence/contracts.json":
            affected.add(":kweb-rfc-governance")
            reasons.append("contract-policy:" + path)
        elif path.endswith(".md") or path.startswith("docs/rfcs/evidence/") or path.startswith("docs/rfcs/0005-migrate-cli-evidence/"):
            reasons.append("documentation:" + path)
        elif new_modules and path in ("settings.gradle.kts", ".github/ci/suites.json"):
            affected.update(new_modules)
            reasons.append("new-module-registration:" + path)
        elif path in GLOBAL_FILES or path.startswith(GLOBAL_PREFIXES):
            full = True
            reasons.append("global:" + path)
        else:
            raise PlanError("unclassified-path", "An implementation path has no verification policy: " + path)
    affected = set(graph["modules"]) if full else reverse_consumers(graph, affected)
    if full and root is not None:
        refresh = evidence_refresh(root, registry, True)
    require(refresh <= set(registry["rfcs"]), "unregistered-rfc", "A required RFC has no evidence producer rule.")
    for rfc in sorted(refresh):
        affected.update(registry["rfcs"][rfc]["modules"])
        reasons.append("refresh-rfc:" + rfc)
    roots = {task for module in affected for task in registry["modules"][module]}
    if full or ":kweb-example-app-benchmark" in affected:
        roots.add(":kweb-example-app-benchmark:applicationBenchmarkIntegrationTest")
    if full or ":kweb-example-html5-lab" in affected:
        roots.add(":kweb-example-html5-lab:html5TestSiteRun")
    tasks = closure(graph["tasks"], roots)
    families = {family for family, task in registry["families"].items() if task in tasks}
    for rfc in refresh:
        require(set(registry["rfcs"][rfc]["families"]) <= families,
                "missing-producer", "The selected task closure cannot produce a required RFC artifact.")
    return {"schemaVersion": 1, "revision": changed["revision"], "base": changed["base"],
            "changedPaths": sorted(changed["paths"]), "full": full, "modules": sorted(affected),
            "roots": sorted(roots), "tasks": sorted(tasks), "refreshRfcs": sorted(refresh),
            "families": sorted(families), "features": feature_set(tasks), "reasons": sorted(reasons)}


def validate_plan(value):
    keys(value, PLAN_FIELDS, "invalid-plan")
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 1 and
            type(value["full"]) is bool, "invalid-plan", "Invalid plan version or mode.")
    for field in ("revision", "base"):
        require(bool(re.fullmatch(r"[0-9a-f]{40}", value[field])), "invalid-plan", "Invalid plan revision.")
    for field, limit in (("changedPaths", 20000), ("modules", 256), ("roots", 50000),
                         ("tasks", 50000), ("refreshRfcs", 256), ("families", 256), ("reasons", 25000)):
        strings(value[field], limit, "invalid-plan")
    keys(value["features"], FEATURES, "invalid-plan")
    require(all(type(enabled) is bool for enabled in value["features"].values()),
            "invalid-plan", "Setup features must be booleans.")
    require(set(value["roots"]) <= set(value["tasks"]) and value["features"] == feature_set(set(value["tasks"])),
            "invalid-plan", "Plan setup does not match its selected task closure.")
    for path in value["changedPaths"]:
        safe_path(path)
    require(all(re.fullmatch(r":[a-z0-9-]+", module) for module in value["modules"]) and
            all(re.fullmatch(r"(?::[A-Za-z0-9_-]+)+", task) for task in value["tasks"]) and
            all(re.fullmatch(r"[0-9]{4}", rfc) for rfc in value["refreshRfcs"]) and
            all(re.fullmatch(r"[a-z][a-z-]+", family) for family in value["families"]),
            "invalid-plan", "Plan identities must match the registered identifier grammar.")
    return value


def gate(plan_value, results):
    validate_plan(plan_value)
    keys(results, {"plan", "lightweight", "verify", "rfc-evidence", "governance"}, "gate-input")
    expected = {"plan": "success", "lightweight": "success", "governance": "success",
                "verify": "success" if plan_value["roots"] else "skipped",
                "rfc-evidence": "success" if plan_value["refreshRfcs"] else "skipped"}
    require(results == expected, "gate-blocked", "A selected verification job failed, was cancelled or was unexpectedly skipped.")


def main():
    parser = StrictParser()
    subparsers = parser.add_subparsers(dest="command", required=True)
    collector = subparsers.add_parser("changes")
    collector.add_argument("--repository-root", default=".")
    collector.add_argument("--event", required=True)
    collector.add_argument("--event-name", required=True)
    collector.add_argument("--output", required=True)
    documents = subparsers.add_parser("docs")
    documents.add_argument("--changes", required=True)
    documents.add_argument("--output", required=True)
    planner = subparsers.add_parser("plan")
    planner.add_argument("--repository-root", default=".")
    planner.add_argument("--graph", required=True)
    planner.add_argument("--changes", required=True)
    planner.add_argument("--output", required=True)
    planner.add_argument("--full", action="store_true")
    auditor = subparsers.add_parser("audit")
    auditor.add_argument("--plan", required=True)
    auditor.add_argument("--graph", required=True)
    auditor.add_argument("--output", required=True)
    auditor.add_argument("--target", choices=TARGETS, required=True)
    checker = subparsers.add_parser("gate")
    checker.add_argument("--plan", required=True)
    checker.add_argument("--results", required=True)
    selector = subparsers.add_parser("select")
    selector.add_argument("--plan", required=True)
    selector.add_argument("--rfc", required=True)
    validator = subparsers.add_parser("validate")
    validator.add_argument("--plan", required=True)
    merger = subparsers.add_parser("merge-graphs")
    merger.add_argument("--directory", required=True)
    merger.add_argument("--output", required=True)
    probe = subparsers.add_parser("probe")
    probe.add_argument("--graph", required=True)
    probe.add_argument("--output", required=True)
    arguments = parser.parse_args()
    if arguments.command == "probe":
        root = Path(".")
        head = git(root, "rev-parse", "HEAD").decode().strip()
        changed = {"schemaVersion": 1, "revision": head, "sourceRevision": head,
                   "base": head, "paths": ["kweb-service-tray/src/commonMain/selection-probe.kt"], "full": False}
        value = plan(None, load(arguments.graph), load(root / ".github/ci/suites.json"), changed, refresh=[])
        require(set(value["modules"]) == {":kweb-service-tray", ":kweb-rfc-governance"} and
                not value["features"]["cef"] and not value["features"]["node"],
                "probe-failed", "The actual tray consumer graph unexpectedly needs CEF or Node.")
        write(arguments.output, {"scenario": "synthetic-tray-change-with-current-evidence", "plan": value})
    elif arguments.command == "merge-graphs":
        directory = Path(arguments.directory)
        graphs = [load(directory / ("ci-graph-" + target) / "graph.json") for target in TARGETS]
        write(arguments.output, merge_graphs(graphs))
    elif arguments.command == "changes":
        value = changes(Path(arguments.repository_root), load(arguments.event), arguments.event_name)
        write(arguments.output, value)
        if os.environ.get("GITHUB_OUTPUT"):
            with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
                output.write("graphs=" + str(not only_documentation(value)).lower() + "\n")
    elif arguments.command == "docs":
        write(arguments.output, documentation_plan(load(arguments.changes)))
        if os.environ.get("GITHUB_OUTPUT"):
            with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
                output.write("verify=false\nrefresh=false\nfull=false\n")
    elif arguments.command == "plan":
        root = Path(arguments.repository_root).resolve()
        changed = load(arguments.changes)
        require(changed["revision"] == git(root, "rev-parse", "HEAD").decode().strip(),
                "git-input", "Plan revision does not match this checkout.")
        value = plan(root, load(arguments.graph), load(root / ".github/ci/suites.json"), changed, arguments.full)
        write(arguments.output, value)
        if os.environ.get("GITHUB_OUTPUT"):
            with Path(os.environ["GITHUB_OUTPUT"]).open("a") as output:
                for name, enabled in (("verify", bool(value["roots"])), ("refresh", bool(value["refreshRfcs"])),
                                      ("full", value["full"])):
                    output.write(name + "=" + str(enabled).lower() + "\n")
        if os.environ.get("GITHUB_STEP_SUMMARY"):
            with Path(os.environ["GITHUB_STEP_SUMMARY"]).open("a") as summary:
                summary.write("## CI execution plan\n\n```json\n" + json.dumps(value, indent=2) + "\n```\n")
    elif arguments.command == "audit":
        value = validate_plan(load(arguments.plan))
        require(value["revision"] == git(Path("."), "rev-parse", "HEAD").decode().strip(),
                "graph-drift", "The audited checkout does not match the plan revision.")
        graph = load(arguments.graph)
        registry = load(Path(".github/ci/suites.json"))
        validate_graph(graph, registry)
        local = plan(Path(".").resolve(), graph, registry, load("build/ci/changes.json"),
                     value["full"], value["refreshRfcs"])
        require(set(local["roots"]) <= set(value["roots"]), "graph-drift",
                "This platform has an additional affected consumer; update the execution plan.")
        roots = value["roots"]
        if arguments.target != "windows-x64":
            roots = [task for task in roots if task not in (
                ":kweb-example-app-benchmark:applicationBenchmarkIntegrationTest",
                ":kweb-example-html5-lab:html5TestSiteRun")]
        tasks = closure(graph["tasks"], roots)
        write(arguments.output, {"schemaVersion": 1, "revision": value["revision"], "tasks": sorted(tasks),
                                 "features": feature_set(tasks)})
        expected = dict(value["features"])
        if arguments.target != "windows-x64":
            expected.update(benchmark=False, liveHtml5=False)
        require(feature_set(tasks) == expected, "graph-drift",
                "This platform requires setup not declared by the plan; update the contract.")
    elif arguments.command == "gate":
        gate(load(arguments.plan), load(arguments.results))
    elif arguments.command == "validate":
        validate_plan(load(arguments.plan))
    else:
        value = validate_plan(load(arguments.plan))
        return 0 if arguments.rfc in value["refreshRfcs"] else 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (PlanError, KeyError, TypeError, ValueError, OSError) as error:
        code = error.code if isinstance(error, PlanError) else "ci.invalid-input"
        print(json.dumps({"code": code, "message": str(error) if isinstance(error, PlanError) else "Invalid CI input."}), file=sys.stderr)
        sys.exit(2)
