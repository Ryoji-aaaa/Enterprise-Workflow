import { LogOut } from "lucide-react";

import { Button } from "@/components/ui/button";

export function LogoutForm({ compact = false }: { compact?: boolean }) {
  return (
    <form
      action="/api/auth/logout"
      className={compact ? undefined : "w-full"}
      method="post"
    >
      <Button
        className={compact ? "text-muted-foreground" : "mt-5 w-full"}
        size={compact ? "icon-lg" : "lg"}
        title={compact ? "ログアウト" : undefined}
        type="submit"
        variant={compact ? "ghost" : "default"}
      >
        {compact ? <LogOut /> : "ログアウト"}
        {compact ? <span className="sr-only">ログアウト</span> : null}
      </Button>
    </form>
  );
}
