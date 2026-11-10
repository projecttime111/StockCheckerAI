import assert from "node:assert/strict";
import test, { after, afterEach } from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import ts from "typescript";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "https://stockpilot.example/status" });
Object.assign(globalThis, { window: dom.window, document: dom.window.document, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true });
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
const { render, cleanup, act, waitFor } = await import("@testing-library/react");
const require = createRequire(import.meta.url), React = require("react");
let visibility = "visible", networkOnline = true, now = 1_000_000, pendingBackend = false;
const calls = [], monitors = [];
Object.defineProperty(document, "visibilityState", { configurable: true, get: () => visibility });
Object.defineProperty(navigator, "onLine", { configurable: true, get: () => networkOnline });

function compile(path, resolveImport) {
  const module = { exports: {} };
  const source = ts.transpileModule(readFileSync(new URL(`../${path}`, import.meta.url), "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true, target: ts.ScriptTarget.ES2022 } }).outputText;
  new Function("require", "module", "exports", source)(resolveImport, module, module.exports);
  return module.exports;
}
const core = compile("lib/serviceHealthClient.ts", require);
function mockFetch(url, options) {
  const backend = new URL(url, window.location.origin).pathname === "/actuator/health/liveness";
  const call = { url: String(url), options, backend, aborted: false }; calls.push(call);
  if (backend && pendingBackend) return new Promise((_resolve, reject) => {
    const abort = () => { call.aborted = true; reject(new DOMException("Aborted", "AbortError")); };
    options.signal.addEventListener("abort", abort, { once: true });
    if (options.signal.aborted) abort();
  });
  return Promise.resolve(new Response(JSON.stringify(backend ? { status: "UP" } : { status: "UP", service: "stockpilot-web" }), { status: 200, headers: { "content-type": "application/json" } }));
}
function createProbe() {
  const { useServiceHealth } = compile("lib/serviceHealth.ts", name => {
    if (name === "./api") return { API_URL: "https://api.stockpilot.example", DEMO_MODE: false };
    if (name === "./serviceHealthClient") return { ...core, createServiceHealthMonitor: options => {
      const monitor = core.createServiceHealthMonitor({ ...options, fetcher: mockFetch, now: () => now, frontendTimeoutMs: 200, backendTimeoutMs: 500, attemptTimeoutMs: 200, waitingAfterMs: 10, retryDelayMs: 10, staleAfterMs: 10_000 });
      monitors.push(monitor); return monitor;
    } };
    return require(name);
  });
  return function Probe({ name = "probe" }) { const state = useServiceHealth(); return React.createElement("output", { "data-testid": name }, `${state.frontend.status}/${state.backend.status}/${state.checking ? "checking" : "idle"}`); };
}
async function event(target, type, update = () => {}) { await act(async () => { update(); target.dispatchEvent(new window.Event(type)); }); }
afterEach(async () => {
  cleanup(); monitors.splice(0).forEach(monitor => monitor.dispose());
  await Promise.resolve(); calls.length = 0; visibility = "visible"; networkOnline = true; now = 1_000_000; pendingBackend = false;
});
after(() => dom.window.close());

test("multiple mounted hook subscribers and StrictMode share one frontend/backend check", async () => {
  const Probe = createProbe();
  const ui = render(React.createElement(React.StrictMode, null, React.createElement(Probe, { name: "one" }), React.createElement(Probe, { name: "two" })));
  await waitFor(() => assert.equal(ui.getByTestId("one").textContent, "online/online/idle"));
  assert.equal(ui.getByTestId("two").textContent, "online/online/idle");
  assert.equal(calls.length, 2); assert.equal(calls.filter(call => call.backend).length, 1);
  assert.ok(calls.every(call => call.options.credentials === "omit" && call.options.cache === "no-store"));
  await event(window, "focus"); assert.equal(calls.length, 2);
});

test("hidden tabs pause pending HTTP and a hidden online event cannot wake the backend", async () => {
  pendingBackend = true; const Probe = createProbe(); const ui = render(React.createElement(Probe));
  await waitFor(() => assert.equal(calls.length, 2));
  await event(document, "visibilitychange", () => { visibility = "hidden"; });
  assert.equal(calls.find(call => call.backend).aborted, true);
  assert.equal(ui.getByTestId("probe").textContent, "unknown/unknown/idle");
  await event(window, "online"); await event(window, "focus");
  assert.equal(calls.length, 2);
  pendingBackend = false;
  await event(document, "visibilitychange", () => { visibility = "visible"; });
  await waitFor(() => assert.equal(ui.getByTestId("probe").textContent, "online/online/idle"));
  assert.equal(calls.length, 4);
});

test("offline events update both observations and reconnect only when the tab is visible", async () => {
  const Probe = createProbe(); const ui = render(React.createElement(Probe));
  await waitFor(() => assert.equal(ui.getByTestId("probe").textContent, "online/online/idle"));
  await event(window, "offline", () => { networkOnline = false; });
  assert.equal(ui.getByTestId("probe").textContent, "offline/offline/idle");
  await event(document, "visibilitychange", () => { visibility = "hidden"; });
  await event(window, "online", () => { networkOnline = true; }); assert.equal(calls.length, 2);
  await event(document, "visibilitychange", () => { visibility = "visible"; });
  await waitFor(() => assert.equal(ui.getByTestId("probe").textContent, "online/online/idle")); assert.equal(calls.length, 4);
});

test("last observer cleanup removes browser listeners and remount refreshes stale observations", async () => {
  const Probe = createProbe(); const ui = render(React.createElement(Probe));
  await waitFor(() => assert.equal(ui.getByTestId("probe").textContent, "online/online/idle"));
  ui.unmount(); const monitor = monitors[0];
  await event(window, "offline", () => { networkOnline = false; });
  assert.equal(monitor.getSnapshot().backend.status, "online");
  await event(window, "online", () => { networkOnline = true; }); assert.equal(calls.length, 2);
  now += 180_000;
  const remounted = render(React.createElement(Probe));
  await waitFor(() => assert.equal(calls.length, 4));
  await waitFor(() => assert.equal(remounted.getByTestId("probe").textContent, "online/online/idle"));
});

test("a visible first observer resumes a check paused before the previous observer unmounted", async () => {
  pendingBackend = true; const Probe = createProbe(); const ui = render(React.createElement(Probe));
  await waitFor(() => assert.equal(calls.length, 2));
  await event(document, "visibilitychange", () => { visibility = "hidden"; }); ui.unmount();
  visibility = "visible"; pendingBackend = false;
  const remounted = render(React.createElement(Probe));
  await waitFor(() => assert.equal(remounted.getByTestId("probe").textContent, "online/online/idle")); assert.equal(calls.length, 4);
});
