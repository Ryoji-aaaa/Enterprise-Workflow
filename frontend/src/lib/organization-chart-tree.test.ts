import assert from "node:assert/strict";
import test from "node:test";

import { buildOrganizationChartIndex } from "./organization-chart-tree.ts";

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
