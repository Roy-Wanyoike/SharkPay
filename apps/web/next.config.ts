import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  // The console is auth-gated and fully dynamic (session cookies); no static
  // export or image optimization config is needed at foundation stage.
};

export default nextConfig;
