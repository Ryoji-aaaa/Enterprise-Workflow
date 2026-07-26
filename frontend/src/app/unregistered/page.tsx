import { headers } from "next/headers";
import { redirect } from "next/navigation";
import { Clock3 } from "lucide-react";

import { AccountStatusCard } from "@/components/account-status-card";
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
    <AccountStatusCard
      description="このアカウントはワークフローアプリに登録されていません。"
      detail="管理者へ利用申請を通知しました。登録完了後に再度ログインしてください。"
      icon={Clock3}
      title="利用申請を受け付けました"
    />
  );
}
