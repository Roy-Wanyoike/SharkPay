import nextConfig from "eslint-config-next";

// eslint-config-next@16 ships a FLAT config array directly (base +
// core-web-vitals + typescript parser) — no FlatCompat needed (FlatCompat
// chokes on it: "Converting circular structure to JSON").
const eslintConfig = [
  {
    ignores: [
      "node_modules/**",
      ".next/**",
      "out/**",
      "coverage/**",
      "next-env.d.ts",
      "vitest.setup.ts",
    ],
  },
  ...nextConfig,
];

export default eslintConfig;
