import assert from "node:assert/strict";
import test from "node:test";

import type { CurrentUser } from "../../lib/backend-api.ts";

import {
  getActiveWorkspaceNavigationItem,
  getVisibleWorkspaceNavigationItems,
  isWorkspaceNavigationItemActive,
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
  ],
  features: { mailNotificationHistory: true },
};

function item(href: string) {
  const navigationItem = workspaceNavigationItems.find((value) => value.href === href);
  assert.ok(navigationItem);
  return navigationItem;
}

test("権限に応じたワークスペースメニューだけを表示する", () => {
  const labels = getVisibleWorkspaceNavigationItems(currentUser).map((value) => value.label);

  assert.deepEqual(labels, ["トップ", "経費申請", "組織図"]);
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

  assert.deepEqual(labels, ["トップ", "経費申請", "承認待ち", "組織図", "ユーザー管理"]);
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
    features: { mailNotificationHistory: false },
  }).some((value) => value.href === "/admin/mail-notifications"), false);
});

test("詳細画面と編集画面では親メニューをアクティブにする", () => {
  assert.equal(isWorkspaceNavigationItemActive("/expenses/123", item("/expenses")), true);
  assert.equal(isWorkspaceNavigationItemActive("/expenses/123/edit", item("/expenses")), true);
  assert.equal(isWorkspaceNavigationItemActive("/approvals/123", item("/approvals")), true);
  assert.equal(isWorkspaceNavigationItemActive("/admin/users/123/edit", item("/admin/users")), true);
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
