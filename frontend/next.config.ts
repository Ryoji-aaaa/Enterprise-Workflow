import type { NextConfig } from "next";

const workspacePaths = [
  "/top",
  "/ui-samples",
  "/expenses/:path*",
  "/approvals/:path*",
  "/organization-chart",
  "/admin/:path*",
  "/document-intelligence/:path*",
  "/content-understanding/:path*",
];

const nextConfig: NextConfig = {
  output: "standalone",
  async headers() {
    return workspacePaths.map((source) => ({
      source,
      headers: [{ key: "Cache-Control", value: "no-store" }],
    }));
  },
};

export default nextConfig;
