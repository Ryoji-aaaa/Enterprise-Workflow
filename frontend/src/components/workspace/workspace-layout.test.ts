import assert from "node:assert/strict";
import test from "node:test";

import { getWorkspaceLayoutMode } from "./workspace-layout.ts";

test("/topだけをnavigation-orientedとして扱う", () => {
  assert.equal(getWorkspaceLayoutMode("/top"), "navigation-oriented");
});

test("/top以外のワークスペースrouteをcontent-orientedとして扱う", () => {
  for (const pathname of [
    "/top/extra",
    "/topical",
    "/expenses",
    "/document-intelligence",
    "/content-understanding",
    "/expenses/auto-entry",
    "/ui-samples",
  ]) {
    assert.equal(getWorkspaceLayoutMode(pathname), "content-oriented");
  }
});
