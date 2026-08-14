export type WorkspaceLayoutMode = "navigation-oriented" | "content-oriented";

export function getWorkspaceLayoutMode(pathname: string): WorkspaceLayoutMode {
  return pathname === "/top" ? "navigation-oriented" : "content-oriented";
}
