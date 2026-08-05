"""Aggregate JUnit and NDJSON results produced by the workflow test harness."""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any, Iterable


JUNIT_SUITES = ("backend", "frontend", "e2e")
ALL_SUITES = ("backend", "frontend", "keycloak", "e2e")


class ResultError(RuntimeError):
    """A structured test result is missing or unusable."""


@dataclass
class TestCase:
    suite: str
    name: str
    classname: str = ""
    status: str = "passed"
    duration_ms: int = 0
    file: str | None = None
    line: int | None = None
    message: str | None = None
    log: str | None = None
    diagnostics: str | None = None
    attachments: list[str] = field(default_factory=list)
    retry_results: list[dict[str, Any]] = field(default_factory=list)


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def parse_duration_ms(value: str | None) -> int:
    try:
        return max(0, round(float(value or "0") * 1000))
    except ValueError:
        return 0


def child_with_name(element: ET.Element, name: str) -> ET.Element | None:
    return next((child for child in element if local_name(child.tag) == name), None)


def parse_junit_file(path: Path, suite: str) -> list[TestCase]:
    try:
        root = ET.parse(path).getroot()
    except (ET.ParseError, OSError) as error:
        raise ResultError(f"Malformed JUnit XML: {path}: {error}") from error

    cases: list[TestCase] = []
    for element in root.iter():
        if local_name(element.tag) != "testcase":
            continue
        failure = child_with_name(element, "failure")
        error = child_with_name(element, "error")
        skipped = child_with_name(element, "skipped")
        status = "passed"
        detail = None
        if failure is not None:
            status = "failed"
            detail = failure
        elif error is not None:
            status = "error"
            detail = error
        elif skipped is not None:
            status = "skipped"
            detail = skipped

        message = None
        if detail is not None:
            message = detail.get("message") or (detail.text or "").strip() or None
        line_value = element.get("line")
        try:
            line = int(line_value) if line_value else None
        except ValueError:
            line = None
        cases.append(
            TestCase(
                suite=suite,
                name=element.get("name", "unnamed test"),
                classname=element.get("classname", ""),
                status=status,
                duration_ms=parse_duration_ms(element.get("time")),
                file=element.get("file"),
                line=line,
                message=message,
            )
        )
    return cases


