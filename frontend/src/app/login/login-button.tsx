"use client";

import { useState, useSyncExternalStore } from "react";
import { LoaderCircle, LogIn } from "lucide-react";

import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { Separator } from "@/components/ui/separator";
import { authClient } from "@/lib/auth-client";

const GUEST_LOGIN_EMAIL = "guest00@example.com";
const GUEST_LOGIN_PASSWORD = "password";
const LOGIN_ERROR_MESSAGE =
  "ログインを開始できませんでした。しばらくしてから再度お試しください。";

type LoginType = "normal" | "guest";

const subscribe = () => () => {};
const getHydratedSnapshot = () => true;
const getServerSnapshot = () => false;

export function LoginButton() {
  const [error, setError] = useState<string>();
  const [pendingLogin, setPendingLogin] = useState<LoginType>();
  const hydrated = useSyncExternalStore(
    subscribe,
    getHydratedSnapshot,
    getServerSnapshot,
  );

  async function login() {
    setPendingLogin("normal");
    setError(undefined);

    const result = await authClient.signIn.oauth2({
      providerId: "keycloak",
      callbackURL: "/top",
      errorCallbackURL: "/login?error=oauth",
    });

    if (result?.error) {
      setError(LOGIN_ERROR_MESSAGE);
      setPendingLogin(undefined);
    }
  }

  async function guestLogin() {
    setPendingLogin("guest");
    setError(undefined);

    try {
      const result = await authClient.signIn.oauth2({
        providerId: "keycloak",
        callbackURL: "/top",
        errorCallbackURL: "/login?error=oauth",
        disableRedirect: true,
      });
      const authorizationUrl = result.data?.url;

      if (result.error || !authorizationUrl) {
        setError(LOGIN_ERROR_MESSAGE);
        setPendingLogin(undefined);
        return;
      }

      const url = new URL(authorizationUrl);
      url.searchParams.set("login_hint", GUEST_LOGIN_EMAIL);
      window.location.assign(url.toString());
    } catch {
      setError(LOGIN_ERROR_MESSAGE);
      setPendingLogin(undefined);
    }
  }

  return (
    <div className="grid gap-3">
      <Button
        className="h-10 w-full text-sm"
        disabled={!hydrated || pendingLogin !== undefined}
        onClick={login}
        type="button"
      >
        {pendingLogin === "normal" ? (
          <LoaderCircle className="animate-spin" data-icon="inline-start" />
        ) : (
          <LogIn data-icon="inline-start" />
        )}
        {pendingLogin === "normal" ? "Keycloakへ移動しています…" : "ログイン"}
      </Button>

      <div className="flex items-center gap-3" aria-label="または">
        <Separator className="flex-1" />
        <span className="text-xs text-muted-foreground">または</span>
        <Separator className="flex-1" />
      </div>

      <Button
        className="h-10 w-full text-sm"
        disabled={!hydrated || pendingLogin !== undefined}
        onClick={guestLogin}
        type="button"
        variant="outline"
      >
        {pendingLogin === "guest" ? (
          <LoaderCircle className="animate-spin" data-icon="inline-start" />
        ) : (
          <LogIn data-icon="inline-start" />
        )}
        {pendingLogin === "guest"
          ? "ゲストログインを開始しています…"
          : "ゲストログイン"}
      </Button>
      <p className="text-center text-xs text-red-800">
        <span className="text-muted-foreground">ゲストパスワード=</span>{GUEST_LOGIN_PASSWORD}
      </p>

      {error ? (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}
    </div>
  );
}
