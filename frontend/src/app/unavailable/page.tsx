import { headers } from "next/headers";
import { redirect } from "next/navigation";

import { LogoutForm } from "@/components/logout-form";
import { auth } from "@/lib/auth";

export const dynamic = "force-dynamic";

export default async function UnavailablePage() {
  const session = await auth.api.getSession({
    headers: await headers(),
  });

  if (!session) {
    redirect("/login");
  }

  return (
    <main className="page">
      <section className="card">
        <h1>利用できないアカウントです</h1>
        <p>このアカウントではワークフローアプリを利用できません。</p>
        <LogoutForm />
      </section>
    </main>
  );
}
