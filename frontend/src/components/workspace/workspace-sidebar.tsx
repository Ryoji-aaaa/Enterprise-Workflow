import { WorkspaceNavigationContent } from "./workspace-navigation-content";

export function WorkspaceSidebar() {
  return (
    <aside
      aria-label="サイドメニュー"
      className="hidden border-r bg-sidebar md:flex md:flex-col"
    >
      <WorkspaceNavigationContent />
    </aside>
  );
}
