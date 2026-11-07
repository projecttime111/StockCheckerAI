import assert from "node:assert/strict";
import test, { after, afterEach } from "node:test";
import { readFileSync, existsSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";
import { JSDOM } from "jsdom";
const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/admin" });
Object.assign(globalThis, { window: dom.window, document: dom.window.document, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true });
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
const { render, fireEvent, cleanup, waitFor } = await import("@testing-library/react");
const require = createRequire(import.meta.url);
const React = require("react");
const { QueryClient, QueryClientProvider } = require("@tanstack/react-query");
const root = fileURLToPath(new URL("..", import.meta.url));
let request, pathname = "/admin/businesses";
const redirects = [], modules = new Map(), clients = [];
const router = { replace: path => redirects.push(path) };
function load(path) {
  let filename = path;
  if (!existsSync(filename)) filename += existsSync(path + ".tsx") ? ".tsx" : ".ts";
  if (modules.has(filename)) return modules.get(filename);
  const module = { exports: {} };
  const source = ts.transpileModule(readFileSync(filename, "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true, target: ts.ScriptTarget.ES2022 } }).outputText;
  const localRequire = name => {
    if (name === "@/lib/api") return { api: (...args) => request(...args), signOutSession: async () => redirects.push("/login") };
    if (name === "next/link") return { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) };
    if (name === "next/navigation") return { usePathname: () => pathname, useRouter: () => router };
    if (name.startsWith("@/")) return load(resolve(root, name.slice(2)));
    if (name.startsWith("./")) return load(resolve(dirname(filename), name));
    return require(name);
  };
  new Function("require", "module", "exports", source)(localRequire, module, module.exports);
  modules.set(filename, module.exports);
  return module.exports;
}
const { PlatformShell } = load(resolve(root, "components/platform/PlatformShell"));
const Clients = load(resolve(root, "app/admin/businesses/page")).default;
const Imports = load(resolve(root, "app/admin/imports/page")).default;
const Audit = load(resolve(root, "app/admin/audit/page")).default;
const System = load(resolve(root, "app/admin/system/page")).default;
const { BusinessDetailView } = load(resolve(root, "components/platform/BusinessDetailView"));
const { isPlatformSession, platformPath } = load(resolve(root, "lib/platform"));
const permissions = ["platform.tenants.view", "platform.tenants.manage", "platform.subscriptions.manage", "platform.system_health.view", "platform.audit.view"];
const billingPermissions = ["platform.tenants.view", "platform.subscriptions.manage"];
const session = (role = "PLATFORM_SUPER_ADMIN", access = permissions) => ({ userId: "staff-1", fullName: "Platform Operator", email: "operator@example.test", tenantId: null, role, platformRole: role, permissions: access, workspaces: [] });
function mount(component, props) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  clients.push(client);
  return render(React.createElement(QueryClientProvider, { client }, React.createElement(PlatformShell, null, React.createElement(component, props))));
}
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.clear()); redirects.length = 0; pathname = "/admin/businesses"; });
after(() => dom.window.close());
const paged = (content, page = 0, totalPages = 1, totalElements = content.length) => ({ content, page, size: 20, totalPages, totalElements });
const business = { id: "business-1", name: "Acme Retail", businessMode: "RETAIL", status: "ACTIVE", currency: "INR", gstEnabled: true, createdAt: "2026-01-01T00:00:00Z", plan: "FREE", subscriptionStatus: "TRIAL", subscriptionEndsAt: null, memberCount: 2, productCount: 12, importCount: 1, lastImportAt: null };
const usage = { memberCount: 2, productCount: 12, customerCount: 3, supplierCount: 2, warehouseCount: 1, salesInvoiceCount: 0, purchaseInvoiceCount: 1, stockMovementCount: 12, importCount: 1, importFileCount: 2, lastImportAt: null };
const detail = () => ({ business: { ...business }, usage: { ...usage }, internalNotes: "Support context" });
const importRow = { id: "import-1", tenantId: business.id, businessName: business.name, kind: "SMART", source: "SMART_IMPORT", status: "FAILED", name: "Opening inventory", createdAt: "2026-01-02T00:00:00Z", committedAt: null, rowCount: 12, errorCount: 2, fileCount: 1, fileNames: ["opening.xlsx"] };

test("guard waits for verified session and rejects tenant owner before child queries mount", async () => {
  let finish; const calls = [];
  request = async path => { calls.push(path); return new Promise(resolve => { finish = resolve; }); };
  const ui = mount(Clients);
  assert.match(ui.getByRole("status").textContent, /Verifying/);
  assert.deepEqual(calls, ["/api/auth/session"]);
  finish({ ...session(), role: "OWNER", platformRole: null, tenantId: business.id });
  await ui.findByRole("heading", { name: "Platform access required" });
  assert.deepEqual(calls, ["/api/auth/session"]);
  assert.equal(isPlatformSession({ ...session(), tenantId: business.id }), false);
});

