import {
  ClipboardList,
  FileChartColumn,
  FileSearch,
  FileText,
  LayoutDashboard,
  MailCheck,
  ScanLine,
  Shapes,
  Users,
  type LucideIcon,
} from "lucide-react";

import {
  canViewMailNotificationHistory,
  canViewOrganizationChart,
  type CurrentUser,
} from "../../lib/backend-api.ts";

export type WorkspaceNavigationItem = {
  href: string;
  label: string;
  icon: LucideIcon;
  isVisible: (user: CurrentUser) => boolean;
};

export type WorkspaceMockNavigationItem = {
  label: string;
  icon: LucideIcon;
};

function hasPermission(permission: string) {
  return (user: CurrentUser) => user.permissions.includes(permission);
}

export const workspaceNavigationItems: readonly WorkspaceNavigationItem[] = [
  {
    href: "/top",
    label: "トップ",
    icon: LayoutDashboard,
    isVisible: () => true,
  },
  {
    href: "/expenses",
    label: "経費申請",
    icon: FileText,
    isVisible: hasPermission("EXPENSE_APPLICATION_READ_OWN"),
  },
  {
    href: "/approvals",
    label: "承認待ち",
    icon: ClipboardList,
    isVisible: hasPermission("EXPENSE_APPLICATION_APPROVE"),
  },
  {
    href: "/organization-chart",
    label: "組織図",
    icon: Shapes,
    isVisible: canViewOrganizationChart,
  },
  {
    href: "/admin/users",
    label: "ユーザー管理",
    icon: Users,
    isVisible: hasPermission("USER_READ"),
  },
  {
    href: "/admin/mail-notifications",
    label: "送付済メール一覧",
    icon: MailCheck,
    isVisible: canViewMailNotificationHistory,
  },
  {
    href: "/document-intelligence",
    label: "Document Intelligence",
    icon: FileSearch,
    isVisible: () => true,
  },
  {
    href: "/content-understanding",
    label: "Content Understanding",
    icon: ScanLine,
    isVisible: () => true,
  },
];

export const workspaceMockNavigationItems: readonly WorkspaceMockNavigationItem[] = [
  { label: "モック文字３", icon: FileChartColumn },
  { label: "モック文字４", icon: ClipboardList },
  { label: "モック文字５", icon: Users },
  { label: "モック文字６", icon: Shapes },
];

export function getVisibleWorkspaceNavigationItems(
  user: CurrentUser,
): WorkspaceNavigationItem[] {
  return workspaceNavigationItems.filter((item) => item.isVisible(user));
}

export function isWorkspaceNavigationItemActive(
  pathname: string,
  item: Pick<WorkspaceNavigationItem, "href">,
): boolean {
  return pathname === item.href
    || (item.href !== "/top" && pathname.startsWith(`${item.href}/`));
}

export function getActiveWorkspaceNavigationItem(
  pathname: string,
  user: CurrentUser,
): WorkspaceNavigationItem | undefined {
  return getVisibleWorkspaceNavigationItems(user).find((item) =>
    isWorkspaceNavigationItemActive(pathname, item),
  );
}
