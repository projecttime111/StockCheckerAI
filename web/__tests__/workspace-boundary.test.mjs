import assert from "node:assert/strict";
import test, { after, afterEach } from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import ts from "typescript";
import { JSDOM } from "jsdom";
const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/products?query=test" });
Object.assign(globalThis, { window: dom.window, document: dom.window.document, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true });
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
const { render, cleanup, fireEvent } = await import("@testing-library/react");
const require = createRequire(import.meta.url), React = require("react");
let session = {}, signedIn = true, pathname = "/products", childMounts = 0, retries = 0;
const redirects = [], router = { replace: path => redirects.push(path) };
const module = { exports: {} };
const source = ts.transpileModule(readFileSync(new URL("../components/WorkspaceBoundary.tsx", import.meta.url), "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true, target: ts.ScriptTarget.ES2022 } }).outputText;
new Function("require", "module", "exports", source)(name => {
  if (name === "@/lib/api") return { getToken: () => signedIn ? "identity" : null, signOutSession: async () => {} };
  if (name === "@/components/LoadingState") return { LoadingState: ({ title }) => React.createElement("div", { role: "status" }, title) };
  if (name === "@/lib/session") return { useSession: () => ({ ...session, refetch: () => { retries++; } }) };
  if (name === "next/navigation") return { usePathname: () => pathname, useRouter: () => router };
  if (name === "next/link") return { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) };
  return require(name);
}, module, module.exports);
const { WorkspaceBoundary } = module.exports;
function BusinessPage() { childMounts++; return React.createElement("div", null, "Private business page"); }
function mount() { return render(React.createElement(WorkspaceBoundary, null, React.createElement(BusinessPage))); }
afterEach(() => { cleanup(); session = {}; signedIn = true; pathname = "/products"; childMounts = 0; retries = 0; redirects.length = 0; });
after(() => dom.window.close());

test("business route does not mount data-fetching children before session verification", () => {
  session = { isLoading: true }; const ui = mount();
  assert.match(ui.getByRole("status").textContent, /Checking workspace/);
  assert.equal(childMounts, 0);
});
test("business route redirects unsigned users without mounting private children", () => {
  signedIn = false; mount();
  assert.equal(childMounts, 0);
  assert.deepEqual(redirects, ["/login?next=%2Fproducts%3Fquery%3Dtest"]);
});
test("global platform identity cannot mount business routes without a selected membership", () => {
  session = { data: { tenantId: null, role: "PLATFORM_SUPER_ADMIN", platformRole: "PLATFORM_SUPER_ADMIN" } };
  const ui = mount(); assert.equal(childMounts, 0);
  assert.equal(ui.getByRole("link", { name: "Choose workspace" }).getAttribute("href"), "/workspace/select");
});
test("selected business identity mounts its page only after verification", () => {
  session = { data: { tenantId: "business-A", role: "OWNER", platformRole: null } };
  const ui = mount(); assert.ok(ui.getByText("Private business page")); assert.equal(childMounts, 1);
});
test("suspended or unavailable workspace offers retry and workspace switching without private content", () => {
  session = { error: new Error("Workspace is suspended") }; const ui = mount();
  assert.equal(childMounts, 0);
  assert.equal(ui.getByRole("link", { name: "Switch workspace" }).getAttribute("href"), "/workspace/select");
  fireEvent.click(ui.getByRole("button", { name: "Retry" })); assert.equal(retries, 1);
});
test("public invitation remains reachable without a business membership", () => {
  signedIn = false; pathname = "/team/accept"; mount(); assert.equal(childMounts, 1); assert.deepEqual(redirects, []);
});
