import { readFile } from "node:fs/promises";

import { expect, type Page } from "@playwright/test";

export const STANDARD_APPLICANT = "STANDARD_APPLICANT" as const;

export type StagingPersonaCode =
  | typeof STANDARD_APPLICANT
  | "DEPARTMENT_MANAGER"
  | "DIVISION_HEAD"
  | "ACCOUNTING_APPROVER"
  | "PRESIDENT";

export type StagingPersona = {
  code: StagingPersonaCode;
  email: string;
  organizationUnitCode: string;
  divisionUnitCode?: string;
  positionCode: string;
  requiredRoleCodes: string[];
  requiredPermissionCodes: string[];
};

export type PersonaPreflightCheck =
  | "MANIFEST"
  | "AUTHENTICATION"
  | "IDENTITY"
  | "ROLES"
  | "PERMISSIONS"
  | "ORGANIZATION_CHART"
  | "PRIMARY_ASSIGNMENT"
  | "POSITION"
  | "DIVISION_ANCESTRY"
  | "DEPARTMENT_MANAGER_FIXTURE"
  | "ACCOUNTING_APPROVER_FIXTURE";

type CurrentUser = {
  email: string;
  employmentType: string;
  department: unknown;
  roles: string[];
  permissions: string[];
  features: Record<string, unknown>;
};

type OrganizationMember = {
  email: string;
  positionCode: string | null;
  isHead: boolean;
  isPrimary: boolean;
};

type OrganizationUnit = {
  id: string;
  parentUnitId: string | null;
  code: string;
  type: string;
  members: OrganizationMember[];
};

type OrganizationChart = {
  units: OrganizationUnit[];
};

type PreflightOptions = {
  onCheck?: (check: PersonaPreflightCheck) => void;
  approvalFixtures?: {
    departmentManager: StagingPersona;
    accountingApprover: StagingPersona;
  };
};

export type PersonaPreflightResult = {
  currentUser: CurrentUser;
};

