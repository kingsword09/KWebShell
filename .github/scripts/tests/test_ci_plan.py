import copy
import hashlib
import importlib.util
import json
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[3]
SPEC = importlib.util.spec_from_file_location("ci_plan", ROOT / ".github/scripts/ci_plan.py")
CI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CI)
REVISION = "a" * 40


def fixtures():
    modules = {":base": {"directory": "base", "dependencies": []},
               ":leaf": {"directory": "leaf", "dependencies": []},
               ":consumer": {"directory": "consumer", "dependencies": [":base"]}}
    tasks = {module + ":check": [module + ":test"] for module in modules}
    tasks.update({module + ":test": [] for module in modules})
    tasks[":consumer:test"] = [":base:test"]
    tasks[":kweb-example-app-benchmark:applicationBenchmarkIntegrationTest"] = []
    tasks[":kweb-example-html5-lab:html5TestSiteRun"] = []
    graph = {"schemaVersion": 1, "modules": modules, "tasks": tasks}
    registry = {"schemaVersion": 1, "modules": {module: [module + ":check"] for module in modules},
                "rfcs": {"0001": {"modules": [":base"], "families": ["proof"]}},
                "families": {"proof": ":base:test"}}
    changes = {"schemaVersion": 1, "revision": REVISION, "sourceRevision": REVISION,
               "base": "b" * 40, "paths": [], "full": False}
    return graph, registry, changes


