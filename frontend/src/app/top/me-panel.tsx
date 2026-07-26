"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";

import type { CurrentUser } from "@/lib/backend-client";

type State =
  | { kind: "loading" }
  | { kind: "success"; user: CurrentUser }
  | { kind: "unavailable" };

type ErrorBody = {
  code?: string;
};

function roleLabel(role: string): string {
  return role === "ADMIN" ? "管理者" : role === "USER" ? "一般ユーザー" : role;
}

export function MePanel() {
  const router = useRouter();
  const [state, setState] = useState<State>({ kind: "loading" });

  useEffect(() => {
    const controller = new AbortController();

    async function load() {
      try {
        const response = await fetch("/api/backend/me", {
          cache: "no-store",
          signal: controller.signal,
        });

        if (response.ok) {
          setState({
            kind: "success",
            user: (await response.json()) as CurrentUser,
          });
          return;
        }

        const error = (await response.json()) as ErrorBody;
        if (response.status === 401) {
          router.replace("/login");
          router.refresh();
        } else if (error.code === "APPLICATION_USER_NOT_REGISTERED") {
          router.replace("/unregistered");
        } else if (response.status === 403) {
          router.replace("/unavailable");
        } else {
          setState({ kind: "unavailable" });
        }
      } catch {
        if (!controller.signal.aborted) {
          setState({ kind: "unavailable" });
        }
      }
    }

    void load();
    return () => controller.abort();
  }, [router]);

  if (state.kind === "loading") {
    return <p>利用者情報を取得しています…</p>;
  }

  if (state.kind === "unavailable") {
    return (
      <>
        <h1>一時的に利用できません</h1>
        <p>しばらくしてから再度お試しください。</p>
      </>
    );
  }

  if (state.kind !== "success") {
    return null;
  }

  const { user } = state;
  return (
    <>
      <h1>ワークフローシステム</h1>
      <p>ようこそ、{user.displayName}さん</p>
      <dl className="profile">
        <div>
          <dt>メールアドレス</dt>
          <dd>{user.email}</dd>
        </div>
        <div>
          <dt>所属</dt>
          <dd>{user.department?.name ?? "未設定"}</dd>
        </div>
        <div>
          <dt>権限</dt>
          <dd>{user.roles.map(roleLabel).join("、")}</dd>
        </div>
      </dl>
    </>
  );
}