test("billing landing redirects without mounting restricted overview", async () => {
  pathname = "/admin";
  request = async path => { assert.equal(path, "/api/auth/session"); return session("PLATFORM_BILLING_ADMIN", billingPermissions); };
  const ui = mount(() => React.createElement("p", null, "Restricted child"));
  await waitFor(() => assert.deepEqual(redirects, ["/admin/businesses"]));
  assert.equal(ui.queryByText("Restricted child"), null);
  assert.equal(ui.queryByRole("link", { name: "System status" }), null);
});

test("client search and status use server paging and reset to the first page", async () => {
  const calls = [];
  request = async path => { calls.push(path); if (path === "/api/auth/session") return session(); const params = new URL(path, "http://localhost").searchParams; return paged([{ ...business, name: params.get("page") === "1" ? "Later Client" : business.name }], Number(params.get("page")), 2, 22); };
  const ui = mount(Clients);
  await ui.findByRole("link", { name: business.name });
  fireEvent.click(ui.getByRole("button", { name: "Next" }));
  await ui.findByRole("link", { name: "Later Client" });
  fireEvent.change(ui.getByLabelText("Search businesses"), { target: { value: " A&B " } });
  fireEvent.change(ui.getByLabelText("Business status"), { target: { value: "SUSPENDED" } });
  fireEvent.click(ui.getByRole("button", { name: "Search" }));
  await waitFor(() => assert.ok(calls.some(path => { const p = new URL(path, "http://localhost").searchParams; return p.get("search") === "A&B" && p.get("page") === "0" && p.get("status") === "SUSPENDED"; })));
  await ui.findByText(/22 records/);
});

test("support can inspect activity but cannot mutate business access, billing, or notes", async () => {
  pathname = `/admin/businesses/${business.id}`; const calls = [];
  request = async path => { calls.push(path); if (path === "/api/auth/session") return session("PLATFORM_SUPPORT", ["platform.tenants.view", "platform.system_health.view", "platform.audit.view"]); return path.endsWith(`/businesses/${business.id}`) ? detail() : paged([]); };
  const ui = mount(BusinessDetailView, { id: business.id });
  await ui.findByText("Support context");
  await waitFor(() => assert.ok(calls.some(path => path.includes("/members?")) && calls.some(path => path.includes("/imports?"))));
  for (const name of ["Review suspension", "Save subscription record", "Save internal notes"]) assert.equal(ui.queryByRole("button", { name }), null);
});

test("suspension requires confirmation and reason, preserves failed input, refreshes saved state", async () => {
  pathname = `/admin/businesses/${business.id}`; let record = detail(); const writes = [];
  request = async (path, init) => { if (path === "/api/auth/session") return session(); if (init?.method === "PATCH") { writes.push({ path, body: JSON.parse(init.body) }); if (writes.length === 1) throw new Error("Service temporarily unavailable"); record = { ...record, business: { ...record.business, status: "SUSPENDED" } }; return record; } return path.endsWith(`/businesses/${business.id}`) ? record : paged([]); };
  const ui = mount(BusinessDetailView, { id: business.id });
  fireEvent.click(await ui.findByRole("button", { name: "Review suspension" }));
  assert.equal(ui.getByRole("button", { name: "Suspend business" }).disabled, true);
  fireEvent.change(ui.getByLabelText("Reason for access change"), { target: { value: "  Client requested a pause  " } });
  assert.equal(ui.getByRole("button", { name: "Suspend business" }).disabled, true);
  fireEvent.change(ui.getByLabelText("Type Acme Retail to confirm"), { target: { value: business.name } });
  fireEvent.click(ui.getByRole("button", { name: "Suspend business" }));
  await ui.findByText("Service temporarily unavailable");
  assert.equal(ui.getByLabelText("Reason for access change").value, "  Client requested a pause  ");
  fireEvent.click(ui.getByRole("button", { name: "Suspend business" }));
  await ui.findByText("Business access updated.");
  await ui.findByRole("button", { name: "Review reactivation" });
  assert.deepEqual(writes[1], { path: `/api/platform/businesses/${business.id}/status`, body: { status: "SUSPENDED", reason: "Client requested a pause" } });
});

test("billing updates only a manual subscription record with required reason and ISO expiry", async () => {
  pathname = `/admin/businesses/${business.id}`; let record = { ...detail(), internalNotes: null }; const writes = [], reads = [];
  request = async (path, init) => { if (path === "/api/auth/session") return session("PLATFORM_BILLING_ADMIN", billingPermissions); if (init?.method === "PATCH") { const body = JSON.parse(init.body); writes.push({ path, body }); record = { ...record, business: { ...record.business, ...body } }; return record; } reads.push(path); return record; };
  const ui = mount(BusinessDetailView, { id: business.id });
  await ui.findByRole("button", { name: "Save subscription record" });
  assert.equal(ui.queryByText("Internal platform notes"), null);
  assert.equal(ui.queryByText("Workspace members"), null);
  assert.ok(reads.every(path => !path.includes("/members") && !path.includes("/imports")));
  fireEvent.change(ui.getByLabelText("Recorded plan"), { target: { value: "ENTERPRISE" } });
  fireEvent.change(ui.getByLabelText("Recorded subscription status"), { target: { value: "ACTIVE" } });
  fireEvent.change(ui.getByLabelText("Subscription end (your local time)"), { target: { value: "2030-02-01T14:30" } });
  fireEvent.change(ui.getByLabelText("Reason for subscription update"), { target: { value: "Agreed annual contract" } });
  fireEvent.click(ui.getByRole("button", { name: "Save subscription record" }));
  await ui.findByText("Subscription record updated.");
  assert.deepEqual(writes, [{ path: `/api/platform/businesses/${business.id}/subscription`, body: { plan: "ENTERPRISE", subscriptionStatus: "ACTIVE", subscriptionEndsAt: new Date("2030-02-01T14:30").toISOString(), reason: "Agreed annual contract" } }]);
  assert.equal(ui.getByLabelText("Reason for subscription update").value, "");
});

