export type ChartUnitNode = {
  id: string;
  parentUnitId: string | null;
  type: string;
  displayOrder: number;
};

export function buildOrganizationChartIndex<T extends ChartUnitNode>(units: T[]) {
  const childrenByParent = new Map<string | null, T[]>();
  for (const unit of units) {
    const siblings = childrenByParent.get(unit.parentUnitId) ?? [];
    siblings.push(unit);
    childrenByParent.set(unit.parentUnitId, siblings);
  }
  for (const siblings of childrenByParent.values()) {
    siblings.sort((left, right) => left.displayOrder - right.displayOrder);
  }

  const topLevel = childrenByParent.get(null) ?? [];
  return {
    childrenByParent,
    governanceUnits: topLevel.filter((unit) => unit.type === "OTHER"),
    operationalUnits: topLevel.filter((unit) => unit.type !== "OTHER"),
  };
}
