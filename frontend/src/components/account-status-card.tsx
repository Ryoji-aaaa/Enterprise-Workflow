import type { LucideIcon } from "lucide-react";

import { LogoutForm } from "@/components/logout-form";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

export function AccountStatusCard({
  description,
  detail,
  icon: Icon,
  title,
}: {
  description: string;
  detail?: string;
  icon: LucideIcon;
  title: string;
}) {
  return (
    <main className="flex min-h-svh items-center justify-center bg-muted/30 p-4">
      <Card className="w-full max-w-md shadow-sm">
        <CardHeader className="items-center text-center">
          <div className="mb-2 grid size-12 place-items-center rounded-full bg-primary/10 text-primary">
            <Icon className="size-6" />
          </div>
          <CardTitle>
            <h1>{title}</h1>
          </CardTitle>
          <CardDescription>{description}</CardDescription>
        </CardHeader>
        {detail ? (
          <CardContent>
            <div className="rounded-lg bg-muted px-3 py-2.5 text-center text-xs/relaxed text-muted-foreground">
              {detail}
            </div>
          </CardContent>
        ) : null}
        <CardFooter>
          <LogoutForm />
        </CardFooter>
      </Card>
    </main>
  );
}
