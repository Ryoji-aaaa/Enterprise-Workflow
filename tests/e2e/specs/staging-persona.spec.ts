import { expect, test } from "@playwright/test";

import { extractSafeTopLevelErrorCode } from "../support/safe-diagnostic";
import {
  parseStagingPersonaManifest,
  validateOrganizationFixture,
  type StagingPersona,
} from "../support/staging-persona";

const applicant: StagingPersona = {
  code: "STANDARD_APPLICANT",
  email: "applicant@example.test",
  organizationUnitCode: "SECTION",
  divisionUnitCode: "DIVISION",
  positionCode: "MEMBER",
  requiredRoleCodes: ["APPLICATION_USER"],
  requiredPermissionCodes: ["EXPENSE_APPLICATION_CREATE"],
};
const departmentManager: StagingPersona = {
  code: "DEPARTMENT_MANAGER",
  email: "manager@example.test",
  organizationUnitCode: "SECTION",
  divisionUnitCode: "DIVISION",
  positionCode: "SECTION_HEAD",
  requiredRoleCodes: ["WORKFLOW_APPROVER"],
  requiredPermissionCodes: ["EXPENSE_APPLICATION_APPROVE"],
};
const accountingApprover: StagingPersona = {
  code: "ACCOUNTING_APPROVER",
  email: "accounting@example.test",
  organizationUnitCode: "ACCOUNTING_SECTION",
  divisionUnitCode: "MANAGEMENT_DIVISION",
  positionCode: "MEMBER",
  requiredRoleCodes: ["WORKFLOW_APPROVER"],
  requiredPermissionCodes: ["EXPENSE_APPLICATION_APPROVE"],
};

function chart(sectionParentUnitId: string | null = "department") {
  return {
    units: [
      { id: "division", parentUnitId: null, code: "DIVISION", type: "DIVISION", members: [] },
      { id: "department", parentUnitId: "division", code: "DEPARTMENT", type: "DEPARTMENT", members: [] },
      {
        id: "section",
        parentUnitId: sectionParentUnitId,
        code: "SECTION",
        type: "SECTION",
        members: [
          { email: applicant.email, positionCode: "MEMBER", isHead: false, isPrimary: true },
          { email: departmentManager.email, positionCode: "SECTION_HEAD", isHead: true, isPrimary: true },
        ],
      },
      {
        id: "management-division",
        parentUnitId: null,
        code: "MANAGEMENT_DIVISION",
        type: "DIVISION",
        members: [],
      },
      {
        id: "accounting",
        parentUnitId: "management-division",
        code: "ACCOUNTING_SECTION",
        type: "SECTION",
        members: [
          { email: accountingApprover.email, positionCode: "MEMBER", isHead: false, isPrimary: true },
        ],
      },
    ],
  };
}

test("staging persona manifest contract resolves a requested persona", () => {
  const parsed = parseStagingPersonaManifest(JSON.stringify({
    schemaVersion: 1,
    personas: {
      STANDARD_APPLICANT: {
        email: applicant.email,
        organizationUnitCode: applicant.organizationUnitCode,
        divisionUnitCode: applicant.divisionUnitCode,
        positionCode: applicant.positionCode,
        requiredRoleCodes: applicant.requiredRoleCodes,
        requiredPermissionCodes: applicant.requiredPermissionCodes,
      },
    },
  }), "STANDARD_APPLICANT");

  expect(parsed).toEqual(applicant);
});

test("staging persona manifest rejects an unknown schema", () => {
  expect(() => parseStagingPersonaManifest(JSON.stringify({
    schemaVersion: 2,
    personas: {},
  }), "STANDARD_APPLICANT")).toThrow("schemaVersion must be 1");
});

test("organization preflight accepts the canonical approval fixture shape", () => {
  expect(() => validateOrganizationFixture(chart(), applicant, {
    approvalFixtures: { departmentManager, accountingApprover },
  })).not.toThrow();
});

test("organization preflight rejects an applicant without the required division ancestor", () => {
  expect(() => validateOrganizationFixture(chart(null), applicant)).toThrow(
    "does not reach its required division",
  );
});

test("organization preflight fails fast on a parent cycle", () => {
  const cyclicChart = chart();
  cyclicChart.units.find((unit) => unit.id === "department")!.parentUnitId = "section";
  expect(() => validateOrganizationFixture(cyclicChart, applicant)).toThrow("parent cycle");
});

test("organization preflight fails fast on a missing parent", () => {
  expect(() => validateOrganizationFixture(chart("missing-parent"), applicant)).toThrow("missing parent");
});

test("safe handoff diagnostics keep only a bounded top-level error code", () => {
  expect(extractSafeTopLevelErrorCode({
    code: "DIVISION_NOT_FOUND",
    message: "must not be persisted",
    details: { email: "must-not-appear@example.test" },
  })).toBe("DIVISION_NOT_FOUND");
  expect(extractSafeTopLevelErrorCode({ code: "A".repeat(129) })).toBeUndefined();
  expect(extractSafeTopLevelErrorCode({ code: "unsafe value" })).toBeUndefined();
  expect(extractSafeTopLevelErrorCode({ message: "no code" })).toBeUndefined();
});
