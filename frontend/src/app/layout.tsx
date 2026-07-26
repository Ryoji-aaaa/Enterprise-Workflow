import type { Metadata } from "next";
import { Inter } from "next/font/google";

import "@/app/globals.css";
import { cn } from "@/lib/utils";

const inter = Inter({
  subsets: ["latin"],
  variable: "--font-sans",
});

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
    <html lang="ja" className={cn("font-sans", inter.variable)}>
      <body>{children}</body>
    </html>
  );
}
