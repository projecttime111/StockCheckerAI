import assert from "node:assert/strict";
import test, { after, afterEach } from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import ts from "typescript";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "https://stockpilot.test/sign-in" });
Object.assign(globalThis, { window: dom.window, document: dom.window.document, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true });
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
const { render, cleanup, act } = await import("@testing-library/react");
const require = createRequire(import.meta.url), React = require("react");
const getToken = async () => "session-token";
const clerk = { signOut: async () => {}, setActive: async () => {} };
let auth = { isLoaded: false, userId: null, orgId: null, getToken }, bridge = null;
let hookCalls = 0, pathname = "/sign-in";
const configurations = [];
const childIdentities = [], timers = new Map();
let timerId = 0;
window.setTimeout = (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; };
window.clearTimeout = id => { timers.delete(id); };
const api = {
  CLERK_ENABLED: true,
  configureClerkSession: value => { bridge = value; configurations.push(value); },
  clerkSessionReady: identity => bridge !== null && bridge.identity === identity,
};
const module = { exports: {} };
const source = ts.transpileModule(readFileSync(new URL("../components/IdentityProvider.tsx", import.meta.url), "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true, target: ts.ScriptTarget.ES2022 } }).outputText;
new Function("require", "module", "exports", source)(name => {
  if (name === "@/lib/api") return api;
  if (name === "next/navigation") return { usePathname: () => pathname };
  if (name === "@/components/LoadingState") return { LoadingState: ({ title, description }) => React.createElement("div", { role: "status" }, title, description) };
  if (name === "@clerk/nextjs") return { useAuth: () => { hookCalls++; return auth; }, useClerk: () => clerk };
  return require(name);
}, module, module.exports);
const { IdentityProvider } = module.exports;
function Child() { childIdentities.push(bridge?.identity); return React.createElement("p", null, "Application content"); }
function content() { return React.createElement(IdentityProvider, null, React.createElement(Child)); }
afterEach(() => {
  cleanup();
  auth = { isLoaded: false, userId: null, orgId: null, getToken };
  api.CLERK_ENABLED = true;
  bridge = null; hookCalls = 0; childIdentities.length = 0; timers.clear(); pathname = "/sign-in"; configurations.length = 0;
});
after(() => dom.window.close());

test("unloaded Clerk session keeps application children unmounted and offers recovery after a delay", () => {
  const ui = render(content());
  assert.equal(childIdentities.length, 0);
  assert.equal(bridge, null);
  assert.match(ui.getByRole("status").textContent, /Preparing your secure session/);
  assert.equal(ui.queryByRole("button", { name: "Retry sign-in" }), null);
  const timer = [...timers.values()].find(value => value.delay === 15_000);
  assert.ok(timer);
  act(() => timer.callback());
  assert.match(ui.getByRole("status").textContent, /taking longer than expected/);
  assert.ok(ui.getByRole("button", { name: "Retry sign-in" }));
  assert.equal(childIdentities.length, 0);
});

test("Clerk initialization can recover after a delay without exposing an unconfigured session", () => {
  const ui = render(content());
  act(() => [...timers.values()][0].callback());
  auth = { ...auth, isLoaded: true, userId: "user-one" };
  ui.rerender(content());
  assert.deepEqual(childIdentities, ["clerk:user-one:personal"]);
  assert.ok(ui.getByText("Application content"));
  assert.equal(ui.queryByRole("button", { name: "Retry sign-in" }), null);
  assert.equal(timers.size, 0);
});

test("workspace changes configure the new identity before application children render", () => {
  auth = { ...auth, isLoaded: true, userId: "user-one", orgId: "org-one" };
  const ui = render(content());
  assert.deepEqual(childIdentities, ["clerk:user-one:org-one"]);
  auth = { ...auth, orgId: "org-two" };
  ui.rerender(content());
  assert.deepEqual(childIdentities, ["clerk:user-one:org-one", "clerk:user-one:org-two"]);
  ui.unmount();
  assert.equal(bridge, null);
});

test("signed-out Clerk visitors can mount the sign-in page with a configured anonymous bridge", () => {
  auth = { ...auth, isLoaded: true };
  render(content());
  assert.deepEqual(childIdentities, [null]);
  assert.ok(bridge);
});

test("local authentication does not invoke Clerk hooks or require a Clerk provider", () => {
  api.CLERK_ENABLED = false;
  const ui = render(content());
  assert.ok(ui.getByText("Application content"));
  assert.equal(hookCalls, 0);
  assert.equal(bridge, null);
});

test("SDK callback refresh does not clear active queries or detach the current identity", async () => {
  auth = { ...auth, isLoaded: true, userId: "user-one" };
  const ui = render(content());
  const previousBridge = bridge, previousConfigurations = configurations.length;
  auth = { ...auth, getToken: async () => "refreshed-token" };
  ui.rerender(content());
  assert.equal(bridge, previousBridge);
  assert.equal(configurations.length, previousConfigurations);
  assert.equal(await bridge.getToken(), "refreshed-token");
  assert.ok(ui.getByText("Application content"));
});

test("public homepage renders while Clerk is unavailable, but private navigation still waits", () => {
  pathname = "/";
  const ui = render(content());
  assert.ok(ui.getByText("Application content"));
  assert.equal(bridge, null);
  pathname = "/dashboard";
  ui.rerender(content());
  assert.equal(ui.queryByText("Application content"), null);
  assert.match(ui.getByRole("status").textContent, /Preparing your secure session/);
});

test("public service status remains available while Clerk has not loaded", () => {
  pathname = "/status";
  const ui = render(content());
  assert.ok(ui.getByText("Application content"));
  assert.equal(bridge, null);
  assert.equal(ui.queryByText("Preparing your secure session"), null);
  pathname = "/admin/system";
  ui.rerender(content());
  assert.equal(ui.queryByText("Application content"), null);
  assert.match(ui.getByRole("status").textContent, /Preparing your secure session/);
});
