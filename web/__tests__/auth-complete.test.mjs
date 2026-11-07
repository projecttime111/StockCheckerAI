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
let state = { isFetching: true }, signedIn = true, timerId = 0, search = "";
const timers = new Map(), redirects = [], operations = [];
const router = { replace: path => redirects.push(path) };
const queryClient = { cancelQueries: async () => operations.push("cancel") };
const api = {
  CLERK_ENABLED: true, getToken: () => signedIn ? "identity" : null,
  signOutSession: async () => { operations.push("sign-out"); },
  loginDestination: role => role.startsWith("PLATFORM_") ? "/admin" : "/dashboard",
  invitationDestination: query => {
    const next = new URLSearchParams(query).get("next");
    return next?.startsWith("/team/accept?token=") ? next : null;
  },
};
const module = { exports: {} };
const source = ts.transpileModule(readFileSync(new URL("../app/auth/complete/page.tsx", import.meta.url), "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true, target: ts.ScriptTarget.ES2022 } }).outputText;
new Function("require", "module", "exports", "window", source)(name => {
  if (name === "@/lib/api") return api;
  if (name === "@/lib/session") return { useSession: () => ({ ...state, refetch: async () => { operations.push("refetch"); } }) };
  if (name === "next/navigation") return { useRouter: () => router };
  if (name === "@tanstack/react-query") return { useQueryClient: () => queryClient };
  if (name === "next/link") return { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) };
  if (name === "@/components/AccountFrame") return { AccountFrame: ({ children, title }) => React.createElement("main", null, React.createElement("h1", null, title), children) };
  if (name === "@/components/LoadingState") return { LoadingState: ({ title, description }) => React.createElement("div", { role: "status" }, title, description) };
  if (name === "@/components/FormControls") return { PrimaryButton: props => React.createElement("button", props) };
  return require(name);
}, module, module.exports, {
  get location() { return { search }; },
  setTimeout: (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; },
  clearTimeout: id => timers.delete(id),
});
const Page = module.exports.default;
const session = (accessState, extra = {}) => ({ accessState, role: "OWNER", workspaces: [], ...extra });
function advance(delay) { act(() => { for (const [id, timer] of timers) if (timer.delay <= delay) { timers.delete(id); timer.callback(); } }); }
afterEach(() => { cleanup(); state = { isFetching: true }; signedIn = true; search = ""; timers.clear(); redirects.length = 0; operations.length = 0; });
after(() => dom.window.close());

test("slow and orphaned account checks reveal recovery instead of an indefinite loader", async () => {
  const ui = render(React.createElement(Page));
  assert.match(ui.getByRole("status").textContent, /Checking your account/);
  advance(10_000);
  assert.match(ui.getByRole("status").textContent, /wake up after inactivity/);
  assert.ok(ui.getByRole("button", { name: "Retry connection" }));
  advance(90_000);
  assert.match(ui.getByRole("alert").textContent, /couldn’t finish/);
  assert.equal(ui.queryByRole("status"), null);
  fireEvent.click(ui.getByRole("button", { name: "Try again" }));
  await waitFor(() => assert.deepEqual(operations, ["cancel", "refetch"]));
  assert.match(ui.getByRole("status").textContent, /Checking your account/);
});

test("fresh session results open the correct destination only after verification completes", () => {
  state = { data: session("WORKSPACE_READY"), isFetching: true };
  const ui = render(React.createElement(Page));
  assert.deepEqual(redirects, []);
  state = { ...state, isFetching: false }; ui.rerender(React.createElement(Page));
  assert.deepEqual(redirects, ["/dashboard"]);
  ui.rerender(React.createElement(Page));
  assert.deepEqual(redirects, ["/dashboard"]);
});

test("new Clerk customers go directly to business setup and existing members select a workspace", () => {
  for (const [accessState, extra, destination] of [
    ["ORGANIZATION_REQUIRED", {}, "/onboarding"], ["ORGANIZATION_UNLINKED", {}, "/onboarding"],
    ["ORGANIZATION_REQUIRED", { workspaces: [{ tenantId: "business" }] }, "/workspace/select"],
    ["MEMBERSHIP_REQUIRED", {}, "/workspace/select"], ["PLATFORM_READY", { role: "PLATFORM_SUPER_ADMIN" }, "/admin"],
  ]) {
    state = { data: session(accessState, extra), isFetching: false };
    const ui = render(React.createElement(Page));
    assert.equal(redirects.at(-1), destination); ui.unmount();
  }
});

test("invited new accounts retain their invitation before ordinary workspace onboarding", () => {
  search = "?next=" + encodeURIComponent("/team/accept?token=invite");
  state = { data: session("ORGANIZATION_REQUIRED"), isFetching: false };
  render(React.createElement(Page));
  assert.deepEqual(redirects, ["/team/accept?token=invite"]);
});

test("errors and expired sessions offer recovery without migration links or private navigation", async () => {
  state = { error: new Error("Your sign-in session has expired."), isFetching: false };
  const ui = render(React.createElement(Page));
  assert.match(ui.getByRole("alert").textContent, /expired/);
  assert.deepEqual(redirects, []);
  assert.equal(ui.queryByRole("link", { name: /Link existing/ }), null);
  fireEvent.click(ui.getByRole("button", { name: "Sign out and try again" }));
  await waitFor(() => assert.deepEqual(operations, ["sign-out"]));
  assert.equal(ui.getByRole("link", { name: "Back to home" }).getAttribute("href"), "/");
});

test("signed-out visitors return to Clerk sign-in", () => {
  signedIn = false; render(React.createElement(Page));
  assert.deepEqual(redirects, ["/sign-in"]);
});
