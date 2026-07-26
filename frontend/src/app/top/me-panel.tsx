"use client";

import { useEffect, useState } from "react";

import type { CurrentUser } from "@/lib/backend-client";

type State =
  | { kind: "loading" }
  | { kind: "success"; user: CurrentUser }
  | { kind: "unregistered" | "forbidden" | "unauthenticated" | "unavailable" };

type ErrorBody = {
  code?: string;
};

function roleLabel(role: string): string {
  return role === "ADMIN" ? "管理者" : role === "USER" ? "一般ユーザー" : role;
}

export function MePanel() {
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
          setState({ kind: "unauthenticated" });
        } else if (error.code === "APPLICATION_USER_NOT_REGISTERED") {
          setState({ kind: "unregistered" });
        } else if (response.status === 403) {
          setState({ kind: "forbidden" });
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
  }, []);

  if (state.kind === "loading") {
    return <p>利用者情報を取得しています…</p>;
  }

  if (state.kind === "unregistered") {
    return (
      <>
        <h1>利用申請を受け付けました</h1>
        <p>このアカウントはワークフローアプリに登録されていません。</p>
        <p>管理者へ利用申請を通知しました。登録完了後に再度ログインしてください。</p>
      </>
    );
  }

  if (state.kind === "forbidden") {
    return (
      <>
        <h1>利用できないアカウントです</h1>
        <p>このアカウントではワークフローアプリを利用できません。</p>
      </>
    );
  }

  if (state.kind === "unauthenticated") {
    return (
      <>
        <h1>セッションの有効期限が切れました</h1>
        <p>ログアウト後、再度ログインしてください。</p>
      </>
    );
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
