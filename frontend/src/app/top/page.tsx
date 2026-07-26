import { headers } from "next/headers";
import { redirect } from "next/navigation";

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

  return <MePanel />;
}
