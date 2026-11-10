import assert from "node:assert/strict";
import test, { after, afterEach } from "node:test";
import { existsSync, readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";
import { JSDOM } from "jsdom";
const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "https://stockpilot.example/status" });
Object.assign(globalThis, { window: dom.window, document: dom.window.document, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true });
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
const { render, cleanup, fireEvent, within } = await import("@testing-library/react");
const require = createRequire(import.meta.url), React = require("react"), { renderToStaticMarkup } = require("react-dom/server");
const root = fileURLToPath(new URL("..", import.meta.url)), modules = new Map();
const probe = (status = "unknown", overrides = {}) => ({ status, checkedAt: null, responseTimeMs: null, message: "No check has completed yet.", ...overrides });
let checks = 0, hookCalls = 0, clerkBlocked = false, health;
function reset() { checks = 0; hookCalls = 0; clerkBlocked = false; health = { frontend: probe(), backend: probe(), checking: false, check: async () => { checks++; } }; }
reset();
function load(path) {
  let filename = resolve(root, path); if (!existsSync(filename)) filename += existsSync(filename + ".tsx") ? ".tsx" : ".ts";
  if (modules.has(filename)) return modules.get(filename);
  const module = { exports: {} };
  const source = ts.transpileModule(readFileSync(filename, "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true, target: ts.ScriptTarget.ES2022 } }).outputText;
  new Function("require", "module", "exports", source)(name => {
    if (name.endsWith(".css")) return {};
    if (name === "@/lib/serviceHealth") return { useServiceHealth: () => { hookCalls++; return health; } };
    if (name === "next/link") return { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) };
    if (name === "@/lib/api") return { CLERK_ENABLED: true, DEMO_MODE: false, isDemoMode: () => false };
    if (name === "@/lib/query") return { Providers: ({ children }) => children };
    if (name === "@/components/IdentityProvider") return { IdentityProvider: () => React.createElement("p", null, "Waiting for identity") };
    if (name === "@/components/WorkspaceBoundary") return { WorkspaceBoundary: ({ children }) => children };
    if (name === "@clerk/nextjs") return { ClerkProvider: ({ children }) => clerkBlocked ? null : children };
    if (name === "@tanstack/react-query") return { useQuery: () => ({ error: new Error("Private database status unavailable"), refetch: () => {}, isFetching: false }) };
    if (name === "@/lib/platform") return { platformApi: { system: () => {} } };
    if (name === "@/components/platform/PlatformUI") return { PlatformHeading: ({ title, description, action }) => React.createElement("header", null, React.createElement("h1", null, title), React.createElement("p", null, description), action) };
    if (name.startsWith("@/")) return load(name.slice(2));
    if (name.startsWith("./")) return load(resolve(dirname(filename), name));
    return require(name);
  }, module, module.exports);
  modules.set(filename, module.exports); return module.exports;
}
afterEach(() => { cleanup(); modules.clear(); reset(); });
after(() => dom.window.close());

test("public status distinguishes frontend availability from backend startup waiting without auth", () => {
  health.frontend = probe("online", { checkedAt: 1_700_000_000_000, responseTimeMs: 0, message: "The website responded." });
  health.backend = probe("waiting", { message: "Waiting for a backend response." }); health.checking = true;
  const ui = render(React.createElement(load("app/status/page.tsx").default));
  assert.ok(ui.getByRole("heading", { name: "Service status", level: 1 }));
  assert.ok(within(ui.getByRole("article", { name: "Frontend health" })).getByText("Online"));
  assert.ok(within(ui.getByRole("article", { name: "Frontend health" })).getByText("0 ms"));
  assert.ok(within(ui.getByRole("article", { name: "Backend health" })).getByText("Waiting for response"));
  assert.ok(ui.getByText(/may be starting or temporarily slow/));
  assert.equal(ui.getByRole("button", { name: "Checking services…" }).disabled, true);
  assert.equal(ui.queryByText("Waiting for identity"), null); assert.equal(checks, 0);
  assert.match(ui.getByText(/90 seconds/).textContent, /up to 90 seconds/);
});

test("manual refresh uses the shared check and real observation times and duration", () => {
  const checkedAt = 1_700_000_000_000;
  health.frontend = probe("online", { checkedAt, responseTimeMs: 12, message: "Website available" });
  health.backend = probe("unavailable", { checkedAt, responseTimeMs: 90000, message: "The backend did not respond in time." });
  const Component = load("components/ServiceStatusPanel.tsx").ServiceStatusPanel;
  const ui = render(React.createElement(Component));
  fireEvent.click(ui.getByRole("button", { name: "Check again" })); assert.equal(checks, 1);
  assert.equal(ui.container.querySelector("time").dateTime, new Date(checkedAt).toISOString());
  assert.ok(ui.getByText(`${new Intl.NumberFormat().format(90000)} ms`));
  health = { ...health, checking: true, backend: probe("checking") }; ui.rerender(React.createElement(Component));
  fireEvent.click(ui.getByRole("button", { name: "Checking services…" })); assert.equal(checks, 1);
  assert.ok(ui.container.querySelector(".motion-safe\\:animate-spin"));
});

test("stale, offline, and demo observations do not claim the backend is online", () => {
  health.frontend = probe("unknown", { checkedAt: 1_700_000_000_000, message: "Last result is old; check again." });
  health.backend = probe("offline", { message: "This device is offline." });
  const Component = load("components/ServiceStatusPanel.tsx").ServiceStatusPanel;
  const ui = render(React.createElement(Component));
  assert.ok(ui.getByText("Check needed")); assert.ok(ui.getByText("Device offline")); assert.equal(ui.queryByText("Online"), null);
  health.backend = probe("demo", { message: "Demo mode uses local data; no backend check is needed." }); ui.rerender(React.createElement(Component));
  assert.ok(ui.getByText("Demo mode")); assert.equal(ui.queryByText("Online"), null);
});

test("global status stays rendered when Clerk cannot render the application", () => {
  clerkBlocked = true; health.frontend = probe("online"); health.backend = probe("waiting");
  const previous = process.env.NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY;
  process.env.NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY = "pk_test_fixture";
  try {
    const html = renderToStaticMarkup(React.createElement(load("app/layout.tsx").default, null, React.createElement("p", null, "Protected application")));
    assert.match(html, /href="\/status"/); assert.match(html, /Frontend/); assert.match(html, /Backend/); assert.match(html, /Waiting/);
    assert.doesNotMatch(html, /Protected application|Waiting for identity/); assert.ok(hookCalls > 0);
  } finally { if (previous === undefined) delete process.env.NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY; else process.env.NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY = previous; }
});

test("platform system shows public connectivity above a protected database error", () => {
  health.frontend = probe("online"); health.backend = probe("online");
  const ui = render(React.createElement(load("app/admin/system/page.tsx").default));
  const panel = ui.getByRole("heading", { name: "Frontend and backend status" });
  const error = ui.getByText("Private database status unavailable");
  assert.ok(panel.compareDocumentPosition(error) & window.Node.DOCUMENT_POSITION_FOLLOWING);
  assert.equal(ui.getAllByText("Online").length, 2);
});
