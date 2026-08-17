import assert from "node:assert/strict";
import test from "node:test";

import type { AutoEntryField } from "./auto-entry-review.ts";
import {
  getAutoEntryEvidenceSources,
  renderAutoEntryPageEvidence,
  scaleAutoEntryPolygon,
} from "./expense-auto-entry-evidence.ts";
import type { ResolvedAutoEntryField } from "./expense-auto-entry.ts";

function field(
  path: string,
  sources: AutoEntryField<string>["sources"],
  { deleted = false, resolution = "NOT_REQUIRED" }: {
    deleted?: boolean;
    resolution?: ResolvedAutoEntryField["resolution"];
  } = {},
): ResolvedAutoEntryField {
  return {
    path,
    label: path,
    field: {
      value: "AI value",
      confidence: 0.99,
      status: "OK",
      sources,
      findings: [],
    },
    currentValue: resolution === "EDITED" ? "edited value" : "AI value",
    deleted,
    resolution,
  };
}

function closeTo(actual: number, expected: number): void {
  assert.ok(Math.abs(actual - expected) < 1e-9, `${actual} is not close to ${expected}`);
}

test("polygonをsource pageからrendered pageの幅と高さへ個別にscaleする", () => {
  const scaled = scaleAutoEntryPolygon(
    [{ x: 10, y: 20 }, { x: 40, y: 20 }, { x: 25, y: 50 }],
    100,
    200,
    250,
    500,
  );

  assert.deepEqual(scaled, [
    { x: 25, y: 50 },
    { x: 100, y: 50 },
    { x: 62.5, y: 125 },
  ]);
});

test("任意点数、複数source、複数pageのtracked field evidenceを保持する", () => {
  const evidence = getAutoEntryEvidenceSources([
    field("document.issuerName", [
      { pageNumber: 1, polygon: [{ x: 1, y: 2 }, { x: 3, y: 2 }, { x: 2, y: 4 }] },
      { pageNumber: 2, polygon: [
        { x: 5, y: 6 }, { x: 8, y: 6 }, { x: 9, y: 8 }, { x: 7, y: 10 }, { x: 5, y: 8 },
      ] },
    ]),
    field("document.totalAmount", [
      { pageNumber: 2, polygon: [{ x: 10, y: 20 }, { x: 20, y: 20 }, { x: 20, y: 30 }, { x: 10, y: 30 }] },
    ]),
  ]);

  assert.deepEqual(evidence.map(({ fieldPath, pageNumber, sourceIndex, polygon }) => ({
    fieldPath,
    pageNumber,
    sourceIndex,
    pointCount: polygon.length,
  })), [
    { fieldPath: "document.issuerName", pageNumber: 1, sourceIndex: 0, pointCount: 3 },
    { fieldPath: "document.issuerName", pageNumber: 2, sourceIndex: 1, pointCount: 5 },
    { fieldPath: "document.totalAmount", pageNumber: 2, sourceIndex: 0, pointCount: 4 },
  ]);

  const secondPage = renderAutoEntryPageEvidence(
    { pageNumber: 2, width: 100, height: 200, unit: "pixel", angleDegrees: 90 },
    evidence,
    300,
    400,
  );
  assert.equal(secondPage.length, 2);
  assert.deepEqual(secondPage.map(({ fieldPath }) => fieldPath), [
    "document.issuerName",
    "document.totalAmount",
  ]);
});

test("sourcesなし、削除済みAI明細、人間追加明細はevidenceを生成しない", () => {
  const evidence = getAutoEntryEvidenceSources([
    field("document.issuerTaxRegistrationNumber", []),
    field("document.lineItems[0].itemDescription", [{
      pageNumber: 1,
      polygon: [{ x: 1, y: 1 }, { x: 2, y: 1 }, { x: 2, y: 2 }, { x: 1, y: 2 }],
    }], { deleted: true, resolution: "EDITED" }),
  ]);

  // sourceLineItemIndex=nullの人間追加明細はResolvedAutoEntryFieldへ追加されない。
  assert.deepEqual(evidence, []);
});

test("編集済みAI fieldでも元sourceをevidenceとして維持する", () => {
  const evidence = getAutoEntryEvidenceSources([
    field("document.issuerName", [{
      pageNumber: 1,
      polygon: [{ x: 1, y: 1 }, { x: 2, y: 1 }, { x: 2, y: 2 }, { x: 1, y: 2 }],
    }], { resolution: "EDITED" }),
  ]);

  assert.equal(evidence.length, 1);
  assert.equal(evidence[0]?.fieldPath, "document.issuerName");
});

test("3点未満の不正polygonと無効なpage寸法は安全に描画しない", () => {
  const evidence = getAutoEntryEvidenceSources([
    field("document.totalAmount", [{
      pageNumber: 1,
      polygon: [{ x: 1, y: 1 }, { x: 2, y: 2 }],
    }]),
  ]);
  assert.deepEqual(evidence, []);
  assert.deepEqual(scaleAutoEntryPolygon([{ x: 1, y: 1 }], 0, 100, 200, 300), []);
});

test("resize後もsource pageに対する座標比率を維持する", () => {
  const polygon = [{ x: 15, y: 25 }, { x: 45, y: 25 }, { x: 45, y: 75 }, { x: 15, y: 75 }];
  const small = scaleAutoEntryPolygon(polygon, 60, 100, 300, 400);
  const large = scaleAutoEntryPolygon(polygon, 60, 100, 600, 800);

  for (let index = 0; index < polygon.length; index += 1) {
    closeTo((small[index]?.x ?? 0) / 300, (large[index]?.x ?? 0) / 600);
    closeTo((small[index]?.y ?? 0) / 400, (large[index]?.y ?? 0) / 800);
  }
});
