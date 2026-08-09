import assert from "node:assert/strict";
import test from "node:test";

import type { CurrentUser } from "../../lib/backend-api.ts";

import {
  getActiveWorkspaceNavigationItem,
  getVisibleWorkspaceNavigationItems,
  isWorkspaceNavigationItemActive,
  workspaceMockNavigationItems,
  workspaceNavigationItems,
} from "./workspace-navigation.ts";

const currentUser: CurrentUser = {
  id: "00000000-0000-0000-0000-000000000001",
  externalSubject: "subject",
  email: "example.user1@sdcj.co.jp",
  displayName: "開発一般ユーザー",
  employmentType: "REGULAR_EMPLOYEE",
  department: { name: "開発部" },
  roles: ["APPLICATION_USER"],
  permissions: [
    "EXPENSE_APPLICATION_READ_OWN",
    "ORGANIZATION_CHART_READ",
    "DOCUMENT_INTELLIGENCE_ANALYZE",
    "CONTENT_UNDERSTANDING_ANALYZE",
  ],
  features: {
    mailNotificationHistory: true,
    documentIntelligence: true,
    contentUnderstanding: true,
  },
};

function item(href: string) {
  const navigationItem = workspaceNavigationItems.find((value) => value.href === href);
  assert.ok(navigationItem);
  return navigationItem;
}

test("権限に応じたワークスペースメニューだけを表示する", () => {
  const labels = getVisibleWorkspaceNavigationItems(currentUser).map((value) => value.label);

  assert.deepEqual(labels, [
    "トップ",
    "経費申請",
    "組織図",
    "Document Intelligence",
    "Content Understanding",
  ]);
});

test("承認待ちとユーザー管理は対応権限がある場合だけ表示する", () => {
  const labels = getVisibleWorkspaceNavigationItems({
    ...currentUser,
    permissions: [
      ...currentUser.permissions,
      "EXPENSE_APPLICATION_APPROVE",
      "USER_READ",
    ],
  }).map((value) => value.label);

  assert.deepEqual(labels, [
    "トップ",
    "経費申請",
    "承認待ち",
    "組織図",
    "ユーザー管理",
    "Document Intelligence",
    "Content Understanding",
  ]);
});

test("Document Analysisの2ルートを実メニューとして表示する", () => {
  const labels = getVisibleWorkspaceNavigationItems(currentUser).map((value) => value.label);

  assert.equal(labels.includes("Document Intelligence"), true);
  assert.equal(labels.includes("Content Understanding"), true);
  assert.equal(item("/document-intelligence").label, "Document Intelligence");
  assert.equal(item("/content-understanding").label, "Content Understanding");
});

test("組織図はDB権限と許可された雇用区分の両方を満たす場合だけ表示する", () => {
  assert.equal(getVisibleWorkspaceNavigationItems(currentUser).some((value) => value.href === "/organization-chart"), true);
  assert.equal(getVisibleWorkspaceNavigationItems({
    ...currentUser,
    employmentType: "ASSOCIATE_EMPLOYEE",
  }).some((value) => value.href === "/organization-chart"), true);

  for (const employmentType of ["PART_TIME", "CONTRACT_EMPLOYEE", "SYSTEM"] as const) {
    assert.equal(getVisibleWorkspaceNavigationItems({
      ...currentUser,
      employmentType,
    }).some((value) => value.href === "/organization-chart"), false);
  }

  assert.equal(getVisibleWorkspaceNavigationItems({
    ...currentUser,
    permissions: currentUser.permissions.filter((value) => value !== "ORGANIZATION_CHART_READ"),
  }).some((value) => value.href === "/organization-chart"), false);
});

