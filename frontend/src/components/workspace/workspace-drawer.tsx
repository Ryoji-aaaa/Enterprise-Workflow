"use client";

import {
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
} from "@/components/ui/sheet";

import { WorkspaceNavigationContent } from "./workspace-navigation-content";

export function WorkspaceDrawer({ onNavigate }: { onNavigate: () => void }) {
  return (
    <SheetContent
      className="w-[15rem] bg-sidebar p-0 text-sidebar-foreground sm:max-w-[15rem]"
      side="left"
    >
      <SheetHeader className="border-b p-4 pr-12">
        <SheetTitle>ワークスペースメニュー</SheetTitle>
        <SheetDescription className="sr-only">
          ワークスペース内のページへ移動します。
        </SheetDescription>
      </SheetHeader>
      <WorkspaceNavigationContent onNavigate={onNavigate} />
    </SheetContent>
  );
}
