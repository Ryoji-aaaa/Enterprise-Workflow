import json
import shutil
import tempfile
import unittest
from pathlib import Path
from unittest import mock

import aggregate
from aggregate import ResultError, aggregate_run, write_outputs


FIXTURES = Path(__file__).parent / "fixtures"


class AggregateTest(unittest.TestCase):
    def fixture(self, name: str) -> Path:
        holder = tempfile.TemporaryDirectory()
        self.addCleanup(holder.cleanup)
        target = Path(holder.name) / "run"
        shutil.copytree(FIXTURES / name, target)
        return target

    def add_phase(self, run_dir: Path, name: str, content: dict) -> None:
        phases = run_dir / "phases"
        phases.mkdir(exist_ok=True)
        (phases / name).write_text(json.dumps(content), encoding="utf-8")

    def test_all_pass_and_checks_are_not_tests(self):
        summary, _ = aggregate_run(self.fixture("all-pass"))
        self.assertEqual("PASS", summary["overall"])
        self.assertEqual(4, sum(item["discovered"] for item in summary["suites"].values()))
        self.assertEqual(2, summary["checks"]["passed"])

    def test_failure_is_reported(self):
        summary, _ = aggregate_run(self.fixture("test-failure"))
        self.assertEqual("FAIL", summary["overall"])
        self.assertEqual(1, summary["suites"]["backend"]["failed"])
        self.assertEqual("broken case", summary["failures"][0]["name"])

    def test_setup_error_without_tests_is_error(self):
        summary, _ = aggregate_run(self.fixture("setup-error"))
        self.assertEqual("ERROR", summary["overall"])
        self.assertEqual("ERROR", summary["suites"]["e2e"]["status"])

    def test_malformed_junit_is_error(self):
        summary, _ = aggregate_run(self.fixture("malformed-junit"))
        self.assertEqual("ERROR", summary["overall"])
        self.assertIn("Malformed JUnit", summary["failures"][0]["message"])

    def test_skipped_test_is_discovered_but_not_executed(self):
        summary, _ = aggregate_run(self.fixture("skipped-test"))
        suite = summary["suites"]["frontend"]
        self.assertEqual(2, suite["discovered"])
        self.assertEqual(1, suite["executed"])
        self.assertEqual(1, suite["skipped"])

    def test_nested_testsuites_do_not_duplicate_counts(self):
        summary, _ = aggregate_run(self.fixture("nested-suites"))
        self.assertEqual(2, summary["suites"]["backend"]["discovered"])

    def test_multiple_surefire_files_are_combined(self):
        summary, _ = aggregate_run(self.fixture("multiple-surefire"))
        self.assertEqual(2, summary["suites"]["backend"]["passed"])

    def test_keycloak_ndjson_is_combined_with_junit(self):
        summary, _ = aggregate_run(self.fixture("all-pass"))
        self.assertEqual(1, summary["suites"]["keycloak"]["passed"])
        self.assertEqual(4, len(summary["selected_suites"]))

    def test_missing_results_are_error(self):
        summary, _ = aggregate_run(self.fixture("missing-junit"))
        self.assertEqual("ERROR", summary["overall"])

    def test_zero_tests_are_error(self):
        summary, _ = aggregate_run(self.fixture("zero-tests"))
        self.assertEqual("ERROR", summary["overall"])

    def test_outputs_include_all_tests_ok_inputs(self):
        run_dir = self.fixture("all-pass")
        summary = write_outputs(run_dir)
        self.assertEqual("PASS", summary["overall"])
        self.assertTrue((run_dir / "summary.json").is_file())
        self.assertTrue((run_dir / "summary.md").is_file())
        self.assertTrue((run_dir / "merged-junit.xml").is_file())

    def test_failed_runner_without_failed_case_is_error(self):
        run_dir = self.fixture("all-pass")
        phases = run_dir / "phases"
        phases.mkdir()
        (phases / "001-backend-junit.json").write_text(
            '{"suite":"backend","kind":"test","name":"junit","status":"failed","reason":"runner failed"}',
            encoding="utf-8",
        )
        summary, _ = aggregate_run(run_dir)
        self.assertEqual("ERROR", summary["overall"])
        self.assertEqual("ERROR", summary["suites"]["backend"]["status"])

    def test_successful_phase_without_its_result_file_is_error(self):
        run_dir = self.fixture("all-pass")
        phases = run_dir / "phases"
        phases.mkdir()
        (phases / "001-backend-junit.json").write_text(
            '{"suite":"backend","kind":"test","name":"junit","status":"passed"}',
            encoding="utf-8",
        )
        summary, _ = aggregate_run(run_dir)
        self.assertEqual("ERROR", summary["overall"])
        self.assertIn("expected result file", summary["failures"][0]["message"])

    def test_backend_junit_and_postgresql_it_are_combined(self):
        run_dir = self.fixture("multiple-surefire")
        postgresql = run_dir / "raw/junit/backend/postgresql"
        postgresql.mkdir(parents=True)
        (postgresql / "TEST-postgresql.xml").write_text(
            '<?xml version="1.0"?><testsuite><testcase name="postgres one"/><testcase name="postgres two"/></testsuite>',
            encoding="utf-8",
        )
        self.add_phase(run_dir, "001-backend-postgresql-it.json", {
            "suite": "backend", "kind": "test", "name": "postgresql-it", "status": "passed"
        })
        summary, _ = aggregate_run(run_dir)
        self.assertEqual(4, summary["suites"]["backend"]["passed"])

    def test_postgresql_it_xml_missing_is_error(self):
        run_dir = self.fixture("multiple-surefire")
        self.add_phase(run_dir, "001-backend-postgresql-it.json", {
            "suite": "backend", "kind": "test", "name": "postgresql-it", "status": "passed"
        })
        summary, _ = aggregate_run(run_dir)
        self.assertEqual("ERROR", summary["suites"]["backend"]["status"])

    def test_postgresql_it_malformed_xml_is_error(self):
        run_dir = self.fixture("multiple-surefire")
        postgresql = run_dir / "raw/junit/backend/postgresql"
        postgresql.mkdir(parents=True)
        (postgresql / "TEST-postgresql.xml").write_text("<testsuite>", encoding="utf-8")
        self.add_phase(run_dir, "001-backend-postgresql-it.json", {
            "suite": "backend", "kind": "test", "name": "postgresql-it", "status": "failed"
        })
        summary, _ = aggregate_run(run_dir)
        self.assertEqual("ERROR", summary["suites"]["backend"]["status"])

    def test_postgresql_it_assertion_failure_is_fail(self):
        run_dir = self.fixture("multiple-surefire")
        postgresql = run_dir / "raw/junit/backend/postgresql"
        postgresql.mkdir(parents=True)
        (postgresql / "TEST-postgresql.xml").write_text(
            '<testsuite><testcase name="query"><failure message="bad query"/></testcase></testsuite>', encoding="utf-8"
        )
        self.add_phase(run_dir, "001-backend-postgresql-it.json", {
            "suite": "backend", "kind": "test", "name": "postgresql-it", "status": "failed"
        })
        summary, _ = aggregate_run(run_dir)
        self.assertEqual("FAIL", summary["suites"]["backend"]["status"])

    def test_postgresql_it_runner_failure_without_failed_case_is_error(self):
        run_dir = self.fixture("multiple-surefire")
        postgresql = run_dir / "raw/junit/backend/postgresql"
        postgresql.mkdir(parents=True)
        (postgresql / "TEST-postgresql.xml").write_text(
            '<testsuite><testcase name="query"/></testsuite>', encoding="utf-8"
        )
        self.add_phase(run_dir, "001-backend-postgresql-it.json", {
            "suite": "backend", "kind": "test", "name": "postgresql-it", "status": "failed", "reason": "runner failed"
        })
        summary, _ = aggregate_run(run_dir)
        self.assertEqual("ERROR", summary["suites"]["backend"]["status"])

    def e2e_failure_run(self) -> Path:
        run_dir = self.fixture("all-pass")
        (run_dir / "metadata.json").write_text(
            '{"run_id":"e2e-failure","selected_suites":["e2e"]}', encoding="utf-8"
        )
        (run_dir / "raw/junit/e2e/junit.xml").write_text(
            '<testsuite><testcase name="reject outsider" classname="tests/e2e/specs/workflow.spec.ts">'
            '<failure message="forbidden"/></testcase></testsuite>', encoding="utf-8"
        )
        self.add_phase(run_dir, "001-e2e-playwright.json", {
            "suite": "e2e", "kind": "test", "name": "playwright", "status": "failed"
        })
        return run_dir

    def e2e_phase_run(self, status: str, *, include_junit: bool = True) -> Path:
        run_dir = self.fixture("all-pass")
        (run_dir / "metadata.json").write_text(
            '{"run_id":"e2e-phase","selected_suites":["e2e"]}', encoding="utf-8"
        )
        if not include_junit:
            (run_dir / "raw/junit/e2e/junit.xml").unlink()
        self.add_phase(run_dir, "001-e2e-playwright.json", {
            "suite": "e2e",
            "kind": "test",
            "name": "playwright",
            "status": status,
            "reason": f"playwright {status} before results",
        })
        return run_dir

    def write_playwright_report(self, run_dir: Path) -> None:
        report_path = run_dir / "raw/e2e/report.json"
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps({"suites": [{
            "file": "specs/workflow.spec.ts",
            "specs": [{"title": "e2e passes", "line": 10, "tests": [{"results": [{
                "status": "passed",
                "duration": 30,
                "attachments": [{"path": "/test-results/diagnostics/e2e/results/pass/trace.zip"}],
            }]}]}],
        }]}), encoding="utf-8")

    def test_playwright_json_enriches_location_attachments_and_retries(self):
        run_dir = self.e2e_failure_run()
        report_path = run_dir / "raw/e2e/report.json"
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps({"suites": [{
            "file": "tests/e2e/specs/workflow.spec.ts",
            "specs": [{"title": "reject outsider", "line": 842, "tests": [{"results": [
                {"status": "failed", "duration": 12, "attachments": [
                    {"path": "/test-results/diagnostics/e2e/results/case/trace.zip"},
                    {"path": "/test-results/diagnostics/e2e/results/case/test-failed-1.png"},
                    {"path": "/test-results/diagnostics/e2e/results/case/video.webm"},
                    {"path": "/outside/secret.txt"},
                ]},
                {"status": "passed", "duration": 8, "attachments": []},
            ]}]}],
        }]}), encoding="utf-8")
        summary, _ = aggregate_run(run_dir)
        failure = next(item for item in summary["failures"] if item["kind"] == "test")
        self.assertEqual("tests/e2e/specs/workflow.spec.ts", failure["file"])
        self.assertEqual(842, failure["line"])
        self.assertEqual(3, len(failure["attachments"]))
        self.assertEqual(2, len(failure["retry_results"]))
        self.assertEqual("logs/e2e/playwright.log", failure["log"])
        self.assertEqual("diagnostics/e2e/", failure["diagnostics"])

    def test_failed_playwright_json_missing_is_error(self):
        summary, _ = aggregate_run(self.e2e_failure_run())
        self.assertEqual("ERROR", summary["suites"]["e2e"]["status"])
        self.assertIn("Playwright JSON report is missing", [
            failure["message"] for failure in summary["failures"]
        ])

    def test_passed_playwright_json_missing_is_error(self):
        summary, _ = aggregate_run(self.e2e_phase_run("passed"))
        self.assertEqual("ERROR", summary["suites"]["e2e"]["status"])
        self.assertIn("Playwright JSON report is missing", [
            failure["message"] for failure in summary["failures"]
        ])

    def test_error_playwright_without_results_reports_only_original_error(self):
        summary, _ = aggregate_run(self.e2e_phase_run("error", include_junit=False))
        self.assertEqual("ERROR", summary["suites"]["e2e"]["status"])
        self.assertEqual(
            ["playwright error before results"],
            [failure["message"] for failure in summary["failures"]],
        )

    def test_cancelled_playwright_without_results_reports_only_original_error(self):
        summary, _ = aggregate_run(self.e2e_phase_run("cancelled", include_junit=False))
        self.assertEqual("ERROR", summary["suites"]["e2e"]["status"])
        self.assertEqual(
            ["playwright cancelled before results"],
            [failure["message"] for failure in summary["failures"]],
        )

    def test_passed_playwright_with_json_remains_pass_and_enriches_cases(self):
        run_dir = self.e2e_phase_run("passed")
        self.write_playwright_report(run_dir)
        summary, cases = aggregate_run(run_dir)
        self.assertEqual("PASS", summary["suites"]["e2e"]["status"])
        self.assertEqual("specs/workflow.spec.ts", cases["e2e"][0].file)
        self.assertEqual(10, cases["e2e"][0].line)
        self.assertEqual(1, len(cases["e2e"][0].attachments))
        self.assertEqual(1, len(cases["e2e"][0].retry_results))

    def test_setup_error_failure_list_has_no_missing_playwright_artifacts(self):
        summary, _ = aggregate_run(self.fixture("setup-error"))
        messages = [failure["message"] for failure in summary["failures"]]
        self.assertEqual(["services did not become healthy"], messages)
        self.assertNotIn("Playwright JSON report is missing", messages)
        self.assertNotIn("JUnit XML is missing for selected suite e2e", messages)

    def test_playwright_json_malformed_is_error(self):
        run_dir = self.e2e_failure_run()
        report_path = run_dir / "raw/e2e/report.json"
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text("{", encoding="utf-8")
        summary, _ = aggregate_run(run_dir)
        self.assertEqual("ERROR", summary["suites"]["e2e"]["status"])

    def test_duplicate_check_is_reporter_error(self):
        run_dir = self.fixture("multiple-surefire")
        checks = run_dir / "raw/checks/checks.ndjson"
        checks.write_text(checks.read_text(encoding="utf-8") * 2, encoding="utf-8")
        with self.assertRaises(ResultError):
            aggregate_run(run_dir)

    def test_group_phase_is_not_counted_as_check(self):
        run_dir = self.fixture("multiple-surefire")
        self.add_phase(run_dir, "001-backend-group.json", {
            "suite": "backend", "kind": "group", "name": "migration", "status": "passed"
        })
        summary, _ = aggregate_run(run_dir)
        self.assertEqual(1, summary["checks"]["passed"])

    def test_failed_group_is_not_duplicated_with_failed_child_check(self):
        run_dir = self.fixture("multiple-surefire")
        self.add_phase(run_dir, "001-backend-group.json", {
            "suite": "backend", "kind": "group", "name": "migration", "status": "failed"
        })
        (run_dir / "raw/checks/checks.ndjson").write_text(
            '{"suite":"backend","kind":"check","name":"migration child","status":"failed"}\n', encoding="utf-8"
        )
        summary, _ = aggregate_run(run_dir)
        names = [failure["name"] for failure in summary["failures"]]
        self.assertEqual(["migration child"], names)

    def test_atomic_outputs_preserve_previous_summary_on_failure(self):
        run_dir = self.fixture("all-pass")
        for name in ("summary.json", "summary.md", "merged-junit.xml"):
            (run_dir / name).write_text(f"old {name}", encoding="utf-8")
        with mock.patch.object(aggregate, "write_merged_junit", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                write_outputs(run_dir)
        for name in ("summary.json", "summary.md", "merged-junit.xml"):
            self.assertEqual(f"old {name}", (run_dir / name).read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
