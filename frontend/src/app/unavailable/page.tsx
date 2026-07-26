import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { CircleX } from "lucide-react";

import { AccountStatusCard } from "@/components/account-status-card";
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
    <AccountStatusCard
      description="このアカウントではワークフローアプリを利用できません。"
      icon={CircleX}
      title="利用できないアカウントです"
    />
  );
}
