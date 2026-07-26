"use client";

import { useState } from "react";

import { authClient } from "@/lib/auth-client";

export function LoginButton() {
  const [error, setError] = useState<string>();
  const [pending, setPending] = useState(false);

  async function login() {
    setPending(true);
    setError(undefined);

    const result = await authClient.signIn.oauth2({
      providerId: "keycloak",
      callbackURL: "/top",
      errorCallbackURL: "/login?error=oauth",
    });

    if (result?.error) {
      setError("ログインを開始できませんでした。しばらくしてから再度お試しください。");
      setPending(false);
    }
  }

  return (
    <>
      <button className="button" disabled={pending} onClick={login} type="button">
        {pending ? "Keycloakへ移動しています…" : "ログイン"}
      </button>
      {error ? <p className="error" role="alert">{error}</p> : null}
    </>
  );
}