function requireCondition(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function requireString(value: unknown, field: string): string {
  requireCondition(typeof value === "string" && value.length > 0, `Persona ${field} is invalid.`);
  return value;
}

function requireStringArray(value: unknown, field: string): string[] {
  requireCondition(
    Array.isArray(value) && value.every((entry) => typeof entry === "string" && entry.length > 0),
    `Persona ${field} is invalid.`,
  );
  return value;
}

export function parseStagingPersonaManifest(
  content: string,
  code: StagingPersonaCode,
): StagingPersona {
  let parsed: unknown;
  try {
    parsed = JSON.parse(content) as unknown;
  } catch {
    throw new Error("Staging persona manifest is not valid JSON.");
  }
  requireCondition(isRecord(parsed) && parsed.schemaVersion === 1, "Staging persona schemaVersion must be 1.");
  requireCondition(isRecord(parsed.personas), "Staging persona manifest has no personas object.");
  const rawPersona = parsed.personas[code];
  requireCondition(isRecord(rawPersona), `Staging persona ${code} is unavailable.`);

  const divisionUnitCode = rawPersona.divisionUnitCode;
  requireCondition(
    divisionUnitCode === undefined || (typeof divisionUnitCode === "string" && divisionUnitCode.length > 0),
    "Persona divisionUnitCode is invalid.",
  );
  return {
    code,
    email: requireString(rawPersona.email, "email"),
    organizationUnitCode: requireString(rawPersona.organizationUnitCode, "organizationUnitCode"),
    ...(divisionUnitCode ? { divisionUnitCode } : {}),
    positionCode: requireString(rawPersona.positionCode, "positionCode"),
    requiredRoleCodes: requireStringArray(rawPersona.requiredRoleCodes, "requiredRoleCodes"),
    requiredPermissionCodes: rawPersona.requiredPermissionCodes === undefined
      ? []
      : requireStringArray(rawPersona.requiredPermissionCodes, "requiredPermissionCodes"),
  };
}

export async function loadStagingPersona(code: StagingPersonaCode): Promise<StagingPersona> {
  const manifestPath = process.env.STAGING_TEST_PERSONAS_PATH;
  if (!manifestPath) throw new Error("Required environment variable STAGING_TEST_PERSONAS_PATH is not set.");
  let content: string;
  try {
    content = await readFile(manifestPath, "utf8");
  } catch {
    throw new Error("Staging persona manifest is not readable.");
  }
  return parseStagingPersonaManifest(content, code);
}

export async function loginAsStagingPersona(
  page: Page,
  persona: StagingPersona,
  password: string,
): Promise<void> {
  const issuer = process.env.KEYCLOAK_URL;
  if (!issuer) throw new Error("Required environment variable KEYCLOAK_URL is not set.");
  await page.goto("/login");
  await page.getByRole("button", { name: "ログイン", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(
    `^${issuer.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}/realms/workflow/protocol/openid-connect/auth`,
  ));
  await page.locator("#username").fill(persona.email);
  await page.locator("#password").fill(password);
  await page.locator("#kc-login").click();
  await expect(page).toHaveURL(/\/top$/);
}

function parseCurrentUser(value: unknown): CurrentUser {
  requireCondition(isRecord(value), "Persona identity response is invalid.");
  requireCondition(typeof value.email === "string", "Persona identity email is invalid.");
  requireCondition(typeof value.employmentType === "string", "Persona employment type is invalid.");
  requireCondition(Array.isArray(value.roles) && value.roles.every((role) => typeof role === "string"),
    "Persona roles response is invalid.");
  requireCondition(Array.isArray(value.permissions)
    && value.permissions.every((permission) => typeof permission === "string"),
  "Persona permissions response is invalid.");
  requireCondition(isRecord(value.features), "Persona features response is invalid.");
  return {
    email: value.email,
    employmentType: value.employmentType,
    department: value.department,
    roles: value.roles,
    permissions: value.permissions,
    features: value.features,
  };
}

function parseOrganizationChart(value: unknown): OrganizationChart {
  requireCondition(isRecord(value) && Array.isArray(value.units), "Organization chart response is invalid.");
  const units = value.units.map((rawUnit) => {
    requireCondition(isRecord(rawUnit), "Organization chart unit is invalid.");
    requireCondition(typeof rawUnit.id === "string", "Organization chart unit id is invalid.");
    requireCondition(rawUnit.parentUnitId === null || typeof rawUnit.parentUnitId === "string",
      "Organization chart parent unit id is invalid.");
    requireCondition(typeof rawUnit.code === "string", "Organization chart unit code is invalid.");
    requireCondition(typeof rawUnit.type === "string", "Organization chart unit type is invalid.");
    requireCondition(Array.isArray(rawUnit.members), "Organization chart members are invalid.");
    const members = rawUnit.members.map((rawMember) => {
      requireCondition(isRecord(rawMember), "Organization chart member is invalid.");
      requireCondition(typeof rawMember.email === "string", "Organization chart member email is invalid.");
      requireCondition(rawMember.positionCode === null || typeof rawMember.positionCode === "string",
        "Organization chart member position is invalid.");
      requireCondition(typeof rawMember.isHead === "boolean", "Organization chart member head flag is invalid.");
      requireCondition(typeof rawMember.isPrimary === "boolean", "Organization chart member primary flag is invalid.");
      return {
        email: rawMember.email,
        positionCode: rawMember.positionCode,
        isHead: rawMember.isHead,
        isPrimary: rawMember.isPrimary,
      };
    });
    return {
      id: rawUnit.id,
      parentUnitId: rawUnit.parentUnitId,
      code: rawUnit.code,
      type: rawUnit.type,
      members,
    };
  });
  return { units };
}

function findPersonaAssignment(
  chart: OrganizationChart,
  persona: StagingPersona,
): { unit: OrganizationUnit; member: OrganizationMember } {
  const unit = chart.units.find((candidate) => candidate.code === persona.organizationUnitCode);
  requireCondition(Boolean(unit), `Persona ${persona.code} organization unit is unavailable.`);
  const member = unit.members.find((candidate) => candidate.email === persona.email);
  requireCondition(Boolean(member), `Persona ${persona.code} assignment is unavailable.`);
  return { unit, member };
}

function requirePersonaAssignment(
  chart: OrganizationChart,
  persona: StagingPersona,
): { unit: OrganizationUnit; member: OrganizationMember } {
  const { unit, member } = findPersonaAssignment(chart, persona);
  requireCondition(member.isPrimary, `Persona ${persona.code} assignment is not primary.`);
  requireCondition(member.positionCode === persona.positionCode, `Persona ${persona.code} position does not match.`);
  return { unit, member };
}

function requireDivisionAncestry(
  chart: OrganizationChart,
  persona: StagingPersona,
  startUnit: OrganizationUnit,
): void {
  if (!persona.divisionUnitCode) return;
  const expectedDivision = chart.units.find((unit) => unit.code === persona.divisionUnitCode);
  requireCondition(Boolean(expectedDivision), `Persona ${persona.code} division is unavailable.`);
  requireCondition(expectedDivision.type === "DIVISION", `Persona ${persona.code} division type does not match.`);

  const unitsById = new Map(chart.units.map((unit) => [unit.id, unit]));
  const visited = new Set<string>();
  let current: OrganizationUnit | undefined = startUnit;
  while (current) {
    requireCondition(!visited.has(current.id), "Organization chart contains a parent cycle.");
    visited.add(current.id);
    if (current.id === expectedDivision.id) return;
    if (current.parentUnitId === null) break;
    current = unitsById.get(current.parentUnitId);
    requireCondition(Boolean(current), "Organization chart contains a missing parent.");
  }
  throw new Error(`Persona ${persona.code} does not reach its required division.`);
}

export function validateOrganizationFixture(
  chart: OrganizationChart,
  persona: StagingPersona,
  options: Pick<PreflightOptions, "approvalFixtures"> = {},
): void {
  const applicant = requirePersonaAssignment(chart, persona);
  requireDivisionAncestry(chart, persona, applicant.unit);
  if (!options.approvalFixtures) return;

  const manager = requirePersonaAssignment(chart, options.approvalFixtures.departmentManager);
  requireCondition(
    manager.unit.id === applicant.unit.id,
    "Department manager is not assigned to the applicant organization unit.",
  );
  requirePersonaAssignment(chart, options.approvalFixtures.accountingApprover);
}

export async function preflightStagingPersona(
  page: Page,
  persona: StagingPersona,
  options: PreflightOptions = {},
): Promise<PersonaPreflightResult> {
  options.onCheck?.("IDENTITY");
  const meResponse = await page.request.get("/api/backend/me");
  requireCondition(meResponse.status() === 200, "Persona identity request failed.");
  const currentUser = parseCurrentUser(await meResponse.json());
  requireCondition(currentUser.email === persona.email, "Authenticated persona identity does not match.");

  options.onCheck?.("ROLES");
  requireCondition(
    persona.requiredRoleCodes.every((role) => currentUser.roles.includes(role)),
    "Authenticated persona roles do not satisfy the manifest.",
  );
  options.onCheck?.("PERMISSIONS");
  requireCondition(
    persona.requiredPermissionCodes.every((permission) => currentUser.permissions.includes(permission)),
    "Authenticated persona permissions do not satisfy the manifest.",
  );

  options.onCheck?.("ORGANIZATION_CHART");
  const chartResponse = await page.request.get("/api/backend/organization-chart");
  requireCondition(chartResponse.status() === 200, "Persona organization chart request failed.");
  const chart = parseOrganizationChart(await chartResponse.json());

  options.onCheck?.("PRIMARY_ASSIGNMENT");
  const applicant = findPersonaAssignment(chart, persona);
  requireCondition(applicant.member.isPrimary, `Persona ${persona.code} assignment is not primary.`);
  options.onCheck?.("POSITION");
  requireCondition(applicant.member.positionCode === persona.positionCode, "Persona position does not match.");
  options.onCheck?.("DIVISION_ANCESTRY");
  requireDivisionAncestry(chart, persona, applicant.unit);

  if (options.approvalFixtures) {
    options.onCheck?.("DEPARTMENT_MANAGER_FIXTURE");
    const manager = requirePersonaAssignment(chart, options.approvalFixtures.departmentManager);
    requireCondition(
      manager.unit.id === applicant.unit.id,
      "Department manager is not assigned to the applicant organization unit.",
    );
    options.onCheck?.("ACCOUNTING_APPROVER_FIXTURE");
    requirePersonaAssignment(chart, options.approvalFixtures.accountingApprover);
  }
  return { currentUser };
}
