import assert from "node:assert/strict";
import test, { after, afterEach } from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import ts from "typescript";
import { JSDOM } from "jsdom";
const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "https://stockpilot.example/sign-in" });
Object.assign(globalThis, { window: dom.window, document: dom.window.document, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true });
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
const { render, cleanup, fireEvent, act, waitFor } = await import("@testing-library/react");
const require = createRequire(import.meta.url), React = require("react");
let auth = { isLoaded: true, isSignedIn: false, sessionId: null };
let authCalls = 0, signInMounts = 0, signUpMounts = 0, reloads = 0, signOuts = 0, timerId = 0;
let renderForm = true, signOutFailure = false, latestFormProps;
const redirects = [], timers = new Map();
const router = { replace: value => redirects.push(value) };
const location = { origin: "https://stockpilot.example", search: "", reload: () => { reloads++; } };
const api = { CLERK_ENABLED: true, signOutSession: async () => { signOuts++; if (signOutFailure) throw new Error("Offline"); } };
const module = { exports: {} };
const source = ts.transpileModule(readFileSync(new URL("../components/ClerkEntry.tsx", import.meta.url), "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true, target: ts.ScriptTarget.ES2022 } }).outputText;
new Function("require", "module", "exports", "window", "setTimeout", "clearTimeout", source)(name => {
  if (name === "@clerk/nextjs") return {
    useAuth: () => { authCalls++; return auth; },
    SignIn: props => { signInMounts++; latestFormProps = props; return renderForm ? React.createElement("div", null, auth.sessionStatus === "pending" ? "Pending session task" : "Clerk sign-in form") : props.fallback; },
    SignUp: props => { signUpMounts++; latestFormProps = props; return renderForm ? React.createElement("div", null, "Clerk sign-up form") : props.fallback; }
  };
  if (name === "@/lib/api") return api;
  if (name === "next/navigation") return { useRouter: () => router };
  if (name === "next/link") return { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) };
  return require(name);
}, module, module.exports, { location }, (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; }, id => timers.delete(id));
const { ClerkEntry, clerkEntryDestination } = module.exports;
function mount(props = {}, strict = false) { const node = React.createElement(ClerkEntry, props); return render(strict ? React.createElement(React.StrictMode, null, node) : node); }
async function elapse() { await act(async () => { const callbacks = [...timers.values()]; timers.clear(); callbacks.forEach(timer => { assert.equal(timer.delay, 15000); timer.callback(); }); }); }
afterEach(() => { cleanup(); auth = { isLoaded: true, isSignedIn: false, sessionId: null }; authCalls = 0; signInMounts = 0; signUpMounts = 0; reloads = 0; signOuts = 0; renderForm = true; signOutFailure = false; latestFormProps = undefined; redirects.length = 0; timers.clear(); location.search = ""; api.CLERK_ENABLED = true; });
after(() => dom.window.close());

test("already active session bypasses SignIn and redirects once even in StrictMode", () => {
  auth = { isLoaded: true, isSignedIn: true, sessionId: "active" };
  const ui = mount({}, true);
  assert.match(ui.getByRole("status").textContent, /Opening your workspace/);
  assert.deepEqual(redirects, ["/auth/complete"]); assert.equal(signInMounts + signUpMounts, 0);
  ui.rerender(React.createElement(React.StrictMode, null, React.createElement(ClerkEntry)));
  assert.deepEqual(redirects, ["/auth/complete"]);
});

test("active SignUp also bypasses the auth form and preserves safe next", () => {
  auth = { isLoaded: true, isSignedIn: true, sessionId: "active" };
  location.search = "?next=%2Fproducts%3Fquery%3Dtest"; mount({ signup: true });
  assert.deepEqual(redirects, ["/auth/complete?next=%2Fproducts%3Fquery%3Dtest"]); assert.equal(signInMounts + signUpMounts, 0);
});

test("completing sign-in replaces the form with the workspace handoff", () => {
  const ui = mount(); assert.ok(ui.getByText("Clerk sign-in form")); const previous = signInMounts;
  auth = { isLoaded: true, isSignedIn: true, sessionId: "new" }; ui.rerender(React.createElement(ClerkEntry));
  assert.equal(ui.queryByText("Clerk sign-in form"), null); assert.equal(signInMounts, previous); assert.deepEqual(redirects, ["/auth/complete"]);
});

test("pending session remains in Clerk organization/MFA task UI", () => {
  auth = { isLoaded: true, isSignedIn: false, sessionId: null, sessionStatus: "pending" };
  const ui = mount(); assert.ok(ui.getByText("Pending session task")); assert.deepEqual(redirects, []);
});

test("unloaded auth and unmounted UI have visible fallback with reload recovery", async () => {
  auth = { isLoaded: false, isSignedIn: undefined, sessionId: undefined }; renderForm = false;
  const ui = mount(); assert.match(ui.getByRole("status").textContent, /Loading the secure sign-in form/); assert.equal(signInMounts, 0);
  auth = { isLoaded: true, isSignedIn: false, sessionId: null }; ui.rerender(React.createElement(ClerkEntry)); assert.ok(latestFormProps.fallback);
  await elapse(); fireEvent.click(ui.getByRole("button", { name: "Reload sign-in form" })); assert.equal(reloads, 1); assert.deepEqual(redirects, []);
});

test("stalled handoff offers direct link, reload, and retryable signout", async () => {
  auth = { isLoaded: true, isSignedIn: true, sessionId: "active" }; location.search = "?next=%2Fteam%2Faccept%3Ftoken%3Dinvite";
  const ui = mount({}, true); assert.equal(ui.queryByRole("link", { name: "Continue to workspace" }), null); await elapse();
  assert.equal(ui.getByRole("link", { name: "Continue to workspace" }).getAttribute("href"), "/auth/complete?next=%2Fteam%2Faccept%3Ftoken%3Dinvite");
  fireEvent.click(ui.getByRole("button", { name: "Reload page" })); assert.equal(reloads, 1);
  signOutFailure = true; fireEvent.click(ui.getByRole("button", { name: "Sign out" })); await ui.findByText("Could not sign out. Check your connection and try again.");
  signOutFailure = false; fireEvent.click(ui.getByRole("button", { name: "Sign out" })); await waitFor(() => assert.equal(signOuts, 2));
  assert.equal(signInMounts, 0); assert.deepEqual(redirects, ["/auth/complete?next=%2Fteam%2Faccept%3Ftoken%3Dinvite"]);
});

test("destination accepts safe next and same-origin redirect_url, rejecting external targets and auth loops", () => {
  const origin = location.origin;
  assert.equal(clerkEntryDestination("?redirect_url=" + encodeURIComponent(origin + "/sales?view=recent#invoice"), origin), "/auth/complete?next=%2Fsales%3Fview%3Drecent%23invoice");
  assert.equal(clerkEntryDestination("?next=%2Fstock", origin), "/auth/complete?next=%2Fstock");
  for (const target of ["https://attacker.example", "//attacker.example", "/\\attacker.example", "/", "/login", "/sign-in", "/sign-up/continue", "/auth/complete", "/products/../sign-in", "/%2fsign-in", "/%5cattacker.example", "javascript:alert(1)"]) assert.equal(clerkEntryDestination("?next=" + encodeURIComponent(target), origin), "/auth/complete", target);
  assert.equal(clerkEntryDestination("?redirect_url=" + encodeURIComponent("https://attacker.example/products"), origin), "/auth/complete");
});

test("local mode keeps existing login links without invoking Clerk hooks", () => {
  api.CLERK_ENABLED = false; const ui = mount(); assert.equal(ui.getByRole("link", { name: "Continue to sign in" }).getAttribute("href"), "/login");
  assert.equal(authCalls, 0); assert.equal(signInMounts + signUpMounts, 0); assert.deepEqual(redirects, []);
});

test("Clerk completion redirect preserves one safe nested next and rejects recursive or external targets", () => {
  const origin = location.origin;
  const redirect = (next, base = origin + "/auth/complete") => "?redirect_url=" + encodeURIComponent(base + "?next=" + encodeURIComponent(next));
  assert.equal(clerkEntryDestination(redirect("/products?query=blue#results"), origin), "/auth/complete?next=%2Fproducts%3Fquery%3Dblue%23results");
  assert.equal(clerkEntryDestination(redirect("/products", "/auth/complete"), origin), "/auth/complete?next=%2Fproducts");
  for (const nested of ["https://attacker.example/products", "//attacker.example", "/\\attacker.example", "/auth/complete?next=/products", "/login", "/sign-in", "/"]) {
    assert.equal(clerkEntryDestination(redirect(nested), origin), "/auth/complete", nested);
  }
  assert.equal(clerkEntryDestination(redirect("/products", "https://attacker.example/auth/complete"), origin), "/auth/complete");
  assert.equal(clerkEntryDestination(redirect("/products", origin + "/sign-in"), origin), "/auth/complete");
});