class CiPlanTest(unittest.TestCase):
    def setUp(self):
        self.graph, self.registry, self.changes = fixtures()

    def plan(self, paths, refresh=(), full=False):
        self.changes["paths"] = paths
        return CI.plan(None, self.graph, self.registry, self.changes, full, refresh)

    def assert_code(self, code, operation):
        with self.assertRaises(CI.PlanError) as failure:
            operation()
        self.assertEqual("ci." + code, failure.exception.code)

    def test_independent_module_selects_no_unrelated_consumer(self):
        value = self.plan(["leaf/src/file.kt"])
        self.assertEqual([":leaf"], value["modules"])
        self.assertEqual([":leaf:check", ":leaf:test"], value["tasks"])
        self.assertFalse(any(value["features"].values()))

    def test_reverse_dependencies_include_project_and_cross_project_task_edges(self):
        self.assertEqual([":base", ":consumer"], self.plan(["base/src/file.kt"])["modules"])
        self.graph["modules"][":consumer"]["dependencies"] = []
        self.assertEqual([":base", ":consumer"], self.plan(["base/src/file.kt"])["modules"])

    def test_dependency_cycle_terminates_deterministically(self):
        self.graph["modules"][":base"]["dependencies"] = [":consumer"]
        first = self.plan(["base/src/file.kt"])
        self.assertEqual(first, self.plan(["base/src/file.kt"]))
        self.assertEqual([":base", ":consumer"], first["modules"])

    def test_global_inputs_select_full_but_unknown_inputs_fail(self):
        for path in ("gradle/libs.versions.toml", ".github/workflows/ci.yml", "settings.gradle.kts"):
            value = self.plan([path])
            self.assertTrue(value["full"])
            self.assertEqual(sorted(self.graph["modules"]), value["modules"])
        self.assert_code("unclassified-path", lambda: self.plan(["unowned/file.kt"]))

    def test_documentation_and_evidence_do_not_select_native_roots(self):
        value = self.plan(["README.md", "docs/rfcs/evidence/manifest.json"])
        self.assertEqual([], value["roots"])
        self.assertEqual([], value["refreshRfcs"])
        documents = CI.documentation_plan({**self.changes, "paths": ["README.md"]})
        self.assertEqual([], documents["roots"])
        self.assertFalse(CI.only_documentation({**self.changes, "paths": ["docs/rfcs/evidence/contracts.json"]}))
        with self.assertRaises(CI.PlanError):
            CI.documentation_plan({**self.changes, "paths": ["source.kt"]})

    def test_stale_evidence_selects_producers_but_markdown_cannot_run_native_matrix(self):
        documentation = self.plan(["README.md"], ["0001"])
        self.assertEqual([], documentation["roots"])
        self.assertIn("stale-document-contract:strict-governance-required", documentation["reasons"])
        value = self.plan(["base/value.kt"], ["0001"])
        self.assertEqual([":base", ":consumer"], value["modules"])
        self.assertEqual(["proof"], value["families"])
        self.assertEqual(["0001"], value["refreshRfcs"])

    def test_missing_new_module_or_verification_task_blocks(self):
        self.graph["modules"][":new"] = {"directory": "new", "dependencies": []}
        self.assert_code("unregistered-module", lambda: self.plan(["new/src/file.kt"]))
        self.registry["modules"][":new"] = [":new:check"]
        self.assert_code("missing-task", lambda: self.plan(["new/src/file.kt"]))
        self.graph["tasks"][":new:check"] = []
        self.assertEqual([":new"], self.plan(["new/src/file.kt"])["modules"])

    def test_missing_family_producer_blocks_refresh(self):
        self.registry["rfcs"]["0001"]["families"] = ["missing"]
        self.assert_code("invalid-registry", lambda: self.plan(["leaf/file.kt"], ["0001"]))
        self.registry["rfcs"]["0001"]["families"] = ["proof"]
        self.registry["rfcs"]["0001"]["modules"] = [":leaf"]
        self.assert_code("missing-producer", lambda: self.plan(["leaf/file.kt"], ["0001"]))

    def test_strict_graph_types_fields_dependencies_and_limits(self):
        for mutate in (
            lambda graph: graph.update(extra=True),
            lambda graph: graph.update(schemaVersion=True),
            lambda graph: graph["modules"][":leaf"].update(dependencies=[":unknown"]),
            lambda graph: graph["tasks"].update({":leaf:test": [":missing:test"]}),
            lambda graph: graph["modules"][":leaf"].update(directory="../outside"),
        ):
            graph = copy.deepcopy(self.graph)
            mutate(graph)
            with self.assertRaises(CI.PlanError):
                CI.validate_graph(graph, self.registry)
        self.changes["paths"] = ["leaf/file" + str(index) for index in range(20001)]
        self.assert_code("invalid-input", lambda: CI.plan(None, self.graph, self.registry, self.changes, refresh=[]))

    def test_paths_and_plan_mutations_are_rejected(self):
        for path in ("../private", "/absolute", "leaf//file", "C:\\private", "leaf/file\nsecret"):
            self.assert_code("unsafe-path", lambda: self.plan([path]))
        value = self.plan(["leaf/file.kt"])
        for mutate in (lambda plan: plan.update(extra=True), lambda plan: plan.update(schemaVersion=True),
                       lambda plan: plan["features"].update(cef=True), lambda plan: plan["roots"].append(":absent:test")):
            invalid = copy.deepcopy(value)
            mutate(invalid)
            self.assert_code("invalid-plan", lambda: CI.validate_plan(invalid))

    def test_gate_rejects_failure_cancellation_and_unexpected_skip(self):
        value = self.plan(["leaf/file.kt"])
        results = {"plan": "success", "lightweight": "success", "verify": "success",
                   "rfc-evidence": "skipped", "governance": "success"}
        CI.gate(value, results)
        for name in ("plan", "lightweight", "verify", "governance"):
            for outcome in ("failure", "cancelled", "skipped"):
                self.assert_code("gate-blocked", lambda: CI.gate(value, {**results, name: outcome}))
        empty = self.plan(["README.md"])
        CI.gate(empty, {**results, "verify": "skipped"})
        self.assert_code("gate-blocked", lambda: CI.gate(empty, results))
        refresh = self.plan(["base/value.kt"], ["0001"])
        self.assert_code("gate-blocked", lambda: CI.gate(refresh, results))
        CI.gate(refresh, {**results, "rfc-evidence": "success"})

    def test_duplicate_json_fields_and_cli_failure_are_structured(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "input.json"
            path.write_text('{"schemaVersion":1,"schemaVersion":1}')
            self.assert_code("invalid-input", lambda: CI.load(path))
            result = subprocess.run([sys.executable, str(ROOT / ".github/scripts/ci_plan.py"),
                                     "validate", "--plan", str(path)], capture_output=True, text=True)
            self.assertEqual(2, result.returncode)
            self.assertEqual("ci.invalid-input", json.loads(result.stderr)["code"])
            invalid = subprocess.run([sys.executable, str(ROOT / ".github/scripts/ci_plan.py"), "validate"],
                                     capture_output=True, text=True)
            self.assertEqual(2, invalid.returncode)
            self.assertEqual("ci.invalid-options", json.loads(invalid.stderr)["code"])

    def test_platform_graph_union_includes_platform_specific_consumers(self):
        windows = copy.deepcopy(self.graph)
        windows["modules"][":leaf"]["dependencies"] = [":base"]
        merged = CI.merge_graphs([self.graph, windows, self.graph])
        value = CI.plan(None, merged, self.registry, {**self.changes, "paths": ["base/file.kt"]}, refresh=[])
        self.assertEqual([":base", ":consumer", ":leaf"], value["modules"])
        with self.assertRaises(CI.PlanError):
            CI.merge_graphs([self.graph, windows])
        windows["modules"].pop(":leaf")
        with self.assertRaises(CI.PlanError):
            CI.merge_graphs([self.graph, windows, self.graph])


class CiGitInputTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="kweb-ci-git-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.git("init", "-q")
        self.git("config", "user.name", "CI Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.commit("base")
        self.base = self.git("rev-parse", "HEAD")

    def git(self, *arguments):
        return subprocess.run(["git", "-C", str(self.root), *arguments], check=True,
                              capture_output=True, text=True).stdout.strip()

    def commit(self, content, name="source.txt"):
        (self.root / name).write_text(content)
        self.git("add", ".")
        self.git("commit", "-qm", content)
        return self.git("rev-parse", "HEAD")

    def test_push_and_initial_push(self):
        head = self.commit("changed")
        value = CI.changes(self.root, {"before": self.base, "after": head}, "push")
        self.assertEqual(["source.txt"], value["paths"])
        self.assertFalse(value["full"])
        self.assertTrue(CI.changes(self.root, {"before": "0" * 40, "after": head}, "push")["full"])
        with self.assertRaises(CI.PlanError):
            CI.changes(self.root, {"before": self.base, "after": self.base}, "push")

    def test_pr_merge_base_excludes_other_branch_changes_and_covers_rename(self):
        self.git("checkout", "-qb", "topic")
        self.git("mv", "source.txt", "renamed file.txt")
        self.git("commit", "-qm", "rename")
        source = self.git("rev-parse", "HEAD")
        self.git("checkout", "-qb", "other", self.base)
        base = self.commit("unrelated", "unrelated.txt")
        value = CI.changes(self.root, {"pull_request": {"head": {"sha": source}, "base": {"sha": base}}}, "pull_request")
        self.assertEqual(["renamed file.txt", "source.txt"], value["paths"])
        self.assertEqual(self.base, value["base"])

    def test_manual_schedule_and_invalid_base(self):
        self.assertTrue(CI.changes(self.root, {}, "schedule")["full"])
        self.assertTrue(CI.changes(self.root, {}, "workflow_dispatch")["full"])
        for event_name, event in (("unknown", {}), ("push", {"before": "d" * 40, "after": self.base})):
            with self.assertRaises(CI.PlanError):
                CI.changes(self.root, event, event_name)

    def test_contract_digest_matches_tracked_bytes_and_excludes_output(self):
        tracked = {"source.txt"}
        digest = CI.tracked_digest(self.root, ["source.txt"], tracked)
        expected = hashlib.sha256(("source.txt\0" + hashlib.sha256(b"base").hexdigest() + "\n").encode()).hexdigest()
        self.assertEqual(expected, digest)
        (self.root / "untracked.txt").write_text("output")
        self.assertEqual(digest, CI.tracked_digest(self.root, ["source.txt"], tracked))
        (self.root / "source.txt").write_text("different")
        self.assertNotEqual(digest, CI.tracked_digest(self.root, ["source.txt"], tracked))
        (self.root / "source.txt").unlink()
        with self.assertRaises(CI.PlanError):
            CI.tracked_digest(self.root, ["source.txt"], tracked)

    def test_evidence_import_tail_does_not_hide_unbound_or_later_code(self):
        policy = self.root / ".github/ci/suites.json"
        policy.parent.mkdir(parents=True)
        policy.write_text(json.dumps({"rfcs": {"0001": {}}}))
        bindings = self.root / "docs/rfcs/evidence/contracts.json"
        bindings.parent.mkdir(parents=True)
        bindings.write_text(json.dumps({"bindings": [{"rfcId": "0001", "paths": ["source.txt"]}]}))
        self.commit("implementation")
        imported = bindings.with_name("manifest.json")
        imported.write_text("{}")
        head = self.commit("review", "review.md")
        changed = {"schemaVersion": 1, "base": self.base, "sourceRevision": head,
                   "revision": head, "paths": ["source.txt", "docs/rfcs/evidence/manifest.json"], "full": False}
        self.assertEqual(["docs/rfcs/evidence/manifest.json", "review.md"], CI.import_paths(self.root, changed, []))
        self.assertEqual(changed["paths"], CI.import_paths(self.root, changed, ["0001"]))
        changed["paths"].append("unbound.kt")
        self.assertEqual(changed["paths"], CI.import_paths(self.root, changed, []))
        changed["paths"].remove("unbound.kt")
        changed["sourceRevision"] = self.commit("later edit")
        self.assertEqual(changed["paths"], CI.import_paths(self.root, changed, []))

    def test_new_module_addition_preserves_existing_registry_and_settings(self):
        graph, registry, changed = fixtures()
        previous = copy.deepcopy(registry)
        previous["modules"].pop(":leaf")
        directory = self.root / ".github/ci"
        directory.mkdir(parents=True)
        (directory / "suites.json").write_text(json.dumps(previous))
        settings = self.root / "settings.gradle.kts"
        settings.write_text('rootProject.name = "fixture"\ninclude(":base")\ninclude(":consumer")\n')
        base = self.commit("registration base", "registration.md")
        settings.write_text(settings.read_text() + 'include(":leaf")\n')
        (directory / "suites.json").write_text(json.dumps(registry))
        changed.update(base=base, paths=["settings.gradle.kts", ".github/ci/suites.json", "leaf/file.kt"])
        self.assertEqual({":leaf"}, CI.new_module_registration(self.root, changed, graph, registry))
        head = self.git("rev-parse", "HEAD")
        changed.update(revision=head, sourceRevision=head)
        value = CI.plan(self.root, graph, registry, changed, refresh=[])
        self.assertFalse(value["full"])
        self.assertEqual([":leaf"], value["modules"])
        settings.write_text(settings.read_text() + 'rootProject.version = "different"\n')
        self.assertEqual(set(), CI.new_module_registration(self.root, changed, graph, registry))
        settings.write_text('rootProject.name = "fixture"\ninclude(":base")\ninclude(":consumer")\ninclude(":leaf")\n')
        registry["modules"][":base"] = [":base:otherTest"]
        self.assertEqual(set(), CI.new_module_registration(self.root, changed, graph, registry))

    def test_evidence_refresh_matches_targets_runtime_and_tracked_digest(self):
        catalog = self.root / "docs/rfcs"
        catalog.mkdir(parents=True)
        (catalog / "0001-proof.md").write_text("- Status: Implemented\n")
        evidence = catalog / "evidence"
        evidence.mkdir()
        (evidence / "contracts.json").write_text(json.dumps({"bindings": [{"rfcId": "0001", "paths": ["source.txt"]}]}))
        runtime_dir = self.root / "runtime"
        runtime_dir.mkdir()
        runtime = {"cefVersion": "fixture-cef", "chromiumVersion": "fixture-chromium"}
        (runtime_dir / "cef-runtime.json").write_text(json.dumps(runtime))
        digest = CI.tracked_digest(self.root, ["source.txt"], {"source.txt"})
        records = [{"rfcId": "0001", "target": target, "contractSha256": digest, **runtime} for target in CI.TARGETS]
        manifest = evidence / "manifest.json"
        manifest.write_text(json.dumps({"schemaVersion": 2, "records": records}))
        registry = {"rfcs": {"0001": {}}}
        self.assertEqual(set(), CI.evidence_refresh(self.root, registry, False))
        original_read = Path.read_text
        def utf8_read(path, *arguments, **options):
            self.assertEqual("utf-8", options.get("encoding"))
            return original_read(path, *arguments, **options)
        with patch.object(Path, "read_text", utf8_read):
            self.assertEqual(set(), CI.evidence_refresh(self.root, registry, False))
        self.assertEqual({"0001"}, CI.evidence_refresh(self.root, registry, True))
        manifest.write_text(json.dumps({"schemaVersion": 2, "records": records[:-1]}))
        self.assertEqual({"0001"}, CI.evidence_refresh(self.root, registry, False))
        manifest.write_text(json.dumps({"schemaVersion": 2, "records": records}))
        (self.root / "source.txt").write_text("changed")
        self.assertEqual({"0001"}, CI.evidence_refresh(self.root, registry, False))
        (self.root / "source.txt").write_text("base")
        (runtime_dir / "cef-runtime.json").write_text(json.dumps({**runtime, "cefVersion": "new"}))
        self.assertEqual({"0001"}, CI.evidence_refresh(self.root, registry, False))
        with self.assertRaises(CI.PlanError):
            CI.evidence_refresh(self.root, {"rfcs": {}}, False)


class CiWorkflowTest(unittest.TestCase):
    def test_registered_root_tasks_include_new_modules_and_keep_full_suite(self):
        registry = CI.load(ROOT / ".github/ci/suites.json")
        settings = (ROOT / "settings.gradle.kts").read_text()
        modules = set(re.findall(r'include\("(:[a-z0-9-]+)"\)', settings))
        self.assertEqual(modules, set(registry["modules"]))
        self.assertTrue(all(roots for roots in registry["modules"].values()))
        exporter = (ROOT / ".github/scripts/export-ci-graph.sh").read_text()
        self.assertIn(".modules[][]", exporter)
        self.assertIn("runtimeCheck", exporter)
        self.assertIn("--dry-run", exporter)

    def test_workflow_keeps_fail_closed_jobs_conditional_setup_and_fresh_tests(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text()
        self.assertNotIn("paths-ignore:", workflow)
        self.assertIn("name: CI Gate", workflow)
        self.assertIn("cancel-in-progress: ${{ github.event_name == 'pull_request' }}", workflow)
        self.assertIn("!cancelled() && needs.plan.result == 'success'", workflow)
        self.assertIn("    if: always()\n", workflow)
        self.assertIn("schedule:", workflow)
        self.assertIn("steps.scope.outputs.cef == 'true'", workflow)
        self.assertIn(".families[]", workflow)
        self.assertIn(".roots[]", workflow)
        self.assertIn(".github/ci/fresh-verification.gradle", workflow)
        self.assertIn("needs: [plan, lightweight, verify]", workflow)
        self.assertNotIn("--rerun-tasks", workflow)
        self.assertIn("task.outputs.doNotCacheIf", (ROOT / ".github/ci/fresh-verification.gradle").read_text())


if __name__ == "__main__":
    unittest.main()
