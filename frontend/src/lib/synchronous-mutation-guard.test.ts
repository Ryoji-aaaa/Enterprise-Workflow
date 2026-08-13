import assert from "node:assert/strict";
import test from "node:test";

import { createSynchronousMutationGuard } from "./synchronous-mutation-guard.ts";

test("同一turnの二重mutationを同期的に拒否し、完了後は再実行できる", () => {
  const guard = createSynchronousMutationGuard();

  assert.equal(guard.tryStart(), true);
  assert.equal(guard.isActive(), true);
  assert.equal(guard.tryStart(), false);

  guard.finish();
  assert.equal(guard.isActive(), false);
  assert.equal(guard.tryStart(), true);
});