test("import monitoring shows errors with retry and sends source/failed filters", async () => {
  pathname = "/admin/imports"; let fail = true; const calls = [];
  request = async path => { if (path === "/api/auth/session") return session(); calls.push(path); if (fail) throw new Error("Monitoring temporarily unavailable"); return paged([importRow]); };
  const ui = mount(Imports);
  await ui.findByText("Monitoring temporarily unavailable");
  assert.equal(ui.queryByText("No imports found"), null);
  fail = false; fireEvent.click(ui.getByRole("button", { name: "Try again" }));
  await ui.findByText("Opening inventory");
  fireEvent.click(ui.getByLabelText("Failed only"));
  fireEvent.change(ui.getByLabelText("Source"), { target: { value: "SMART_IMPORT" } });
  fireEvent.click(ui.getByRole("button", { name: "Apply filters" }));
  await waitFor(() => assert.ok(calls.some(path => path.includes("failedOnly=true") && path.includes("source=SMART_IMPORT") && path.includes("page=0"))));
  await ui.findByText("2 errors");
});

test("audit renders the recorded reason and before/after changes", async () => {
  pathname = "/admin/audit";
  request = async path => path === "/api/auth/session" ? session() : paged([{ id: "a1", tenantId: business.id, businessName: business.name, actorUserId: "staff-1", actorName: "Platform Operator", action: "TENANT_SUSPENDED", reason: "Client requested a pause", beforeValues: { status: "ACTIVE" }, afterValues: { status: "SUSPENDED" }, createdAt: "2026-01-02T00:00:00Z" }]);
  const ui = mount(Audit); await ui.findByText("Client requested a pause");
  assert.ok(ui.getByText(/"ACTIVE"/)); assert.ok(ui.getByText(/"SUSPENDED"/));
  assert.equal(ui.getByRole("link", { name: business.name }).getAttribute("href"), `/admin/businesses/${business.id}`);
});

test("system shows database failure honestly without inventing external service health", async () => {
  pathname = "/admin/system";
  request = async path => path === "/api/auth/session" ? session() : { generatedAt: "2026-01-02T00:00:00Z", database: { status: "DOWN", responseTimeMs: -1, checkedAt: "2026-01-02T00:00:00Z" }, uptimeSeconds: 3600, totalImports: 0, failedImports: 0, committingImports: 0, oldestCommittingAt: null, failuresLast24Hours: 0, lastFailureAt: null, importsByStatus: {} };
  const ui = mount(System); await ui.findByText("down");
  assert.ok(ui.getByText("Unavailable")); assert.ok(ui.getByText("No imports recorded."));
  assert.ok(ui.getByText(/Email, storage, authentication providers and other services are not included/));
});

test("contract query builder encodes text and preserves page zero while omitting empty filters", () => {
  assert.equal(platformPath("/businesses", { search: "A&B", page: 0, size: 20, status: "", absent: undefined }), "/api/platform/businesses?search=A%26B&page=0&size=20");
});


test("billing cannot mount an audit page by direct URL", async () => {
  pathname = "/admin/audit"; const calls = [];
  request = async path => { calls.push(path); return session("PLATFORM_BILLING_ADMIN", billingPermissions); };
  const ui = mount(Audit);
  await ui.findByRole("heading", { name: "Access restricted" });
  assert.deepEqual(calls, ["/api/auth/session"]);
});

test("deleted businesses show historical records without offering mutations", async () => {
  pathname = `/admin/businesses/${business.id}`;
  request = async path => path === "/api/auth/session" ? session() : path.endsWith(`/businesses/${business.id}`) ? { ...detail(), business: { ...business, status: "DELETED" } } : paged([]);
  const ui = mount(BusinessDetailView, { id: business.id });
  await ui.findByText("This workspace has been deleted. Access cannot be changed here.");
  for (const name of ["Review suspension", "Save subscription record", "Save internal notes"]) assert.equal(ui.queryByRole("button", { name }), null);
});


test("rejected platform session offers workspace selection without mounting admin queries", async () => {
  const calls = [];
  request = async path => { calls.push(path); const error = new Error("Platform access is required"); error.status = 403; throw error; };
  const ui = mount(Clients);
  await ui.findByText("Platform access is required");
  assert.equal(ui.getByRole("link", { name: "Choose a workspace" }).getAttribute("href"), "/workspace/select");
  assert.deepEqual(calls, ["/api/auth/session"]);
});
