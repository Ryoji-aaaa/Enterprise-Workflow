import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { ShieldCheck } from "lucide-react";

import { LoginButton } from "@/app/login/login-button";
import { Alert, AlertDescription } from "@/components/ui/alert";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { WorkflowLogo } from "@/components/workflow-logo";
import { auth } from "@/lib/auth";

export const dynamic = "force-dynamic";

export default async function LoginPage({
  searchParams,
}: {
  searchParams: Promise<{ reason?: string | string[] }>;
}) {
  const reason = (await searchParams).reason;
  const sessionExpired = reason === "session-expired";
  const session = await auth.api.getSession({
    headers: await headers(),
  });

  if (session && !sessionExpired) {
    redirect("/top");
  }

  return (
    <main className="grid min-h-svh bg-background lg:grid-cols-2">
      <section className="relative hidden overflow-hidden bg-primary p-10 text-primary-foreground lg:flex lg:flex-col lg:justify-between">
        <div className="absolute -left-24 -top-24 size-80 rounded-full border border-primary-foreground/10" />
        <div className="absolute -bottom-40 -right-24 size-[32rem] rounded-full border border-primary-foreground/10" />
        <div className="relative flex items-center gap-3">
          <WorkflowLogo className="size-10" />
          <span className="font-heading text-lg font-semibold">
            ワークフローシステム
          </span>
        </div>
        <div className="relative max-w-lg">
          <p className="font-heading text-4xl font-semibold leading-tight tracking-tight">
            社内業務を、
            <br />
            ひとつの流れに。
          </p>
          <p className="mt-5 max-w-md text-sm/7 text-primary-foreground/75">
            申請から承認までを安全に管理する社内ワークフローです。
          </p>
        </div>
        <p className="relative text-xs text-primary-foreground/60">
          社内利用専用
        </p>
      </section>

      <section className="flex min-h-svh items-center justify-center bg-muted/20 p-4 sm:p-8">
        <div className="w-full max-w-sm">
          <div className="mb-8 flex flex-col items-center text-center lg:hidden">
            <WorkflowLogo className="mb-3 size-11" />
            <p className="font-heading text-lg font-semibold">
              ワークフローシステム
            </p>
          </div>

          <Card className="shadow-sm">
            <CardHeader>
              <div className="mb-2 grid size-10 place-items-center rounded-lg bg-primary/10 text-primary">
                <ShieldCheck className="size-5" />
              </div>
              <CardTitle>
                <h1>ログイン</h1>
              </CardTitle>
              <CardDescription>
                社内アカウントを使用してログインしてください。
              </CardDescription>
            </CardHeader>
            <CardContent>
              {sessionExpired ? (
                <Alert className="mb-4">
                  <AlertDescription>
                    セッションの有効期限が切れました。再度ログインしてください。
                  </AlertDescription>
                </Alert>
              ) : null}
              <LoginButton />
              <p className="mt-4 text-center text-[11px]/5 text-muted-foreground">
                認証画面へ安全に移動します。
              </p>
            </CardContent>
          </Card>
        </div>
      </section>
    </main>
  );
}
