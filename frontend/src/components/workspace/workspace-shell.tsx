"use client";

import { useState, type ReactNode } from "react";
import { usePathname } from "next/navigation";

import { Sheet } from "@/components/ui/sheet";
import { cn } from "@/lib/utils";

import { WorkspaceDrawer } from "./workspace-drawer";
import { WorkspaceHeader } from "./workspace-header";
import { getWorkspaceLayoutMode } from "./workspace-layout";
import { WorkspaceSidebar } from "./workspace-sidebar";

export function WorkspaceShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const layoutMode = getWorkspaceLayoutMode(pathname);
  const [drawerState, setDrawerState] = useState({ pathname, open: false });
  const drawerOpen = drawerState.pathname === pathname && drawerState.open;

  function setDrawerOpen(open: boolean) {
    setDrawerState({ pathname, open });
  }

  return (
    <Sheet onOpenChange={setDrawerOpen} open={drawerOpen}>
      <div className="min-h-svh bg-muted/30 text-foreground">
        <WorkspaceHeader layoutMode={layoutMode} />
        <div
          className={cn(
            "min-h-[calc(100svh-4rem)]",
            layoutMode === "navigation-oriented"
              && "grid md:grid-cols-[15rem_minmax(0,1fr)]",
          )}
          data-workspace-layout={layoutMode}
        >
          {layoutMode === "navigation-oriented" ? <WorkspaceSidebar /> : null}
          <div className="min-w-0">
            {children}
          </div>
        </div>
        <WorkspaceDrawer onNavigate={() => setDrawerOpen(false)} />
      </div>
    </Sheet>
  );
}