def read_ndjson(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    try:
        lines = path.read_text(encoding="utf-8").splitlines()
    except OSError as error:
        raise ResultError(f"Cannot read result file: {path}: {error}") from error
    for line_number, line in enumerate(lines, 1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as error:
            raise ResultError(f"Malformed NDJSON: {path}:{line_number}: {error}") from error
        if not isinstance(record, dict):
            raise ResultError(f"NDJSON record is not an object: {path}:{line_number}")
        records.append(record)
    return records


def ndjson_test_cases(path: Path, suite: str) -> list[TestCase]:
    cases: list[TestCase] = []
    for record in read_ndjson(path):
        if record.get("kind") != "test":
            continue
        status = str(record.get("status", "error"))
        if status not in {"passed", "failed", "error", "skipped"}:
            status = "error"
        line = record.get("line")
        cases.append(
            TestCase(
                suite=suite,
                name=str(record.get("name", "unnamed test")),
                classname=suite,
                status=status,
                duration_ms=max(0, int(record.get("duration_ms", 0))),
                file=record.get("file"),
                line=int(line) if isinstance(line, int) else None,
                message=record.get("message"),
                log=record.get("log"),
                diagnostics=record.get("diagnostics"),
            )
        )
    return cases


def suite_counts(cases: Iterable[TestCase]) -> dict[str, int]:
    result = {"discovered": 0, "executed": 0, "passed": 0, "failed": 0, "errors": 0, "skipped": 0}
    for case in cases:
        result["discovered"] += 1
        if case.status != "skipped":
            result["executed"] += 1
        key = {"passed": "passed", "failed": "failed", "error": "errors", "skipped": "skipped"}[case.status]
        result[key] += 1
    return result


def phase_records(run_dir: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for path in sorted((run_dir / "phases").glob("*.json")):
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as error:
            raise ResultError(f"Malformed phase result: {path}: {error}") from error
        if isinstance(record, dict):
            records.append(record)
    return records


def phase_has_expected_results(run_dir: Path, suite: str, phase_name: str) -> bool:
    if (suite, phase_name) == ("backend", "junit"):
        return any((run_dir / "raw" / "junit" / "backend" / "surefire").glob("*.xml"))
    if (suite, phase_name) == ("backend", "postgresql-it"):
        return any((run_dir / "raw" / "junit" / "backend" / "postgresql").glob("*.xml"))
    if (suite, phase_name) == ("frontend", "unit"):
        return (run_dir / "raw" / "junit" / "frontend" / "junit.xml").is_file()
    if (suite, phase_name) == ("keycloak", "contracts"):
        return (run_dir / "raw" / "cases" / "keycloak.ndjson").is_file()
    if (suite, phase_name) == ("e2e", "playwright"):
        return (run_dir / "raw" / "junit" / "e2e" / "junit.xml").is_file()
    return True


def normalize_attachment_path(value: Any) -> str | None:
    if not isinstance(value, str) or not value:
        return None
    path = PurePosixPath(value.replace("\\", "/"))
    parts = path.parts
    if path.is_absolute():
        if len(parts) < 3 or parts[1] != "test-results":
            return None
        parts = parts[2:]
    elif parts and parts[0] == "test-results":
        parts = parts[1:]
    if not parts or ".." in parts or "." in parts:
        return None
    relative = PurePosixPath(*parts)
    if not relative.is_relative_to(PurePosixPath("diagnostics/e2e")):
        return None
    return str(relative)


def playwright_specs(node: dict[str, Any], inherited_file: str | None = None) -> Iterable[dict[str, Any]]:
    node_file = node.get("file") if isinstance(node.get("file"), str) else inherited_file
    for spec in node.get("specs", []):
        if isinstance(spec, dict):
            yield {**spec, "_file": spec.get("file") or node_file}
    for child in node.get("suites", []):
        if isinstance(child, dict):
            yield from playwright_specs(child, node_file)


def read_playwright_json(path: Path) -> list[dict[str, Any]]:
    try:
        report = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ResultError(f"Malformed Playwright JSON: {path}: {error}") from error
    if not isinstance(report, dict) or not isinstance(report.get("suites"), list):
        raise ResultError(f"Malformed Playwright JSON: {path}: suites is missing")

    records: list[dict[str, Any]] = []
    for suite in report["suites"]:
        if not isinstance(suite, dict):
            continue
        for spec in playwright_specs(suite):
            results: list[dict[str, Any]] = []
            attachments: list[str] = []
            for test in spec.get("tests", []):
                if not isinstance(test, dict):
                    continue
                for attempt, result in enumerate(test.get("results", [])):
                    if not isinstance(result, dict):
                        continue
                    results.append({
                        "attempt": attempt,
                        "status": result.get("status"),
                        "duration_ms": result.get("duration", 0),
                    })
                    for attachment in result.get("attachments", []):
                        if not isinstance(attachment, dict):
                            continue
                        normalized = normalize_attachment_path(attachment.get("path"))
                        if normalized and normalized not in attachments:
                            attachments.append(normalized)
            line = spec.get("line")
            records.append({
                "title": str(spec.get("title", "")),
                "file": str(spec.get("_file")) if spec.get("_file") else None,
                "line": int(line) if isinstance(line, int) else None,
                "retry_results": results,
                "attachments": attachments,
            })
    return records


def files_match(left: str | None, right: str | None) -> bool:
    if not left or not right:
        return False
    left_path = left.replace("\\", "/").lstrip("./")
    right_path = right.replace("\\", "/").lstrip("./")
    return left_path == right_path or left_path.endswith("/" + right_path) or right_path.endswith("/" + left_path)


def titles_match(case_name: str, title: str) -> bool:
    return case_name == title or case_name.endswith(f" › {title}") or case_name.endswith(f" > {title}")


def enrich_e2e_cases(cases: list[TestCase], records: list[dict[str, Any]]) -> None:
    for case in cases:
        if case.status in {"failed", "error"}:
            case.log = "logs/e2e/playwright.log"
            case.diagnostics = "diagnostics/e2e/"
        title_matches = [record for record in records if titles_match(case.name, record["title"])]
        file_value = case.file or case.classname
        exact = [
            record for record in title_matches
            if files_match(file_value, record["file"])
            and case.line is not None and case.line == record["line"]
        ]
        file_matches = [record for record in title_matches if files_match(file_value, record["file"])]
        match = exact[0] if len(exact) == 1 else file_matches[0] if len(file_matches) == 1 else title_matches[0] if len(title_matches) == 1 else None
        if match is None:
            continue
        case.file = match["file"] or case.file
        case.line = match["line"] if match["line"] is not None else case.line
        case.attachments = match["attachments"]
        case.retry_results = match["retry_results"]


def result_failure(case: TestCase) -> dict[str, Any]:
    return {
        "suite": case.suite,
        "kind": "test",
        "name": case.name,
        "status": case.status,
        "file": case.file,
        "line": case.line,
        "message": case.message,
        "log": case.log,
        "diagnostics": case.diagnostics,
        "attachments": case.attachments,
        "retry_results": case.retry_results,
    }


def aggregate_run(run_dir: Path) -> tuple[dict[str, Any], dict[str, list[TestCase]]]:
    metadata_path = run_dir / "metadata.json"
    try:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise ResultError(f"Cannot read metadata: {metadata_path}: {error}") from error
    selected = metadata.get("selected_suites", [])
    if not isinstance(selected, list) or not selected or any(s not in ALL_SUITES for s in selected):
        raise ResultError("metadata.json has invalid selected_suites")

    phases = phase_records(run_dir)
    cases_by_suite: dict[str, list[TestCase]] = {suite: [] for suite in selected}
    suite_result_errors: dict[str, list[str]] = {suite: [] for suite in selected}
    failures: list[dict[str, Any]] = []

    for suite in selected:
        if suite in JUNIT_SUITES:
            junit_dir = run_dir / "raw" / "junit" / suite
            xml_files = sorted(junit_dir.rglob("*.xml")) if junit_dir.is_dir() else []
            if not xml_files:
                suite_result_errors[suite].append(f"JUnit XML is missing for selected suite {suite}")
            for path in xml_files:
                try:
                    cases_by_suite[suite].extend(parse_junit_file(path, suite))
                except ResultError as error:
                    suite_result_errors[suite].append(str(error))
        else:
            case_path = run_dir / "raw" / "cases" / "keycloak.ndjson"
            if not case_path.is_file():
                suite_result_errors[suite].append("Keycloak case result file is missing")
            else:
                try:
                    cases_by_suite[suite].extend(ndjson_test_cases(case_path, suite))
                except ResultError as error:
                    suite_result_errors[suite].append(str(error))

        playwright_executed = suite == "e2e" and any(
            phase.get("suite") == "e2e"
            and phase.get("kind") == "test"
            and phase.get("name") == "playwright"
            for phase in phases
        )
        if playwright_executed:
            playwright_path = run_dir / "raw" / "e2e" / "report.json"
            if not playwright_path.is_file():
                suite_result_errors[suite].append("Playwright JSON report is missing")
            else:
                try:
                    enrich_e2e_cases(cases_by_suite[suite], read_playwright_json(playwright_path))
                except ResultError as error:
                    suite_result_errors[suite].append(str(error))

        if not cases_by_suite[suite] and not suite_result_errors[suite]:
            suite_result_errors[suite].append(f"Selected suite {suite} discovered zero tests")

        for phase in phases:
            if (
                phase.get("suite") == suite
                and phase.get("kind") != "group"
                and phase.get("status") in {"error", "cancelled"}
            ):
                reason = phase.get("reason") or f"Phase {phase.get('name', 'unknown')} ended as {phase.get('status')}"
                suite_result_errors[suite].append(str(reason))
            if (
                phase.get("suite") == suite
                and phase.get("kind") == "test"
                and phase.get("status") == "passed"
                and not phase_has_expected_results(run_dir, suite, str(phase.get("name", "")))
            ):
                suite_result_errors[suite].append(
                    f"Successful test phase {phase.get('name', 'unknown')} did not produce its expected result file"
                )
        failed_test_phases = [
            phase for phase in phases
            if phase.get("suite") == suite
            and phase.get("kind") == "test"
            and phase.get("status") == "failed"
        ]
        if failed_test_phases and not any(case.status in {"failed", "error"} for case in cases_by_suite[suite]):
            for phase in failed_test_phases:
                suite_result_errors[suite].append(
                    str(phase.get("reason") or f"Test runner {phase.get('name', 'unknown')} failed without a failing structured test case")
                )

    suites: dict[str, dict[str, Any]] = {}
    for suite in selected:
        counts = suite_counts(cases_by_suite[suite])
        if suite_result_errors[suite]:
            status = "ERROR"
            for reason in dict.fromkeys(suite_result_errors[suite]):
                failures.append({
                    "suite": suite,
                    "kind": "runner",
                    "name": f"{suite} result",
                    "status": "error",
                    "file": None,
                    "line": None,
                    "message": reason,
                    "log": None,
                    "diagnostics": None,
                    "attachments": [],
                    "retry_results": [],
                })
        elif counts["failed"] or counts["errors"]:
            status = "FAIL"
        else:
            status = "PASS"
        suites[suite] = {
            "status": status,
            **counts,
            "duration_ms": sum(case.duration_ms for case in cases_by_suite[suite]),
        }
        failures.extend(result_failure(case) for case in cases_by_suite[suite] if case.status in {"failed", "error"})

    checks_path = run_dir / "raw" / "checks" / "checks.ndjson"
    check_counts = {"passed": 0, "failed": 0, "errors": 0, "skipped": 0}
    if checks_path.is_file():
        check_records = read_ndjson(checks_path)
        seen_checks: set[tuple[str, str]] = set()
        for check in check_records:
            if check.get("kind") != "check":
                continue
            check_identity = (str(check.get("suite", "harness")), str(check.get("name", "unnamed check")))
            if check_identity in seen_checks:
                raise ResultError(f"Duplicate required check result: {check_identity[0]} / {check_identity[1]}")
            seen_checks.add(check_identity)
            status = str(check.get("status", "error"))
            key = {"passed": "passed", "failed": "failed", "error": "errors", "skipped": "skipped", "cancelled": "errors"}.get(status, "errors")
            check_counts[key] += 1
            if key in {"failed", "errors"}:
                failures.append({
                    "suite": str(check.get("suite", "harness")),
                    "kind": "check",
                    "name": str(check.get("name", "unnamed check")),
                    "status": status,
                    "file": check.get("file"),
                    "line": check.get("line"),
                    "message": check.get("message") or check.get("reason"),
                    "log": check.get("log"),
                    "diagnostics": check.get("diagnostics"),
                    "attachments": [],
                    "retry_results": [],
                })
    else:
        check_counts["errors"] = 1
        failures.append({
            "suite": "harness", "kind": "check", "name": "required check results", "status": "error",
            "file": None, "line": None, "message": "Required check result file is missing", "log": None, "diagnostics": None,
            "attachments": [], "retry_results": [],
        })

    has_runner_error = any(item["status"] == "ERROR" for item in suites.values()) or check_counts["errors"] > 0
    has_failure = any(item["status"] == "FAIL" for item in suites.values()) or check_counts["failed"] > 0
    overall = "ERROR" if has_runner_error else "FAIL" if has_failure else "PASS"
    summary = {
        "run_id": metadata.get("run_id", run_dir.name),
        "overall": overall,
        "selected_suites": selected,
        "suites": suites,
        "checks": check_counts,
        "failures": failures,
    }
    return summary, cases_by_suite


def xml_safe(value: str | None) -> str:
    if not value:
        return ""
    return "".join(character for character in value if character in "\t\n\r" or ord(character) >= 32)


def write_merged_junit(path: Path, cases_by_suite: dict[str, list[TestCase]]) -> None:
    root = ET.Element("testsuites")
    for suite, cases in cases_by_suite.items():
        counts = suite_counts(cases)
        node = ET.SubElement(root, "testsuite", {
            "name": suite,
            "tests": str(counts["discovered"]),
            "failures": str(counts["failed"]),
            "errors": str(counts["errors"]),
            "skipped": str(counts["skipped"]),
            "time": f"{sum(case.duration_ms for case in cases) / 1000:.3f}",
        })
        for case in cases:
            attributes = {"name": case.name, "classname": case.classname or suite, "time": f"{case.duration_ms / 1000:.3f}"}
            if case.file:
                attributes["file"] = case.file
            if case.line is not None:
                attributes["line"] = str(case.line)
            case_node = ET.SubElement(node, "testcase", attributes)
            if case.status == "failed":
                detail = ET.SubElement(case_node, "failure", {"message": xml_safe(case.message)})
                detail.text = xml_safe(case.message)
            elif case.status == "error":
                detail = ET.SubElement(case_node, "error", {"message": xml_safe(case.message)})
                detail.text = xml_safe(case.message)
            elif case.status == "skipped":
                ET.SubElement(case_node, "skipped", {"message": xml_safe(case.message)})
    ET.ElementTree(root).write(path, encoding="utf-8", xml_declaration=True)


def summary_markdown(summary: dict[str, Any]) -> str:
    lines = [
        "# Test Summary",
        "",
        f"Overall result: **{summary['overall']}**",
        "",
        "| Suite | Status | Discovered | Executed | Pass | Fail | Error | Skip |",
        "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for suite in summary["selected_suites"]:
        item = summary["suites"][suite]
        lines.append(
            f"| {suite.title()} | {item['status']} | {item['discovered']} | {item['executed']} | "
            f"{item['passed']} | {item['failed']} | {item['errors']} | {item['skipped']} |"
        )
    checks = summary["checks"]
    lines.extend([
        "",
        f"Required checks: {checks['passed']} passed, {checks['failed']} failed, "
        f"{checks['errors']} errors, {checks['skipped']} skipped",
        "",
    ])
    if summary["failures"]:
        lines.append("## Failures")
        lines.append("")
        for index, failure in enumerate(summary["failures"], 1):
            lines.append(f"{index}. **[{failure['suite'].upper()}] {failure['name']}**")
            if failure.get("file"):
                location = failure["file"] + (f":{failure['line']}" if failure.get("line") else "")
                lines.append(f"   - File: `{location}`")
            if failure.get("message"):
                lines.append(f"   - {failure['message']}")
            if failure.get("log"):
                lines.append(f"   - Log: `{failure['log']}`")
            if failure.get("diagnostics"):
                lines.append(f"   - Diagnostics: `{failure['diagnostics']}`")
            for attachment in failure.get("attachments", []):
                lines.append(f"   - Attachment: `{attachment}`")
    return "\n".join(lines).rstrip() + "\n"


def console_summary(summary: dict[str, Any], display_path: str) -> str:
    selected = summary["selected_suites"]
    lines = [
        "======================= FINAL TEST SUMMARY =======================",
        "",
        f"Overall result: {summary['overall']}",
        "",
        "Suite       Status  Discovered  Executed  Pass  Fail  Error  Skip",
        "----------  ------  ----------  --------  ----  ----  -----  ----",
    ]
    totals = {key: 0 for key in ("discovered", "executed", "passed", "failed", "errors", "skipped")}
    for suite in selected:
        item = summary["suites"][suite]
        for key in totals:
            totals[key] += item[key]
        lines.append(
            f"{suite.title():<10}  {item['status']:<6}  {item['discovered']:>10}  {item['executed']:>8}  "
            f"{item['passed']:>4}  {item['failed']:>4}  {item['errors']:>5}  {item['skipped']:>4}"
        )
    lines.extend([
        "----------  ------  ----------  --------  ----  ----  -----  ----",
        f"TOTAL               {totals['discovered']:>10}  {totals['executed']:>8}  {totals['passed']:>4}  "
        f"{totals['failed']:>4}  {totals['errors']:>5}  {totals['skipped']:>4}",
        "",
    ])
    checks = summary["checks"]
    lines.append(
        f"Required checks: {checks['passed']} passed, {checks['failed']} failed, "
        f"{checks['errors']} errors, {checks['skipped']} skipped"
    )
    if summary["failures"]:
        lines.extend(["", "Failures:", ""])
        for index, failure in enumerate(summary["failures"], 1):
            lines.append(f"{index}. [{failure['suite'].upper()}] {failure['name']}")
            if failure.get("file"):
                location = failure["file"] + (f":{failure['line']}" if failure.get("line") else "")
                lines.append(f"   File: {location}")
            if failure.get("message"):
                message = str(failure["message"])
                lines.append(f"   {message[:1000]}{'…' if len(message) > 1000 else ''}")
            if failure.get("log"):
                lines.append(f"   Log: {failure['log']}")
            if failure.get("diagnostics"):
                lines.append(f"   Diagnostics: {failure['diagnostics']}")
            for attachment in failure.get("attachments", [])[:5]:
                lines.append(f"   Attachment: {attachment}")
    qualifier = "ALL TESTS OK" if selected == list(ALL_SUITES) else "ALL SELECTED TESTS OK"
    lines.extend([
        "",
        f"{qualifier}: {'YES' if summary['overall'] == 'PASS' else 'NO'}",
        f"Artifacts: {display_path}",
        "==================================================================",
    ])
    return "\n".join(lines) + "\n"


def write_outputs(run_dir: Path) -> dict[str, Any]:
    summary, cases_by_suite = aggregate_run(run_dir)
    targets = {
        "summary.json": json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        "summary.md": summary_markdown(summary),
        "merged-junit.xml": None,
    }
    temporary_paths: dict[str, Path] = {}
    try:
        for name, content in targets.items():
            with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                dir=run_dir,
                prefix=f".{name}.",
                suffix=".tmp",
                delete=False,
            ) as temporary:
                temporary_path = Path(temporary.name)
                if content is not None:
                    temporary.write(content)
            temporary_paths[name] = temporary_path
            if name == "merged-junit.xml":
                write_merged_junit(temporary_path, cases_by_suite)
        for name in targets:
            temporary_paths[name].replace(run_dir / name)
    finally:
        for temporary_path in temporary_paths.values():
            temporary_path.unlink(missing_ok=True)
    return summary


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-dir", required=True, type=Path)
    parser.add_argument("--print-summary", action="store_true")
    parser.add_argument("--display-path")
    args = parser.parse_args()
    try:
        summary = write_outputs(args.run_dir)
    except Exception as error:  # the harness must treat every reporter exception as an error
        print(f"Reporter error: {error}", file=sys.stderr)
        return 2
    if args.print_summary:
        sys.stdout.write(console_summary(summary, args.display_path or str(args.run_dir)))
    if summary["overall"] == "ERROR":
        return 2
    return 0 if summary["overall"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