test("送付済メール一覧は機能フラグとDB権限の両方を満たす場合だけ表示する", () => {
  const permitted = {
    ...currentUser,
    permissions: [...currentUser.permissions, "MAIL_NOTIFICATION_READ"],
  };

  assert.equal(getVisibleWorkspaceNavigationItems(permitted).some((value) => value.href === "/admin/mail-notifications"), true);
  assert.equal(getVisibleWorkspaceNavigationItems(currentUser).some((value) => value.href === "/admin/mail-notifications"), false);
  assert.equal(getVisibleWorkspaceNavigationItems({
    ...permitted,
    features: {
      ...permitted.features,
      mailNotificationHistory: false,
    },
  }).some((value) => value.href === "/admin/mail-notifications"), false);
});

test("Document Analysisは機能フラグとDB権限の両方を満たす場合だけ表示する", () => {
  assert.equal(getVisibleWorkspaceNavigationItems(currentUser).some((value) => value.href === "/document-intelligence"), true);
  assert.equal(getVisibleWorkspaceNavigationItems(currentUser).some((value) => value.href === "/content-understanding"), true);
  assert.equal(getVisibleWorkspaceNavigationItems({
    ...currentUser,
    features: {
      ...currentUser.features,
      documentIntelligence: false,
    },
  }).some((value) => value.href === "/document-intelligence"), false);
  assert.equal(getVisibleWorkspaceNavigationItems({
    ...currentUser,
    permissions: currentUser.permissions.filter((value) => value !== "DOCUMENT_INTELLIGENCE_ANALYZE"),
  }).some((value) => value.href === "/document-intelligence"), false);
  assert.equal(getVisibleWorkspaceNavigationItems({
    ...currentUser,
    features: {
      ...currentUser.features,
      contentUnderstanding: false,
    },
  }).some((value) => value.href === "/content-understanding"), false);
  assert.equal(getVisibleWorkspaceNavigationItems({
    ...currentUser,
    permissions: currentUser.permissions.filter((value) => value !== "CONTENT_UNDERSTANDING_ANALYZE"),
  }).some((value) => value.href === "/content-understanding"), false);
});

test("詳細画面と編集画面では親メニューをアクティブにする", () => {
  assert.equal(isWorkspaceNavigationItemActive("/expenses/123", item("/expenses")), true);
  assert.equal(isWorkspaceNavigationItemActive("/expenses/123/edit", item("/expenses")), true);
  assert.equal(isWorkspaceNavigationItemActive("/approvals/123", item("/approvals")), true);
  assert.equal(isWorkspaceNavigationItemActive("/admin/users/123/edit", item("/admin/users")), true);
  assert.equal(isWorkspaceNavigationItemActive("/document-intelligence", item("/document-intelligence")), true);
  assert.equal(isWorkspaceNavigationItemActive("/document-intelligence/runs/1", item("/document-intelligence")), true);
  assert.equal(isWorkspaceNavigationItemActive("/content-understanding", item("/content-understanding")), true);
  assert.equal(isWorkspaceNavigationItemActive("/content-understanding/runs/1", item("/content-understanding")), true);
});

test("/top は完全一致のときだけアクティブにする", () => {
  assert.equal(isWorkspaceNavigationItemActive("/top", item("/top")), true);
  assert.equal(isWorkspaceNavigationItemActive("/top/extra", item("/top")), false);
  assert.equal(isWorkspaceNavigationItemActive("/topical", item("/top")), false);
  assert.equal(isWorkspaceNavigationItemActive("/expenses", item("/top")), false);
});

test("表示可能なメニューから現在パスのアクティブ項目を取得する", () => {
  const active = getActiveWorkspaceNavigationItem("/expenses/123/edit", currentUser);

  assert.equal(active?.href, "/expenses");
  assert.equal(getActiveWorkspaceNavigationItem("/admin/users/123/edit", currentUser), undefined);
});

test("置換対象の先頭モック2件は残さず、残り4件を維持する", () => {
  assert.deepEqual(workspaceMockNavigationItems.map((value) => value.label), [
    "モック文字３",
    "モック文字４",
    "モック文字５",
    "モック文字６",
  ]);
});
