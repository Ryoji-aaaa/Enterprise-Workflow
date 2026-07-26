import { headers } from "next/headers";
import { redirect } from "next/navigation";

import { LogoutForm } from "@/components/logout-form";
import { auth } from "@/lib/auth";

export const dynamic = "force-dynamic";

export default async function UnregisteredPage() {
  const session = await auth.api.getSession({
    headers: await headers(),
  });

  if (!session) {
    redirect("/login");
  }

  return (
    <main className="page">
      <section className="card">
        <h1>利用申請を受け付けました</h1>
        <p>このアカウントはワークフローアプリに登録されていません。</p>
        <p>管理者へ利用申請を通知しました。登録完了後に再度ログインしてください。</p>
        <LogoutForm />
      </section>
    </main>
  );
}
