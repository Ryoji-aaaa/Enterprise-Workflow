import assert from "node:assert/strict";
import test from "node:test";

import {
  MAX_PDF_CANVAS_PIXELS,
  getPdfCanvasOutputScale,
} from "./expense-auto-entry-pdf.ts";

test("小さいviewportではDPR 1のPDF canvas output scaleを維持する", () => {
  assert.equal(getPdfCanvasOutputScale({
    viewportWidth: 1_000,
    viewportHeight: 1_000,
    devicePixelRatio: 1,
  }), 1);
});

test("pixel budget内ではRetina DPRを維持する", () => {
  assert.equal(getPdfCanvasOutputScale({
    viewportWidth: 1_000,
    viewportHeight: 1_000,
    devicePixelRatio: 2,
  }), 2);
});

test("大きいviewportではPDF canvas output scaleをpixel budgetまで制限する", () => {
  const viewportWidth = 4_000;
  const viewportHeight = 3_000;
  const outputScale = getPdfCanvasOutputScale({
    viewportWidth,
    viewportHeight,
    devicePixelRatio: 2,
  });

  assert.ok(outputScale < 2);
  assert.ok(viewportWidth * viewportHeight * outputScale ** 2 <= MAX_PDF_CANVAS_PIXELS);
});
