import assert from "node:assert/strict";
import test from "node:test";

import {
  buildOrganizationChartIndex,
  canEditOrganizationChartUsers,
  organizationChartUserEditPath,
} from "./organization-chart-tree.ts";

test("統治機関を社長配下の業務組織から分離して表示順に並べる", () => {
  const units = [
    { id: "division", parentUnitId: null, type: "DIVISION", displayOrder: 30 },
    { id: "board", parentUnitId: null, type: "OTHER", displayOrder: 20 },
    { id: "shareholders", parentUnitId: null, type: "OTHER", displayOrder: 10 },
    { id: "department", parentUnitId: "division", type: "DEPARTMENT", displayOrder: 10 },
  ];

  const index = buildOrganizationChartIndex(units);

  assert.deepEqual(index.governanceUnits.map((unit) => unit.id), ["shareholders", "board"]);
  assert.deepEqual(index.operationalUnits.map((unit) => unit.id), ["division"]);
  assert.deepEqual(index.childrenByParent.get("division")?.map((unit) => unit.id), ["department"]);
});

test("USER_UPDATEを持つ場合だけ組織図のユーザー編集操作を許可する", () => {
  assert.equal(canEditOrganizationChartUsers(["ORGANIZATION_CHART_READ", "USER_UPDATE"]), true);
  assert.equal(canEditOrganizationChartUsers(["ORGANIZATION_CHART_READ", "USER_READ"]), false);
  assert.equal(canEditOrganizationChartUsers([]), false);
});

test("組織図の編集操作はユーザーIDを含む管理画面へ遷移する", () => {
  assert.equal(
    organizationChartUserEditPath("123e4567-e89b-42d3-a456-426614174000"),
    "/admin/users/123e4567-e89b-42d3-a456-426614174000/edit",
  );
});
