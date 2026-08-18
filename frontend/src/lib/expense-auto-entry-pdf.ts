export const MAX_PDF_CANVAS_PIXELS = 16_000_000;

export function getPdfCanvasOutputScale({
  viewportWidth,
  viewportHeight,
  devicePixelRatio,
  maxPixels = MAX_PDF_CANVAS_PIXELS,
}: {
  viewportWidth: number;
  viewportHeight: number;
  devicePixelRatio: number;
  maxPixels?: number;
}): number {
  const requestedOutputScale = Number.isFinite(devicePixelRatio) && devicePixelRatio > 0
    ? devicePixelRatio
    : 1;
  const viewportPixels = viewportWidth * viewportHeight;

  if (!Number.isFinite(viewportPixels) || viewportPixels <= 0 || maxPixels <= 0) {
    return requestedOutputScale;
  }

  return Math.min(requestedOutputScale, Math.sqrt(maxPixels / viewportPixels));
}
