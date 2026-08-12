"use client";

import { useState, useSyncExternalStore } from "react";
import { LoaderCircle, LogIn } from "lucide-react";

import { Alert, AlertDescription } from "@/components/ui/alert";
import { Button } from "@/components/ui/button";
import { authClient } from "@/lib/auth-client";

const subscribe = () => () => {};
const getHydratedSnapshot = () => true;
const getServerSnapshot = () => false;

export function LoginButton() {
  const [error, setError] = useState<string>();
  const [pending, setPending] = useState(false);
  const hydrated = useSyncExternalStore(
    subscribe,
    getHydratedSnapshot,
    getServerSnapshot,
  );

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
    <div className="grid gap-3">
      <Button
        className="h-10 w-full text-sm"
        disabled={!hydrated || pending}
        onClick={login}
        type="button"
      >
        {pending ? (
          <LoaderCircle className="animate-spin" data-icon="inline-start" />
        ) : (
          <LogIn data-icon="inline-start" />
        )}
        {pending ? "Keycloakへ移動しています…" : "ログイン"}
      </Button>
      {error ? (
        <Alert variant="destructive">
          <AlertDescription>{error}</AlertDescription>
        </Alert>
      ) : null}
    </div>
  );
}
