import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import ts from "typescript";

function client() {
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
    window, (...args) => respond(...args), { env: { NEXT_PUBLIC_DEMO_MODE: "false" } }
  );
  return { ...module.exports, values, redirects, events, window, respond: (callback) => { respond = callback; } };
}

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
