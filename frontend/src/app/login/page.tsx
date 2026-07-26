import { headers } from "next/headers";
import { redirect } from "next/navigation";

import { LoginButton } from "@/app/login/login-button";
import { auth } from "@/lib/auth";

export const dynamic = "force-dynamic";

export default async function LoginPage() {
  const session = await auth.api.getSession({
    headers: await headers(),
  });

  if (session) {
    redirect("/top");
  }

  return (
    <main className="page">
      <section className="card">
        <h1>ワークフローシステム</h1>
        <p>社内アカウントでログインしてください。</p>
        <LoginButton />
      </section>
    </main>
  );
}
