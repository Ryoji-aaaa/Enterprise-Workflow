"use client";

import { useEffect, useState, type ReactNode } from "react";
import { useRouter } from "next/navigation";

import { Card, CardContent } from "@/components/ui/card";
import type { CurrentUser } from "@/lib/backend-api";
import { AuthenticationRequiredError, fetchBackend } from "@/lib/backend-browser-client";

import { CurrentUserProvider } from "./current-user-context";
import { WorkspaceShell } from "./workspace-shell";

type State =
  | { kind: "loading" }
  | { kind: "success"; user: CurrentUser }
  | { kind: "unavailable" };

type ErrorBody = {
  code?: string;
};

function WorkspaceGateMessage({ children }: { children: ReactNode }) {
  return (
    <main className="grid min-h-svh place-items-center bg-muted/30 p-4">
      <Card className="w-full max-w-md shadow-sm">
        <CardContent>{children}</CardContent>
      </Card>
    </main>
  );
}

export function WorkspaceGate({ children }: { children: ReactNode }) {
  const router = useRouter();
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();

    async function load() {
      try {
        const response = await fetchBackend("/api/backend/me", {
          cache: "no-store",
          signal: controller.signal,
        });

        if (response.ok) {
          const user = (await response.json()) as CurrentUser;
          if (!controller.signal.aborted) {
            setState({ kind: "success", user });
          }
          return;
        }

        const error = (await response.json().catch(() => ({}))) as ErrorBody;
        if (error.code === "APPLICATION_USER_NOT_REGISTERED") {
          router.replace("/unregistered");
        } else if (response.status === 403) {
          router.replace("/unavailable");
        } else if (!controller.signal.aborted) {
          setState({ kind: "unavailable" });
        }
      } catch (cause) {
        if (!controller.signal.aborted && !(cause instanceof AuthenticationRequiredError)) {
          setState({ kind: "unavailable" });
        }
      }
    }

    void load();
    return () => controller.abort();
  }, [router]);

  if (state.kind === "loading") {
    return (
      <WorkspaceGateMessage>
        <p>利用者情報を取得しています…</p>
      </WorkspaceGateMessage>
    );
  }

  if (state.kind === "unavailable") {
    return (
      <WorkspaceGateMessage>
        <h1 className="text-base font-semibold">一時的に利用できません</h1>
        <p className="mt-2 text-sm text-muted-foreground">
          しばらくしてから再度お試しください。
        </p>
      </WorkspaceGateMessage>
    );
  }

  return (
    <CurrentUserProvider user={state.user}>
      <WorkspaceShell>{children}</WorkspaceShell>
    </CurrentUserProvider>
  );
}
