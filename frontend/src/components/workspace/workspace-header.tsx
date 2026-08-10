"use client";

import { Bell, ChevronDown, CircleHelp, Search } from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";

import { LogoutForm } from "@/components/logout-form";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { WorkflowLogo } from "@/components/workflow-logo";
import { cn } from "@/lib/utils";

import { useCurrentUser } from "./current-user-context";
import {
  getVisibleWorkspaceNavigationItems,
  isWorkspaceNavigationItemActive,
} from "./workspace-navigation";

export function WorkspaceHeader() {
  const user = useCurrentUser();
  const pathname = usePathname();
  const initials = user.displayName.trim().slice(0, 2) || "仮";
  const navigationItems = getVisibleWorkspaceNavigationItems(user);

  return (
    <header className="sticky top-0 z-30 flex h-16 items-center border-b bg-background/95 px-4 backdrop-blur md:px-6">
      <div className="flex min-w-0 items-center gap-3 md:w-60">
        <WorkflowLogo className="size-9 shrink-0" />
        <span className="hidden truncate font-heading text-base font-semibold sm:block">
          モック文字１
        </span>
      </div>

      <nav
        aria-label="モバイルナビゲーション"
        className="ml-2 flex min-w-0 flex-1 items-center gap-1 overflow-x-auto overscroll-x-contain md:hidden"
      >
        {navigationItems.map((item) => {
          const Icon = item.icon;
          const active = isWorkspaceNavigationItemActive(pathname, item);
          return (
            <Button
              aria-label={item.label}
              className={cn(active && "bg-muted text-foreground")}
              key={item.href}
              render={(
                <Link
                  aria-current={active ? "page" : undefined}
                  href={item.href}
                />
              )}
              size="icon-lg"
              title={item.label}
              variant="ghost"
            >
              <Icon />
            </Button>
          );
        })}
      </nav>

      <label className="relative mx-4 hidden max-w-xl flex-1 items-center lg:flex">
        <Search className="pointer-events-none absolute left-3 z-10 size-4 text-muted-foreground" />
        <Input
          className="h-9 bg-muted/30 pl-9"
          placeholder="サンプル文字列１"
          type="search"
        />
      </label>

      <div className="ml-auto flex shrink-0 items-center gap-1 sm:gap-2">
        <Button
          aria-label="サンプル操作２"
          className="text-muted-foreground"
          size="icon-lg"
          type="button"
          variant="ghost"
        >
          <Bell />
        </Button>
        <Button
          aria-label="サンプル操作３"
          className="hidden text-muted-foreground sm:inline-flex"
          size="icon-lg"
          type="button"
          variant="ghost"
        >
          <CircleHelp />
        </Button>
        <Separator
          className="mx-1 hidden h-7 sm:block"
          orientation="vertical"
        />
        <div className="flex min-w-0 items-center gap-2 rounded-lg py-1 pl-1 pr-2">
          <Avatar>
            <AvatarFallback className="bg-primary/10 text-xs font-semibold text-primary">
              {initials}
            </AvatarFallback>
          </Avatar>
          <div className="hidden min-w-0 text-left sm:block">
            <p className="max-w-36 truncate text-sm font-medium">
              {user.displayName}
            </p>
            <p className="text-[11px] text-muted-foreground">
              サンプル文字列２
            </p>
          </div>
          <ChevronDown className="hidden size-4 text-muted-foreground sm:block" />
        </div>
        <LogoutForm compact />
      </div>
    </header>
  );
}
