import { cloudflareTest } from "@cloudflare/vitest-pool-workers";
import { defineConfig } from "vitest/config";

// Tests run inside workerd through Miniflare: no Cloudflare account and no network.
export default defineConfig({
  plugins: [cloudflareTest({ wrangler: { configPath: "./wrangler.jsonc" } })],
  // A rate-limit test may first wait up to 10 s for a fresh limiter window (oneWindow in test/helpers.ts).
  test: { testTimeout: 20_000 },
});
