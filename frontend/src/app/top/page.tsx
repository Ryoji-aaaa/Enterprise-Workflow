import { headers } from "next/headers";
import { redirect } from "next/navigation";

import { logout } from "@/app/actions";
import { MePanel } from "@/app/top/me-panel";
import { auth } from "@/lib/auth";

export const dynamic = "force-dynamic";

export default async function TopPage() {
  const session = await auth.api.getSession({
    headers: await headers(),
  });

  if (!session) {
    redirect("/login");
  }

  return (
    <main className="page">
      <section className="card">
        <MePanel />
        <form action={logout}>
          <button className="button" type="submit">ログアウト</button>
        </form>
      </section>
    </main>
  );
}
