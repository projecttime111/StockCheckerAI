import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const read = (path) => readFileSync(new URL(`../${path}`, import.meta.url), "utf8");

test("backend mode keeps demo API behind the env flag", () => {
  const api = read("lib/api.ts");
  const demoApi = read("lib/demoApi.ts");
  const login = read("app/login/page.tsx");
  const home = read("app/page.tsx");

  assert.match(api, /if \(DEMO_MODE\)/);
  assert.match(api, /return demoApi<T>\(path, init\)/);
  assert.match(demoApi, /Demo API cannot be used while Backend mode is active\./);
  assert.match(login, /DEMO_MODE \? "owner@demo\.com" : ""/);
  assert.match(login, /\{DEMO_MODE \? \(/);
  assert.match(login, /Account created successfully\. Sign in with your new credentials\./);
  assert.match(login, /params\.get\("email"\)/);
  assert.match(home, /CLERK_ENABLED \? "\/sign-in" : "\/login"/);
  assert.match(home, /CLERK_ENABLED \? "\/sign-up" : "\/register"/);
  assert.doesNotMatch(home, /redirect\(/);
});

test("production backend mode rejects a missing or insecure API URL", () => {
  const api = read("lib/api.ts");

  assert.match(api, /NODE_ENV === "production" && !DEMO_MODE/);
  assert.match(api, /NEXT_PUBLIC_API_URL is required for a production backend-mode build/);
  assert.match(api, /process\.env\.VERCEL === "1"/);
  assert.match(api, /NEXT_PUBLIC_API_URL must use HTTPS for a Vercel production backend-mode build/);
  assert.ok(api.includes('.replace(/\\/+$/, "")'));
});

test("local registration returns users to login while production keeps verification enabled", () => {
  const register = read("app/register/page.tsx");
  const localConfig = read("../backend/src/main/resources/application.yml");
  const productionConfig = read("../backend/src/main/resources/application-prod.yml");

  assert.match(register, /\/login\?registered=true&email=/);
  assert.doesNotMatch(register, /setSession\(data\.accessToken/);
  assert.match(localConfig, /APP_AUTH_REQUIRE_EMAIL_VERIFICATION:false/);
  assert.match(productionConfig, /APP_AUTH_REQUIRE_EMAIL_VERIFICATION:true/);
});

test("backend pages include clean empty states instead of demo fallback values", () => {
  const dashboard = read("app/dashboard/page.tsx");
  const forecasts = read("app/forecasts/page.tsx");
  const deadStock = read("app/dead-stock/page.tsx");
  const dataQuality = read("app/data-quality/page.tsx");
  const imports = read("app/imports/page.tsx");

  assert.match(dashboard, /No business data yet\. Import Tally\/Excel data or add your first product\./);
  assert.match(forecasts, /No forecasts yet\. Import sales history or run forecasting after adding stock movements\./);
  assert.match(deadStock, /No dead-stock items found\. Dead-stock detection will appear after inventory history exists\./);
  assert.match(dataQuality, /No data-quality issues yet\. Import or add products to begin cleanup\./);
  assert.match(imports, /No imports yet\./);

  for (const source of [dashboard, forecasts, deadStock, dataQuality, imports]) {
    assert.doesNotMatch(source, /Parle-G|Surf Excel|MAGGI|demo-tally-vouchers/);
  }
});
