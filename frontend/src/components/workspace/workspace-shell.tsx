"use client";

import { useEffect, useState, type ReactNode } from "react";
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

  if (drawerState.pathname !== pathname) {
    setDrawerState({ pathname, open: false });
  }

  useEffect(() => {
    if (layoutMode !== "navigation-oriented") {
      return;
    }

    const desktopMediaQuery = window.matchMedia("(min-width: 48rem)");
    const closeDrawerOnDesktop = (event: MediaQueryListEvent) => {
      if (event.matches) {
        setDrawerState({ pathname, open: false });
      }
    };

    desktopMediaQuery.addEventListener("change", closeDrawerOnDesktop);
    return () => desktopMediaQuery.removeEventListener("change", closeDrawerOnDesktop);
  }, [layoutMode, pathname]);

  function setDrawerOpen(open: boolean) {
    setDrawerState({ pathname, open });
  }

  return (
    <Sheet onOpenChange={setDrawerOpen} open={drawerState.open}>
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
