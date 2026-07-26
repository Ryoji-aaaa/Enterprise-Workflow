import type { Metadata } from "next";

import "@/app/globals.css";

export const metadata: Metadata = {
  title: "ワークフローシステム",
  description: "社内ワークフローシステム",
};

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html lang="ja">
      <body>{children}</body>
    </html>
  );
}
