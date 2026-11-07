import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import ts from "typescript";

function client(fetcher) {
  const storage = new Map([["stockpilot.token", "tenant-a"]]);
  const events = [];
  const redirects = [];
  const window = { localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }, dispatchEvent: event => events.push(event.type), location: { pathname: "/reports", search: "", replace: url => redirects.push(url) } };
  const module = { exports: {} };
  const source = ts.transpileModule(readFileSync(new URL("../lib/api.ts", import.meta.url), "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  new Function("require", "module", "exports", "window", "fetch", "process", source)(() => ({ demoApi() {} }), module, module.exports, window, fetcher, { env: { NEXT_PUBLIC_DEMO_MODE: "false" } });
  return { ...module.exports, storage, events, redirects };
}

test("cross-tab account change blocks the next write before it can use another tenant token", async () => {
  let requests = 0;
  const api = client(async () => { requests++; return new Response("{}"); });
  await api.api("/api/products");
  api.storage.set("stockpilot.token", "tenant-b");
  api.observeSessionChange({ key: "stockpilot.token" });
  await assert.rejects(api.api("/api/products", { method: "POST", body: "{}" }), { code: "SESSION_CHANGED" });
  assert.equal(requests, 1);
  assert.ok(api.events.includes("stockpilot:session-changed"));
  assert.equal(api.storage.get("stockpilot.token"), "tenant-b");
});

test("a stalled write has a deadline and warns that completion is unknown", async () => {
  const api = client((_url, init) => new Promise((_resolve, reject) => init.signal.addEventListener("abort", () => reject(new DOMException("Aborted", "AbortError")))));
  await assert.rejects(api.api("/api/imports/batch/commit", { method: "POST", timeoutMs: 10 }), error => error.code === "REQUEST_TIMEOUT" && /may still have completed/.test(error.message));
  assert.deepEqual(api.getRequestActivity(), { pending: 0, writes: 0 });
});

test("request activity remains busy until all concurrent requests finish, including rejected writes", async () => {
  const finish = [], snapshots = [];
  const api = client(() => new Promise(resolve => finish.push(resolve)));
  const unsubscribe = api.subscribeRequestActivity(() => snapshots.push(api.getRequestActivity()));
  const read = api.api("/api/products");
  const write = api.api("/api/products", { method: "POST", body: "{}" });
  assert.deepEqual(api.getRequestActivity(), { pending: 2, writes: 1 });
  finish[0](new Response("{}")); await read;
  assert.deepEqual(api.getRequestActivity(), { pending: 1, writes: 1 });
  finish[1](new Response('{"message":"Validation failed"}', { status: 400 }));
  await assert.rejects(write, { status: 400 });
  assert.deepEqual(api.getRequestActivity(), { pending: 0, writes: 0 });
  assert.deepEqual(snapshots.map(value => value.pending), [1, 2, 1, 0]);
  unsubscribe();
});

test("a successful response from an earlier account is discarded after its body finishes", async () => {
  let finishBody;
  const api = client(async () => ({
    ok: true, status: 200,
    json: () => new Promise(resolve => { finishBody = resolve; })
  }));
  const response = api.api("/api/products");
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(typeof finishBody, "function");
  api.storage.set("stockpilot.token", "tenant-b");
  finishBody({ content: [{ name: "Tenant A confidential product" }] });
  await assert.rejects(response, { code: "SESSION_CHANGED" });
  assert.ok(api.events.includes("stockpilot:session-changed"));
  assert.equal(api.storage.get("stockpilot.token"), "tenant-b");
});

test("CSV exports share expired-session handling", async () => {
  const api = client(async () => new Response(JSON.stringify({ message: "Expired" }), { status: 401 }));
  await assert.rejects(api.apiText("/api/reports/export/sales?format=csv"), { status: 401 });
  assert.equal(api.storage.size, 0);
  assert.equal(api.redirects[0], "/login?expired=1&next=%2Freports");
});

test("money keeps paise and workspace currency, and dates use the local calendar", () => {
  const api = client();
  assert.match(api.money(100.5, "USD"), /100\.50/);
  assert.doesNotMatch(api.money(100.5, "USD"), /₹/);
  assert.equal(api.money(null), "—");
  const oldTimezone = process.env.TZ;
  process.env.TZ = "Asia/Kolkata";
  try {
    const now = new Date("2026-11-06T00:15:00+05:30");
    assert.equal(api.localDate(0, now), "2026-11-06");
    assert.equal(api.localDate(1, now), "2026-11-07");
    assert.equal(api.localDate(1, new Date("2026-12-31T23:00:00+05:30")), "2027-01-01");
  } finally { if (oldTimezone === undefined) delete process.env.TZ; else process.env.TZ = oldTimezone; }
});
