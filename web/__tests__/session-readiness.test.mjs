import assert from "node:assert/strict";
import test, { after, afterEach } from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import ts from "typescript";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "https://stockpilot.test/auth/complete" });
Object.assign(globalThis, { window: dom.window, document: dom.window.document, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true });
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
const { render, cleanup, fireEvent, act, waitFor } = await import("@testing-library/react");
const require = createRequire(import.meta.url), React = require("react");
const { QueryClient, QueryClientProvider, onlineManager } = require("@tanstack/react-query");
let identity = null, reply = async () => ({ tenantId: "tenant-one" });
const calls = [], clients = [];
const events = { SESSION_CHANGED_EVENT: "stockpilot:session-changed", SESSION_CLEARED_EVENT: "stockpilot:session-cleared", WORKSPACE_CHANGED_EVENT: "stockpilot:workspace-changed" };
const api = {
  ...events,
  getSessionIdentity: () => identity,
  api: async (path, options) => {
    const call = { path, options, identity };
    calls.push(call);
    return reply(call);
  },
};
const module = { exports: {} };
const source = ts.transpileModule(readFileSync(new URL("../lib/session.tsx", import.meta.url), "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true, target: ts.ScriptTarget.ES2022 } }).outputText;
new Function("require", "module", "exports", source)(name => name === "./api" ? api : require(name), module, module.exports);
const { useSession } = module.exports;
function Probe() {
  const session = useSession("auto");
  return React.createElement("div", null,
    React.createElement("span", { "data-testid": "status" }, session.status),
    React.createElement("span", { "data-testid": "fetch-status" }, session.fetchStatus),
    React.createElement("span", { "data-testid": "tenant" }, session.data?.tenantId ?? "none"),
    session.error ? React.createElement("p", { role: "alert" }, session.error.message) : null,
    React.createElement("button", { onClick: () => void session.refetch() }, "Try again"));
}
function mount() {
  // Keep TanStack's normal retry/network defaults so useSession must override them.
  const client = new QueryClient({ defaultOptions: { queries: { gcTime: 0 } } });
  clients.push(client);
  return { ...render(React.createElement(QueryClientProvider, { client }, React.createElement(Probe))), client };
}
async function changeIdentity(next) {
  await act(async () => {
    identity = next;
    window.dispatchEvent(new dom.window.Event(events.WORKSPACE_CHANGED_EVENT));
  });
}
afterEach(() => {
  cleanup(); clients.splice(0).forEach(client => client.clear());
  onlineManager.setOnline(true); identity = null; calls.length = 0;
  reply = async () => ({ tenantId: "tenant-one" });
});
after(() => dom.window.close());

test("disabled session check starts when Clerk identity arrives after the page mounts", async () => {
  const ui = mount();
  assert.equal(ui.getByTestId("status").textContent, "pending");
  assert.equal(ui.getByTestId("fetch-status").textContent, "idle");
  assert.equal(calls.length, 0);
  await changeIdentity("clerk:user-one:org-one");
  await waitFor(() => assert.equal(ui.getByTestId("tenant").textContent, "tenant-one"));
  assert.equal(calls.length, 1);
  assert.equal(calls[0].path, "/api/auth/session");
  assert.equal(calls[0].options.headers["X-StockPilot-Mode"], "auto");
});

test("changing organization never displays the previous workspace while the new session loads", async () => {
  identity = "clerk:user-one:org-one";
  let resolveNew;
  reply = call => call.identity.endsWith("org-one") ? Promise.resolve({ tenantId: "tenant-one" }) : new Promise(resolve => { resolveNew = resolve; });
  const ui = mount();
  await waitFor(() => assert.equal(ui.getByTestId("tenant").textContent, "tenant-one"));
  await changeIdentity("clerk:user-one:org-two");
  await waitFor(() => assert.equal(calls.length, 2));
  assert.equal(ui.getByTestId("tenant").textContent, "none");
  assert.equal(ui.getByTestId("status").textContent, "pending");
  await act(async () => resolveNew({ tenantId: "tenant-two" }));
  await waitFor(() => assert.equal(ui.getByTestId("tenant").textContent, "tenant-two"));
});

test("session verification runs even when TanStack thinks the browser is offline", async () => {
  identity = "clerk:user-one:personal";
  onlineManager.setOnline(false);
  const ui = mount();
  await waitFor(() => assert.equal(calls.length, 1));
  await waitFor(() => assert.equal(ui.getByTestId("status").textContent, "success"));
  assert.equal(ui.getByTestId("fetch-status").textContent, "idle");
  assert.equal(ui.getByTestId("tenant").textContent, "tenant-one");
});

test("changing identity cancels the old session request through the API signal", async () => {
  identity = "clerk:user-one:org-one";
  let aborted = false;
  reply = call => call.identity.endsWith("org-two") ? Promise.resolve({ tenantId: "tenant-two" }) : new Promise((resolve, reject) => {
    call.options.signal.addEventListener("abort", () => { aborted = true; reject(new Error("Session request cancelled")); }, { once: true });
  });
  const ui = mount();
  await waitFor(() => assert.equal(calls.length, 1));
  assert.equal(calls[0].options.timeoutMs, 75_000);
  assert.equal(calls[0].options.signal.aborted, false);
  await changeIdentity("clerk:user-one:org-two");
  await waitFor(() => assert.equal(ui.getByTestId("tenant").textContent, "tenant-two"));
  assert.equal(aborted, true);
  assert.equal(calls[0].options.signal.aborted, true);
  assert.equal(ui.queryByRole("alert"), null);
});

test("a timed-out session becomes an actionable error without automatic retry and can recover manually", async () => {
  identity = "clerk:user-one:personal";
  reply = async call => {
    assert.equal(call.options.timeoutMs, 75_000);
    if (calls.length === 1) throw new Error("The account check timed out. Please try again.");
    return { tenantId: "tenant-recovered" };
  };
  const ui = mount();
  await ui.findByRole("alert");
  assert.match(ui.getByRole("alert").textContent, /account check timed out/);
  assert.equal(ui.getByTestId("status").textContent, "error");
  assert.equal(ui.getByTestId("fetch-status").textContent, "idle");
  assert.equal(calls.length, 1);
  fireEvent.click(ui.getByRole("button", { name: "Try again" }));
  await waitFor(() => assert.equal(ui.getByTestId("tenant").textContent, "tenant-recovered"));
  assert.equal(calls.length, 2);
  assert.equal(ui.queryByRole("alert"), null);
});
