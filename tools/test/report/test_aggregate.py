import shutil
import tempfile
import unittest
from pathlib import Path

from aggregate import aggregate_run, write_outputs


FIXTURES = Path(__file__).parent / "fixtures"


class AggregateTest(unittest.TestCase):
    def fixture(self, name: str) -> Path:
        holder = tempfile.TemporaryDirectory()
        self.addCleanup(holder.cleanup)
        target = Path(holder.name) / "run"
        shutil.copytree(FIXTURES / name, target)
        return target

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


if __name__ == "__main__":
    unittest.main()
