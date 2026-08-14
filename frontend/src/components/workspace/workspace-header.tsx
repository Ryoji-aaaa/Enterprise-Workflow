"use client";

import { Bell, ChevronDown, CircleHelp, Menu, Search } from "lucide-react";

import { LogoutForm } from "@/components/logout-form";
import { Avatar, AvatarFallback } from "@/components/ui/avatar";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { Input } from "@/components/ui/input";
import { Separator } from "@/components/ui/separator";
import { SheetTrigger } from "@/components/ui/sheet";
import { WorkflowLogo } from "@/components/workflow-logo";
import { cn } from "@/lib/utils";

import { useCurrentUser } from "./current-user-context";
import type { WorkspaceLayoutMode } from "./workspace-layout";

export function WorkspaceHeader({ layoutMode }: { layoutMode: WorkspaceLayoutMode }) {
  const user = useCurrentUser();
  const initials = user.displayName.trim().slice(0, 2) || "仮";

  return (
    <header className="sticky top-0 z-30 flex h-16 items-center border-b bg-background/95 px-4 backdrop-blur md:px-6">
      <div
        className={cn(
          "flex min-w-0 items-center gap-3",
          layoutMode === "navigation-oriented" && "md:w-60",
        )}
      >
        <SheetTrigger
          render={(
            <Button
              aria-label="メニューを開く"
              className={cn(layoutMode === "navigation-oriented" && "md:hidden")}
              size="icon-lg"
              type="button"
              variant="ghost"
            />
          )}
        >
          <Menu />
        </SheetTrigger>
        <WorkflowLogo className="size-9 shrink-0" />
        <span className="hidden truncate font-heading text-base font-semibold sm:block">
          ワークフローApp
        </span>
      </div>

      <label className="relative mx-4 hidden max-w-xl flex-1 items-center lg:flex">
        <Search className="pointer-events-none absolute left-3 z-10 size-4 text-muted-foreground" />
        <Input
          className="h-9 bg-muted/30 pl-9"
          placeholder="検索（モック）"
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
        <DropdownMenu>
          <DropdownMenuTrigger
            aria-label={`${user.displayName}のユーザー情報を表示`}
            className="flex min-w-0 items-center gap-2 rounded-lg py-1 pl-1 pr-2 text-left outline-none transition-colors hover:bg-muted focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
          >
            <Avatar>
              <AvatarFallback className="bg-primary/10 text-xs font-semibold text-primary">
                {initials}
              </AvatarFallback>
            </Avatar>
            <div className="hidden min-w-0 text-left sm:block">
              <p className="max-w-36 truncate text-sm font-medium">
                {user.displayName}
              </p>
              <p className="max-w-36 truncate text-[11px] text-muted-foreground">
                {user.department?.name ?? "所属未設定"}
              </p>
            </div>
            <ChevronDown className="hidden size-4 text-muted-foreground sm:block" />
          </DropdownMenuTrigger>
          <DropdownMenuContent
            align="end"
            aria-label="ユーザー情報"
            className="w-80 p-3"
          >
            <div className="flex items-center gap-3">
              <Avatar size="lg">
                <AvatarFallback className="bg-primary/10 font-semibold text-primary">
                  {initials}
                </AvatarFallback>
              </Avatar>
              <div className="min-w-0">
                <p className="truncate text-sm font-medium">{user.displayName}</p>
                <p className="truncate text-xs text-muted-foreground">{user.email}</p>
              </div>
            </div>
            <div className="mt-3 border-t pt-3">
              <p className="text-xs text-muted-foreground">所属部署</p>
              <p className="mt-1 text-sm font-medium">
                {user.department?.name ?? "所属未設定"}
              </p>
            </div>
          </DropdownMenuContent>
        </DropdownMenu>
        <LogoutForm compact />
      </div>
    </header>
  );
}
