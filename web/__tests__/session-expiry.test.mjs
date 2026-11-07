import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import ts from "typescript";

function client(env = {}) {
  const values = new Map([["stockpilot.token", "old-token"], ["stockpilot.role", "OWNER"], ["stockpilot.permissions", '["reports.export"]']]);
  const redirects = [];
  const events = [];
  const window = {
    localStorage: { getItem: (key) => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: (key) => values.delete(key) },
    location: { origin: "http://localhost:3000", pathname: "/imports/smart", search: "?session=abc", replace: (url) => redirects.push(url) },
    dispatchEvent: (event) => events.push(event.type)
  };
  let respond = async () => new Response(JSON.stringify({ message: "Expired", error: "INVALID_TOKEN" }), { status: 401 });
  const source = readFileSync(new URL("../lib/api.ts", import.meta.url), "utf8");
  const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  new Function("require", "module", "exports", "window", "fetch", "process", output)(
    () => ({ demoApi: () => { throw new Error("Demo should not be called"); } }), module, module.exports,
    window, (...args) => respond(...args), { env: { NEXT_PUBLIC_DEMO_MODE: "false", ...env } }
  );
  return { ...module.exports, values, redirects, events, window, respond: (callback) => { respond = callback; } };
}

test("production API configuration and session-expiry handling coexist", async () => {
  const api = client({ NODE_ENV: "production", VERCEL: "1", NEXT_PUBLIC_API_URL: " https://api.example.test/// " });
  assert.equal(api.API_URL, "https://api.example.test");
  let requestedUrl;
  api.respond(async (url) => {
    requestedUrl = url;
    return new Response(JSON.stringify({ message: "Expired", error: "INVALID_TOKEN" }), { status: 401 });
  });
  await assert.rejects(api.api("/api/products"), { status: 401 });
  assert.equal(requestedUrl, "https://api.example.test/api/products");
  assert.equal(api.values.size, 0);
  assert.deepEqual(api.events, ["stockpilot:session-cleared"]);
  assert.deepEqual(api.redirects, ["/login?expired=1&next=%2Fimports%2Fsmart%3Fsession%3Dabc"]);
});

test("production API validation rejects missing configuration and insecure Vercel URLs", () => {
  assert.throws(() => client({ NODE_ENV: "production" }), /NEXT_PUBLIC_API_URL is required/);
  assert.throws(() => client({ NODE_ENV: "production", NEXT_PUBLIC_API_URL: "   " }), /NEXT_PUBLIC_API_URL is required/);
  assert.throws(() => client({ NODE_ENV: "production", VERCEL: "1", NEXT_PUBLIC_API_URL: "http://api.example.test" }), /must use HTTPS/);
});

test("F10 authenticated 401 clears credentials, emits cache-clear event, and redirects with destination", async () => {
  const api = client();
  await assert.rejects(api.api("/api/products"), { status: 401 });
  assert.equal(api.values.size, 0);
  assert.deepEqual(api.events, ["stockpilot:session-cleared"]);
  assert.deepEqual(api.redirects, ["/login?expired=1&next=%2Fimports%2Fsmart%3Fsession%3Dabc"]);
});

test("F10 a late 401 from a previous token cannot clear a new login", async () => {
  const api = client();
  api.respond(async () => {
    api.setSession("new-token", "VIEWER", ["dashboard.view"]);
    return new Response("{}", { status: 401 });
  });
  await assert.rejects(api.api("/api/products"), { status: 401 });
  assert.equal(api.getToken(), "new-token");
  assert.deepEqual(api.redirects, []);
});

test("F10 bad login credentials do not trigger session-expired navigation", async () => {
  const api = client();
  api.values.clear();
  await assert.rejects(api.api("/api/auth/login", { method: "POST", body: "{}" }), { status: 401 });
  assert.deepEqual(api.redirects, []);
  assert.deepEqual(api.events, []);
});

test("F10 replacing a session cannot retain the previous role or permissions", () => {
  const api = client();
  api.setSession("account-only-token");
  assert.equal(api.getSessionRole(), null);
  assert.deepEqual(api.getSessionPermissions(), []);
});

test("F10 login destination permits local routes but rejects external and login-loop destinations", () => {
  const api = client();
  assert.equal(api.loginDestination("OWNER", "?next=%2Fimports%2Fsmart%3Fsession%3Dabc"), "/imports/smart?session=abc");
  for (const next of ["https://attacker.invalid", "//attacker.invalid", "/\\attacker.invalid", "/login", "/login?next=/login"]) {
    assert.equal(api.loginDestination("OWNER", `?next=${encodeURIComponent(next)}`), "/dashboard");
  }
  assert.equal(api.loginDestination("CUSTOMER_USER", "?next=/dashboard"), "/portal");
});

