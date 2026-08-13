"use client";

import { Settings } from "lucide-react";
import { usePathname } from "next/navigation";

import { Button, LinkButton } from "@/components/ui/button";
import { cn } from "@/lib/utils";

import { useCurrentUser } from "./current-user-context";
import {
  getActiveWorkspaceNavigationItem,
  getVisibleWorkspaceNavigationItems,
  workspaceMockNavigationItems,
} from "./workspace-navigation";

export function WorkspaceSidebar() {
  const user = useCurrentUser();
  const pathname = usePathname();
  const navigationItems = getVisibleWorkspaceNavigationItems(user);
  const activeItem = getActiveWorkspaceNavigationItem(pathname, user);

  return (
    <aside
      aria-label="サイドメニュー"
      className="hidden border-r bg-sidebar md:flex md:flex-col"
    >
      <nav aria-label="ワークスペースナビゲーション" className="flex-1 space-y-1 p-3">
        {navigationItems.map((item) => {
          const Icon = item.icon;
          const active = activeItem?.href === item.href;
          return (
            <LinkButton
              className={cn(
                "h-auto w-full justify-start gap-3 px-3 py-2.5 text-left text-sm",
                active
                  ? "bg-sidebar-accent font-medium text-sidebar-accent-foreground"
                  : "text-sidebar-foreground/70",
              )}
              aria-current={active ? "page" : undefined}
              href={item.href}
              key={item.href}
              variant="ghost"
            >
              <Icon className="size-4.5" />
              <span>{item.label}</span>
            </LinkButton>
          );
        })}
        {workspaceMockNavigationItems.map((item) => {
          const Icon = item.icon;
          return (
            <Button
              className="h-auto w-full justify-start gap-3 px-3 py-2.5 text-left text-sm text-sidebar-foreground/70"
              key={item.label}
              type="button"
              variant="ghost"
            >
              <Icon className="size-4.5" />
              <span>{item.label}</span>
            </Button>
          );
        })}
      </nav>
      <div className="border-t p-3">
        <Button
          className="h-auto w-full justify-start gap-3 px-3 py-2.5 text-sm text-sidebar-foreground/70"
          type="button"
          variant="ghost"
        >
          <Settings className="size-4.5" />
          <span>モック文字７</span>
        </Button>
      </div>
    </aside>
  );
}
