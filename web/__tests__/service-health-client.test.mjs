import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import ts from "typescript";

const module = { exports: {} };
new Function("module", "exports", ts.transpileModule(readFileSync(new URL("../lib/serviceHealthClient.ts", import.meta.url), "utf8"), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
}).outputText)(module, module.exports);
const { createServiceHealthMonitor } = module.exports;
const frontend = url => url.startsWith("/api/health");
const success = url => Response.json(frontend(url) ? { status: "UP", service: "stockpilot-web" } : { status: "UP" });
const flush = async () => { for (let i = 0; i < 40; i++) await Promise.resolve(); };
function fixture(t, options = {}) {
  t.mock.timers.enable({ apis: ["setTimeout", "Date"], now: 1_000_000 });
  const monitor = createServiceHealthMonitor({ frontendUrl: "/api/health", backendUrl: "https://api.test/actuator/health/liveness", fetcher: async url => success(url), ...options });
  t.after(() => monitor.dispose());
  return monitor;
}
test("concurrent subscribers share credential-free probes and receive independent results", async t => {
  const calls = [], monitor = fixture(t, { fetcher: async (url, init) => { calls.push({ url, init }); return success(url); } });
  let notifications = 0;
  const unsubscribe = monitor.subscribe(() => notifications++);
  const first = monitor.check();
  assert.equal(monitor.check(), first);
  await first;
  assert.equal(calls.length, 2);
  for (const { url, init } of calls) {
    assert.match(url, /\?check=1000000$/);
    assert.equal(init.credentials, "omit"); assert.equal(init.cache, "no-store"); assert.equal(init.redirect, "error");
    assert.deepEqual(init.headers, { Accept: "application/json" });
  }
  assert.equal(monitor.getSnapshot().frontend.status, "online");
  assert.equal(monitor.getSnapshot().backend.status, "online");
  assert.equal(monitor.getSnapshot().checking, false);
  assert.ok(notifications >= 4);
  unsubscribe();
});
test("slow API shows waiting while frontend remains online and recovers when startup completes", async t => {
  let finish;
  const monitor = fixture(t, { fetcher: url => frontend(url) ? Promise.resolve(success(url)) : new Promise(resolve => { finish = () => resolve(success(url)); }) });
  const pending = monitor.check(); await flush();
  assert.equal(monitor.getSnapshot().frontend.status, "online");
  assert.equal(monitor.getSnapshot().backend.status, "checking");
  t.mock.timers.tick(3_000); await flush();
  assert.equal(monitor.getSnapshot().backend.status, "waiting");
  assert.equal(monitor.getSnapshot().checking, true);
  finish(); await pending;
  assert.equal(monitor.getSnapshot().backend.status, "online");
  assert.equal(monitor.getSnapshot().backend.responseTimeMs, 3_000);
});
test("Render proxy startup response retries within the bounded check", async t => {
  let attempts = 0;
  const monitor = fixture(t, { fetcher: async url => {
    if (!frontend(url) && ++attempts === 1) return new Response("Starting service", { status: 503 });
    return success(url);
  } });
  const pending = monitor.check(); await flush();
  assert.equal(monitor.getSnapshot().backend.status, "waiting");
  t.mock.timers.tick(5_000); await flush(); await pending;
  assert.equal(attempts, 2); assert.equal(monitor.getSnapshot().backend.status, "online");
});
for (const hangingBody of [false, true]) test(`unresponsive ${hangingBody ? "response body" : "network request"} cannot leave a check running forever`, async t => {
  const monitor = fixture(t, { fetcher: async url => frontend(url) ? success(url) : hangingBody
    ? { ok: true, headers: new Headers({ "content-type": "application/json" }), json: () => new Promise(() => {}) }
    : new Promise(() => {}) });
  const pending = monitor.check(); await flush();
  for (let elapsed = 0; elapsed < 90_000; elapsed += 5_000) { t.mock.timers.tick(5_000); await flush(); }
  await pending;
  assert.equal(monitor.getSnapshot().checking, false);
  assert.equal(monitor.getSnapshot().frontend.status, "online");
  assert.equal(monitor.getSnapshot().backend.status, "unavailable");
});
test("offline device makes no requests and can recover after reconnection", async t => {
  let online = false, calls = 0;
  const monitor = fixture(t, { isOnline: () => online, fetcher: async url => { calls++; return success(url); } });
  await monitor.check(); assert.equal(calls, 0); assert.equal(monitor.getSnapshot().frontend.status, "offline");
  online = true; monitor.refreshIfStale(); await flush();
  assert.equal(calls, 2); assert.equal(monitor.getSnapshot().backend.status, "online");
});
test("network loss between scheduling and starting probes releases the single-flight check", async t => {
  let online = true;
  const monitor = fixture(t, { isOnline: () => online });
  const pending = monitor.check(); online = false; await pending;
  assert.equal(monitor.getSnapshot().checking, false);
  online = true; await monitor.check();
  assert.equal(monitor.getSnapshot().backend.status, "online");
});
test("pausing aborts pending probes and late responses cannot overwrite a fresh check", async t => {
  let finishOld, calls = 0;
  const monitor = fixture(t, { fetcher: (url, init) => {
    calls++;
    if (!frontend(url) && calls === 2) return new Promise(resolve => { finishOld = () => { assert.equal(init.signal.aborted, true); resolve(Response.json({ status: "DOWN" })); }; });
    return Promise.resolve(success(url));
  } });
  const old = monitor.check(); await flush(); monitor.pause();
  assert.equal(monitor.getSnapshot().backend.status, "unknown");
  monitor.refreshIfStale(); await flush();
  assert.equal(monitor.getSnapshot().backend.status, "online");
  finishOld(); await old; await flush();
  assert.equal(monitor.getSnapshot().backend.status, "online");
});
test("a paused check with recent results resumes on return rather than staying unknown", async t => {
  let calls = 0;
  const monitor = fixture(t, { fetcher: async url => { calls++; return success(url); } });
  await monitor.check(); monitor.pause(); monitor.refreshIfStale(); await flush();
  assert.equal(calls, 4); assert.equal(monitor.getSnapshot().frontend.status, "online");
});
test("old results expire without background keep-alive requests and refresh on demand", async t => {
  let calls = 0;
  const monitor = fixture(t, { fetcher: async url => { calls++; return success(url); } });
  await monitor.check(); const checkedAt = monitor.getSnapshot().backend.checkedAt;
  t.mock.timers.tick(120_000); await flush();
  assert.equal(calls, 2); assert.equal(monitor.getSnapshot().backend.status, "unknown");
  assert.equal(monitor.getSnapshot().backend.checkedAt, checkedAt);
  monitor.refreshIfStale(); await flush();
  assert.equal(calls, 4); assert.equal(monitor.getSnapshot().backend.status, "online");
});
test("fast frontend result expires independently of the later backend result", async t => {
  let finish;
  const monitor = fixture(t, { attemptTimeoutMs: 90_000, fetcher: url => frontend(url) ? Promise.resolve(success(url)) : new Promise(resolve => { finish = () => resolve(success(url)); }) });
  const pending = monitor.check(); await flush();
  t.mock.timers.tick(60_000); finish(); await pending;
  t.mock.timers.tick(60_000); await flush();
  assert.equal(monitor.getSnapshot().frontend.status, "unknown");
  assert.equal(monitor.getSnapshot().backend.status, "online");
  t.mock.timers.tick(60_000); await flush();
  assert.equal(monitor.getSnapshot().backend.status, "unknown");
});
test("subscriber cancellation during initial notification does not retain an obsolete promise", async t => {
  let calls = 0;
  const monitor = fixture(t, { fetcher: async url => { calls++; return success(url); } });
  const unsubscribe = monitor.subscribe(() => { if (monitor.getSnapshot().checking) monitor.pause(); });
  await monitor.check(); unsubscribe();
  assert.equal(calls, 0);
  await monitor.check();
  assert.equal(calls, 2); assert.equal(monitor.getSnapshot().backend.status, "online");
});
test("demo mode never implies the backend is reachable", async t => {
  const urls = [], monitor = fixture(t, { demo: true, fetcher: async url => { urls.push(url); return success(url); } });
  await monitor.check(); assert.equal(urls.length, 1); assert.ok(frontend(urls[0]));
  assert.equal(monitor.getSnapshot().frontend.status, "online"); assert.equal(monitor.getSnapshot().backend.status, "demo");
});
test("unhealthy liveness and frontend HTML do not produce false online results or startup retries", async t => {
  let calls = 0;
  const monitor = fixture(t, { fetcher: async url => {
    calls++;
    return frontend(url) ? new Response("Sign in", { headers: { "content-type": "text/html" } }) : Response.json({ status: "DOWN" }, { status: 503 });
  } });
  await monitor.check(); assert.equal(calls, 2);
  assert.equal(monitor.getSnapshot().frontend.status, "unavailable"); assert.equal(monitor.getSnapshot().backend.status, "unavailable");
});
test("wrong frontend service and inaccessible backend endpoint give actionable errors", async t => {
  const monitor = fixture(t, { fetcher: async url => frontend(url) ? Response.json({ status: "UP", service: "different-server" }) : new Response(null, { status: 403 }) });
  await monitor.check();
  assert.equal(monitor.getSnapshot().frontend.status, "unavailable");
  assert.match(monitor.getSnapshot().backend.message, /HTTP 403/);
});