test("platform and invitation destinations preserve separate entry points", () => {
  const api = client();
  assert.equal(api.loginDestination("PLATFORM_SUPER_ADMIN", "?next=/products"), "/admin");
  assert.equal(api.loginDestination("PLATFORM_SUPPORT", "?next=/admin/imports"), "/admin/imports");
  assert.equal(api.loginDestination("OWNER", "?next=/admin"), "/dashboard");
  for (const next of ["/sign-in", "/sign-up", "/auth/complete"]) assert.equal(api.loginDestination("OWNER", `?next=${next}`), "/dashboard");
  assert.equal(api.invitationDestination("?next=%2Fteam%2Faccept%3Ftoken%3Dinvite-token"), "/team/accept?token=invite-token");
  for (const next of ["//other.test/team/accept?token=x", "/team/accept", "/products?token=x", "/\\other.test/team/accept?token=x"]) assert.equal(api.invitationDestination(`?next=${encodeURIComponent(next)}`), null);
});

test("Clerk retrieves a fresh token for each request without using stored local credentials", async () => {
  const api = client({ NEXT_PUBLIC_CLERK_ENABLED: "true" });
  let generation = 0; const seen = [];
  api.configureClerkSession({ identity: "clerk:user:orgA", getToken: async () => `jwt-${++generation}`, signOut: async () => {} });
  api.respond(async (url, init) => { seen.push([init.headers.get("Authorization"), init.headers.get("X-StockPilot-Mode")]); return new Response("{}"); });
  await api.api("/api/products"); await api.api("/api/products");
  assert.deepEqual(seen, [["Bearer jwt-1", "workspace"], ["Bearer jwt-2", "workspace"]]);
  assert.equal(api.values.get("stockpilot.token"), "old-token");
});

test("Clerk late response from a previous organization is discarded without forcing a new login", async () => {
  const api = client({ NEXT_PUBLIC_CLERK_ENABLED: "true" });
  api.configureClerkSession({ identity: "clerk:user:orgA", getToken: async () => "orgA-jwt", signOut: async () => {} });
  api.respond(async () => { api.configureClerkSession({ identity: "clerk:user:orgB", getToken: async () => "orgB-jwt", signOut: async () => {} }); return new Response('{"products":["private A"]}'); });
  await assert.rejects(api.api("/api/products"), { status: 409, code: "SESSION_CHANGED" });
  assert.equal(api.getToken(), "clerk:user:orgB");
  assert.ok(api.events.every(event => event === "stockpilot:workspace-changed"));
  assert.deepEqual(api.redirects, []);
});

test("Clerk does not send a request if the organization switches while fetching its token", async () => {
  const api = client({ NEXT_PUBLIC_CLERK_ENABLED: "true" }); let called = false;
  api.configureClerkSession({ identity: "clerk:user:orgA", getToken: async () => {
    api.configureClerkSession({ identity: "clerk:user:orgB", getToken: async () => "orgB-jwt", signOut: async () => {} }); return "orgA-jwt";
  }, signOut: async () => {} });
  api.respond(async () => { called = true; return new Response("{}"); });
  await assert.rejects(api.api("/api/products"), { status: 409 });
  assert.equal(called, false);
});

test("platform calls explicitly select platform mode and dual-proof linking retains its local bearer", async () => {
  const api = client({ NEXT_PUBLIC_CLERK_ENABLED: "true" }); const seen = []; let tokens = 0;
  api.configureClerkSession({ identity: "clerk:user:orgA", getToken: async () => { tokens++; return "clerk-jwt"; }, signOut: async () => {} });
  api.respond(async (url, init) => { seen.push([init.headers.get("Authorization"), init.headers.get("X-StockPilot-Mode")]); return new Response("{}"); });
  await api.api("/api/platform/overview");
  await api.api("/api/auth/clerk/link", { method: "POST", headers: { Authorization: "Bearer local-proof", "X-StockPilot-Mode": "auto" }, body: '{"clerkToken":"clerk-proof"}' });
  assert.deepEqual(seen, [["Bearer clerk-jwt", "platform"], ["Bearer local-proof", "auto"]]);
  assert.equal(tokens, 1);
});

test("Clerk sign-in timeout is bounded and provider failures do not clear unrelated local credentials", async () => {
  const api = client({ NEXT_PUBLIC_CLERK_ENABLED: "true" }); let called = false;
  api.configureClerkSession({ identity: "clerk:user:orgA", getToken: () => new Promise(() => {}), signOut: async () => {} });
  api.respond(async () => { called = true; return new Response("{}"); });
  await assert.rejects(api.api("/api/products", { timeoutMs: 5 }), { status: 408, code: "AUTH_TIMEOUT" });
  assert.equal(called, false); assert.equal(api.values.get("stockpilot.token"), "old-token");
  assert.deepEqual(api.redirects, []);
});

test("public invitation preview and registration never attach the signed-in bearer", async () => {
  const api = client({ NEXT_PUBLIC_CLERK_ENABLED: "true" }); let calls = 0;
  api.configureClerkSession({ identity: "clerk:user:orgA", getToken: async () => { throw new Error("Should not fetch token"); }, signOut: async () => {} });
  api.respond(async (url, init) => { calls++; assert.equal(init.headers.get("Authorization"), null); return new Response("{}"); });
  await api.api("/api/auth/invitations/preview", { method: "POST", body: "{}" });
  await api.api("/api/auth/invitations/register", { method: "POST", body: "{}" });
  assert.equal(calls, 2);
});
