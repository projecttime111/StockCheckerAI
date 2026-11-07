import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const settings = readFileSync(new URL("../app/settings/page.tsx", import.meta.url), "utf8");
const onboarding = readFileSync(new URL("../app/onboarding/page.tsx", import.meta.url), "utf8");

test("owner settings expose guarded lifecycle actions", () => {
  assert.match(settings, /Danger Zone/);
  assert.match(settings, /RESET WORKSPACE DATA/);
  assert.match(settings, /DELETE WORKSPACE/);
  assert.match(settings, /DELETE MY ACCOUNT/);
  assert.match(settings, /confirmation !== actionConfig\.phrase/);
  assert.match(settings, /role === "OWNER" \|\| role === "ADMIN"/);
});

test("workspace reset clears cached business data before redirect", () => {
  assert.match(settings, /queryClient\.clear\(\)/);
  assert.match(settings, /router\.push\("\/dashboard"\)/);
  assert.match(settings, /\/api\/tenants\/current\/delete-impact/);
});

test("workspace-less onboarding can create a workspace or delete the account", () => {
  assert.match(onboarding, /api<AuthResponse>\(CLERK_ENABLED \? "\/api\/auth\/clerk\/workspace" : "\/api\/tenants"/);
  assert.match(onboarding, /DELETE MY ACCOUNT/);
  assert.match(onboarding, /\/api\/account/);
});
