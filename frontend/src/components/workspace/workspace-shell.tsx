"use client";

import type { ReactNode } from "react";

import { WorkspaceHeader } from "./workspace-header";
import { WorkspaceSidebar } from "./workspace-sidebar";

export function WorkspaceShell({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-svh bg-muted/30 text-foreground">
      <WorkspaceHeader />
      <div className="grid min-h-[calc(100svh-4rem)] md:grid-cols-[15rem_minmax(0,1fr)]">
        <WorkspaceSidebar />
        <div className="min-w-0">
          {children}
        </div>
      </div>
    </div>
  );
}
