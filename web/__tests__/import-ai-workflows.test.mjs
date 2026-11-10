import assert from "node:assert/strict";
import test, { after, afterEach } from "node:test";
import { readFileSync, existsSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";
import { JSDOM } from "jsdom";
const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/imports/smart" });
Object.assign(globalThis, { window: dom.window, document: dom.window.document, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true });
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
const { render, fireEvent, cleanup, waitFor, within } = await import("@testing-library/react");
const require = createRequire(import.meta.url), React = require("react");
const { QueryClient, QueryClientProvider } = require("@tanstack/react-query");
const root = fileURLToPath(new URL("..", import.meta.url)), modules = new Map(), clients = [];
let request, permissions = ["imports.view", "imports.validate", "imports.commit", "imports.upload"], stubPanels = false;
const api = { api: (...args) => request(...args), apiText: (...args) => request(...args), DEMO_MODE: false, newOperationId: () => crypto.randomUUID(), money: value => value == null ? "—" : `₹${Number(value).toFixed(2)}` };
function load(path) {
  let filename = resolve(root, path); if (!existsSync(filename)) filename += existsSync(filename + ".tsx") ? ".tsx" : ".ts";
  if (modules.has(filename)) return modules.get(filename);
  const module = { exports: {} }, output = ts.transpileModule(readFileSync(filename, "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true, target: ts.ScriptTarget.ES2022 } }).outputText;
  new Function("require", "module", "exports", output)(name => {
    if (name === "@/lib/api" || (name === "./api" && filename.includes("/lib/"))) return api;
    if (name === "@/lib/business") return { useAccess: () => ({ can: p => permissions.includes(p) }), useMoney: () => api.money, useWorkspaceCurrency: () => "INR", refreshBusiness: client => client.invalidateQueries(), Permission: ({ permission, children }) => permissions.includes(permission) ? children : null };
    if (name === "@/components/AppShell" || name === "./AppShell") return { AppShell: ({ children }) => React.createElement("main", null, children) };
    if (stubPanels && ["@/components/AnalyticsReadiness", "@/components/ImportReconciliation", "@/components/DocumentRevisionReview"].includes(name)) return { [name.split("/").at(-1)]: () => null };
    if (name === "next/link") return { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) };
    if (name.startsWith("@/")) return load(name.slice(2));
    if (name.startsWith("./")) return load(resolve(dirname(filename), name));
    return require(name);
  }, module, module.exports); modules.set(filename, module.exports); return module.exports;
}
function mount(Component, props = {}) { const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } }); clients.push(client); return { ...render(React.createElement(QueryClientProvider, { client }, React.createElement(Component, props))), client }; }
const page = (content, number = 0, totalPages = 1, totalElements = content.length) => ({ content, number, totalPages, totalElements });
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.clear()); modules.clear(); permissions = ["imports.view", "imports.validate", "imports.commit", "imports.upload"]; stubPanels = false; window.history.replaceState(null, "", "/imports/smart"); });
after(() => dom.window.close());

test("return and transfer review failures show retry instead of a reassuring empty state", async () => {
  for (const name of ["ReturnReviewPanel", "TransferReviewPanel"]) {
    let failing = true;
    request = async path => { if (path === "/api/warehouses") return []; if (failing) throw new Error("Review service unavailable"); return []; };
    const ui = mount(load(`components/${name}`)[name], { sessionId: "s" });
    await ui.findByText("Review service unavailable"); assert.equal(ui.queryByText(/No ambiguous physical returns|No stock journals/), null);
    failing = false; fireEvent.click(ui.getByRole("button", { name: "Try again" }));
    await ui.findByText(/No ambiguous physical returns|No stock journals/); cleanup();
  }
});

test("return review reports a failed decision and allows a confirmed retry", async () => {
  let fail = true;
  request = async (path, init) => { if (init?.method === "POST") { if (fail) throw new Error("Decision could not be saved"); return {}; } if (path === "/api/warehouses") return []; if (path.startsWith("/api/products")) return page([]); return [{ id: "r1", voucherNumber: "CN-4", productName: "Item", normalizedQuantity: 1, resolutionStatus: "OPEN" }]; };
  const ui = mount(load("components/ReturnReviewPanel").ReturnReviewPanel, { sessionId: "s" });
  fireEvent.click(await ui.findByRole("button", { name: /CN-4/ })); fireEvent.click(ui.getByRole("button", { name: "Financial-only" }));
  await ui.findByText("Decision could not be saved"); fail = false; fireEvent.click(ui.getByRole("button", { name: "Financial-only" }));
  await ui.findByText(/Return review decision saved/);
});

test("accountant report controls expose only exports authorized for that report", () => {
  permissions = ["reports.export", "reports.view", "sales.view", "customers.view_outstanding", "audit.view"];
  const ui = mount(load("app/reports/page").default);
  assert.equal(ui.getAllByRole("button", { name: "Download CSV" }).length, 3);
  for (const name of ["Current Stock", "Product Master", "Purchase Invoices"]) {
    const card = ui.getByText(name).closest(".rounded.border"); assert.equal(within(card).queryByRole("button"), null);
  }
});

test("durable import submission keeps retry identity and restores a running job after remount", async () => {
  const writes = []; let jobs = [], first = true;
  request = async (_path, init) => { if (init?.method === "POST") { writes.push(JSON.parse(init.body)); if (first) { first = false; throw new Error("Connection lost"); } const job = { id: "job", operation: "COMMIT", status: "RUNNING", progress: 40, message: "Committing", createdAt: "2026-11-10", updatedAt: "2026-11-10" }; jobs = [job]; return job; } return jobs; };
  const { useImportJobs } = load("lib/importJobs");
  function Probe() { const state = useImportJobs("s", () => {}); const [error, setError] = React.useState(""); return React.createElement("div", null, React.createElement("p", null, state.active?.status || "Idle"), React.createElement("p", null, error), React.createElement("button", { disabled: state.busy, onClick: () => state.start("COMMIT", { dryRunId: "d" }).catch(e => setError(e.message)) }, "Commit")); }
  let ui = mount(Probe); await waitFor(() => assert.equal(ui.getByRole("button").disabled, false)); fireEvent.click(ui.getByRole("button")); await ui.findByText("Connection lost"); fireEvent.click(ui.getByRole("button")); await ui.findByText("RUNNING"); assert.equal(writes[0].requestId, writes[1].requestId); assert.equal(ui.getByRole("button").disabled, true);
  cleanup(); ui = mount(Probe); await ui.findByText("RUNNING"); assert.equal(ui.getByRole("button").disabled, true); assert.equal(writes.length, 2);
});

test("saved assistant opens permitted history, preserves follow-up identity on retry, and confirms deletion", async () => {
  const writes = []; let fail = true, deleted = false;
  const answer = { id: "a1", role: "assistant", content: "Sales evidence", createdAt: "2026-11-10T10:00:00Z", restricted: false, evidence: { sales: { title: "Sales source", asOf: "2026-11-10", period: { from: "2026-10-01", through: "2026-10-31", timeZone: "UTC" }, data: { total: 20 }, limitations: ["Partial coverage"] } } };
  request = async (path, init) => {
    if (init?.method === "DELETE") { deleted = true; return; }
    if (init?.method === "POST") { const body = JSON.parse(init.body); writes.push(body); if (fail) throw new Error("Provider unavailable"); return { conversationId: "c1", response: "Recovered answer", evidence: {}, messageId: "a2", requestId: body.requestId, status: "FALLBACK", limitations: [] }; }
    if (path.includes("/messages")) return page([answer, { id: "restricted", role: "assistant", content: "DO NOT DISPLAY", restricted: true, createdAt: "2026-11-09" }]);
    return page(deleted ? [] : [{ id: "c1", title: "October stock", updatedAt: "2026-11-10" }]);
  };
  const ui = mount(load("components/SavedAssistant").SavedAssistant);
  fireEvent.click(await ui.findByRole("button", { name: /October stock.*11/ })); await ui.findByText("Sales evidence"); assert.equal(ui.queryByText("DO NOT DISPLAY"), null);
  assert.match(ui.getByText(/Period: 2026-10-01/).textContent, /2026-10-31/); assert.ok(ui.getByText("Partial coverage"));
  fireEvent.change(ui.getByLabelText("Your question"), { target: { value: "Explain that total" } }); fireEvent.submit(ui.getByLabelText("Your question").closest("form")); await ui.findByText("Provider unavailable");
  fail = false; fireEvent.click(ui.getByRole("button", { name: "Retry same question" })); await ui.findByText("Recovered answer"); assert.equal(writes[0].conversationId, "c1"); assert.equal(writes[0].requestId, writes[1].requestId);
  fireEvent.click(ui.getByRole("button", { name: "Delete October stock" })); assert.equal(deleted, false); fireEvent.click(ui.getByRole("button", { name: "Delete conversation" })); await waitFor(() => assert.equal(deleted, true));
});

test("assistant cancellation stops waiting and keeps the original request available for recovery", async () => {
  let sent;
  request = async (_path, init) => { if (init?.method === "POST") { sent = JSON.parse(init.body); return new Promise((_resolve, reject) => init.signal.addEventListener("abort", () => reject(new Error("Aborted")), { once: true })); } return page([]); };
  const ui = mount(load("components/SavedAssistant").SavedAssistant);
  fireEvent.change(ui.getByLabelText("Your question"), { target: { value: "Check current stock" } }); fireEvent.submit(ui.getByLabelText("Your question").closest("form")); fireEvent.click(await ui.findByRole("button", { name: "Stop waiting" }));
  await ui.findByText(/The server may still finish/); assert.ok(ui.getByRole("button", { name: "Retry same question" })); assert.ok(sent.requestId); assert.equal(ui.queryByText("Aborted"), null);
});

test("coverage confirmation requires explicit dates and reconfirmation after edits", async () => {
  const writes = [];
  const data = { status: "PARTIAL", asOf: "2026-11-10", observedDays: 5, coveredDays: 0, coverageConfirmed: false, forecastStale: true, uncostedSalesLines: 2, estimatedCostLines: 0, warnings: ["History is incomplete"] };
  request = async (_path, init) => { if (init?.method === "PUT") writes.push(JSON.parse(init.body)); return data; };
  const ui = mount(load("components/AnalyticsReadiness").AnalyticsReadiness, { allowCoverage: true }); await ui.findByText("History is incomplete");
  const submit = ui.getByRole("button", { name: "Confirm history coverage" }); assert.equal(submit.disabled, true);
  fireEvent.change(ui.getByLabelText("Complete history from"), { target: { value: "2026-10-01" } }); fireEvent.change(ui.getByLabelText("Complete history through"), { target: { value: "2026-10-31" } }); fireEvent.click(ui.getByRole("checkbox")); assert.equal(submit.disabled, false);
  fireEvent.change(ui.getByLabelText("Complete history through"), { target: { value: "2026-10-30" } }); assert.equal(ui.getByRole("checkbox").checked, false); fireEvent.click(ui.getByRole("checkbox")); fireEvent.click(submit); await ui.findByText(/History coverage saved/);
  assert.deepEqual(writes, [{ from: "2026-10-01", through: "2026-10-30", complete: true }]);
});

test("smart dry-run pages beyond row 100, resets filters and session, and distinguishes failed fetches", async () => {
  stubPanels = true; const calls = []; let fail = false;
  const sessions = [{ id: "s1", name: "First import", status: "STAGED", recommendedStrategy: "SNAPSHOT_FIRST" }, { id: "s2", name: "Second import", status: "STAGED", recommendedStrategy: "SNAPSHOT_FIRST" }];
  request = async path => {
    calls.push(path);
    if (path.includes("/jobs")) return [];
    if (path.startsWith("/api/import-sessions?")) return page(sessions);
    if (path.includes("/dry-run/items")) { if (fail) throw new Error("Preview could not load"); const url = new URL(path, "http://localhost"), n = Number(url.searchParams.get("page")); return page([{ id: `${n}`, itemType: "PRODUCT", action: "CREATE", sourceRowNumber: n * 100 + 1, preview: { name: `Product on page ${n + 1}` } }], n, 2, 150); }
    if (path.endsWith("/dry-run")) return { id: "dry", available: true, status: "COMPLETED", summary: {} };
    if (path.endsWith("/commit-result")) return { available: false };
    if (path.endsWith("/staging")) return { summary: {}, reviewItems: [], vouchers: [], snapshots: [], products: [], warehouses: [], parties: [], cashbookEntries: [] };
    return { ...sessions.find(s => path.endsWith(s.id)), files: [], issues: [], plan: { strategy: "SNAPSHOT_FIRST", details: { stagingComplete: true } } };
  };
  const ui = mount(load("app/imports/smart/page").default); fireEvent.click(await ui.findByRole("button", { name: "Dry Run" }));
  await ui.findByText("Product on page 1"); const next = ui.getAllByRole("button", { name: "Next" }).find(button => !button.disabled); fireEvent.click(next); await ui.findByText("Product on page 2");
  fireEvent.change(ui.getByLabelText("Filter dry-run items by issue"), { target: { value: "WARNING" } }); await waitFor(() => assert.ok(calls.some(path => path.includes("page=0") && path.includes("issue=WARNING"))));
  fireEvent.click(ui.getByRole("button", { name: /Second import/ })); fireEvent.click(await ui.findByRole("button", { name: "Dry Run" })); assert.equal(ui.getByLabelText("Filter dry-run items by issue").value, "");
  fail = true; fireEvent.change(ui.getByLabelText("Filter dry-run items by type"), { target: { value: "PRODUCT" } }); await ui.findByText("Preview could not load"); assert.equal(ui.queryByText("No dry-run items match these filters."), null);
});

test("document revision approval binds the reviewed source hash and requires reason plus exact confirmation", async () => {
  const writes = []; const item = { voucherId: "v1", documentType: "SALE", number: "S-7", date: "2026-10-01", partyName: "Shop", sourceCompany: "Books", sourceRecordId: "guid", sourceRevision: "2", sourceHash: "reviewed-hash", destinationHash: "reviewed-destination", existingLines: [{ productName: "Rice", quantity: 2, rate: 5, amount: 10 }], proposedLines: [{ productName: "Rice", unitCode: "KG", quantity: 3, rate: 4, amount: 12 }], stockChanges: [{ productId: "p1", productName: "Rice", warehouseId: "w1", warehouseName: "Main warehouse", currentStock: 10, change: -1, projectedStock: 9 }], outcome: "CHANGED", eligible: true, approved: false, stockMovementCount: 0, existingTotal: 10, proposedTotal: 12 };
  request = async (_path, init) => { if (init?.method === "POST") { writes.push(JSON.parse(init.body)); return {}; } return { items: [item], page: 0, size: 20, totalElements: 1, totalPages: 1, newCount: 0, unchangedCount: 0, changedOnly: true }; };
  const ui = mount(load("components/DocumentRevisionReview").DocumentRevisionReview, { sessionId: "s" }); fireEvent.click(await ui.findByRole("button", { name: "Review revision" })); const button = ui.getByRole("button", { name: "Approve staged revision" }); assert.equal(button.disabled, true); assert.ok(within(ui.getByRole("table", { name: "Saved invoice lines" })).getByText("₹10.00")); assert.ok(within(ui.getByRole("table", { name: "Proposed source lines" })).getByText("₹12.00")); assert.ok(within(ui.getByRole("table", { name: "Projected stock after revision" })).getByText("Main warehouse"));
  fireEvent.change(ui.getByLabelText("Reason for approving this revision"), { target: { value: "Verified correction in source books" } }); fireEvent.change(ui.getByLabelText("Type APPLY DOCUMENT REVISION"), { target: { value: "APPLY DOCUMENT REVISION" } }); fireEvent.click(button); await ui.findByText(/Revision approved for the staged source version/); assert.equal(writes[0].expectedSourceHash, "reviewed-hash"); assert.equal(writes[0].expectedDestinationHash, "reviewed-destination"); assert.equal(writes[0].action, "APPLY_CORRECTION");
});

test("source reconciliation preserves unavailable values and explains differences from dashboard periods", async () => {
  request = async () => ({ sessionId: "s", status: "PARTIAL", generatedAt: "2026-11-10", currency: "INR", coverageFrom: "2026-10-01", coverageThrough: "2026-10-31", excludedRows: 2, unresolvedRows: 1, checks: [{ metric: "sales", label: "Sales total", sourceValue: 12.50, committedValue: null, difference: null, status: "NOT_AVAILABLE", unit: "currency", note: "Missing comparable invoice" }], warnings: ["Source contains unresolved rows"] });
  const ui = mount(load("components/ImportReconciliation").ImportReconciliation, { sessionId: "s" }); await ui.findByText("₹12.50"); assert.equal(ui.getAllByText("Not available").length, 2); assert.ok(ui.getByText(/Dashboard totals can include other imports/)); assert.ok(ui.getByText("Source contains unresolved rows"));
});

test("saved interrupted questions recover after reopening history without creating a new request", async () => {
  const writes = [];
  request = async (path, init) => {
    if (init?.method === "POST") { const body = JSON.parse(init.body); writes.push(body); if (writes.length === 1) throw Object.assign(new Error("Question still processing"), { status: 409 }); return { conversationId: "saved", response: "Recovered from the original run", evidence: {}, messageId: "answer", requestId: body.requestId, status: "COMPLETED", limitations: [] }; }
    if (path.includes("/messages")) return page([{ id: "question", role: "USER", content: "Check October stock", createdAt: "2026-11-10T10:00:00Z", restricted: false, status: "RUNNING", requestId: "original-request-id" }]);
    return page([{ id: "saved", title: "Interrupted question", updatedAt: "2026-11-10" }]);
  };
  const ui = mount(load("components/SavedAssistant").SavedAssistant);
  fireEvent.click(await ui.findByRole("button", { name: /Interrupted question.*11/ }));
  fireEvent.click(await ui.findByRole("button", { name: "Recover saved question" }));
  await ui.findByText(/retry shortly using the same question/);
  fireEvent.click(ui.getByRole("button", { name: "Retry same question" }));
  await ui.findByText("Recovered from the original run");
  assert.deepEqual(writes, Array(2).fill({ conversationId: "saved", message: "Check October stock", requestId: "original-request-id" }));
});

test("reconciliation pages all source checks and preserves overall status across pages", async () => {
  const calls = [];
  request = async path => { calls.push(path); const current = Number(new URL(path, "http://localhost").searchParams.get("page")); return { sessionId: "s", status: "DIFFERENCES", generatedAt: "2026-11-10", currency: "INR", excludedRows: 0, unresolvedRows: 0, checks: [{ metric: `stock-${current}`, label: `Stock balance on page ${current + 1}`, sourceValue: 10, committedValue: 8, difference: -2, status: "DIFFERENCE", unit: "base units", note: "Source balance" }], warnings: [], page: current, size: 100, totalChecks: 101, totalPages: 2 }; };
  const ui = mount(load("components/ImportReconciliation").ImportReconciliation, { sessionId: "s" });
  await ui.findByText("Stock balance on page 1"); fireEvent.click(ui.getByRole("button", { name: "Next" }));
  await ui.findByText("Stock balance on page 2"); assert.ok(calls.some(path => path.endsWith("?page=1&size=100"))); assert.ok(ui.getByText("DIFFERENCES")); assert.equal(ui.getByRole("button", { name: "Next" }).disabled, true);
});

test("restricted analytics costs stay hidden and read-only import viewers do not query privileged jobs", async () => {
  let jobCalls = 0;
  request = async path => { if (path.includes("/jobs")) { jobCalls++; throw new Error("Forbidden"); } return { status: "PARTIAL", asOf: "2026-11-10", observedDays: 31, coveredDays: 0, coverageConfirmed: false, forecastStale: true, uncostedSalesLines: null, estimatedCostLines: null, warnings: [] }; };
  const { useImportJobs } = load("lib/importJobs");
  function ReadOnlyJobs() { const state = useImportJobs("s", () => {}, false); return React.createElement("p", null, state.busy ? "Blocked" : "Read-only review available"); }
  const ui = mount(load("components/AnalyticsReadiness").AnalyticsReadiness); await ui.findByText("Restricted for your role"); assert.ok(ui.getByText("31 calendar days between first and last sale"));
  mount(ReadOnlyJobs); assert.equal(jobCalls, 0); assert.ok(ui.getByText("Read-only review available"));
});

test("read-only smart imports avoid validator and commit requests and omit restricted tabs", async () => {
  stubPanels = true; permissions = ["imports.view"]; const calls = [];
  request = async path => {
    calls.push(path);
    if (path.startsWith("/api/import-sessions?")) return page([{ id: "view-only", name: "Read-only import", status: "STAGED" }]);
    if (path.endsWith("/staging")) return { summary: {}, reviewItems: [], vouchers: [], snapshots: [], products: [], warehouses: [], parties: [], cashbookEntries: [] };
    return { id: "view-only", name: "Read-only import", status: "STAGED", files: [], issues: [], plan: {} };
  };
  const ui = mount(load("app/imports/smart/page").default);
  await ui.findByRole("navigation", { name: "Smart import workspace" });
  assert.equal(ui.queryByRole("button", { name: "Dry Run" }), null);
  assert.equal(ui.queryByRole("button", { name: "Commit" }), null);
  assert.equal(calls.some(path => /\/(jobs|dry-run|commit-result|reconciliation)(\?|$)/.test(path)), false);
});

test("document revision conflicts require refreshing and confirming the updated destination preview", async () => {
  const writes = []; let hash = "original-destination";
  request = async (_path, init) => {
    if (init?.method === "POST") { const body = JSON.parse(init.body); writes.push(body); if (writes.length === 1) { hash = "updated-destination"; throw Object.assign(new Error("Saved invoice changed; refresh the revision preview"), { status: 409 }); } return {}; }
    return { items: [{ voucherId: "v1", documentType: "SALE", number: "S-8", sourceHash: "reviewed-source", destinationHash: hash, outcome: "CHANGED", eligible: true, approved: false, stockMovementCount: 0, existingTotal: 10, proposedTotal: 12, existingLines: [], proposedLines: [], stockChanges: [] }], page: 0, size: 20, totalElements: 1, totalPages: 1, newCount: 0, unchangedCount: 0, changedOnly: true };
  };
  const ui = mount(load("components/DocumentRevisionReview").DocumentRevisionReview, { sessionId: "s" });
  fireEvent.click(await ui.findByRole("button", { name: "Review revision" }));
  fireEvent.change(ui.getByLabelText("Reason for approving this revision"), { target: { value: "Checked books" } }); fireEvent.change(ui.getByLabelText("Type APPLY DOCUMENT REVISION"), { target: { value: "APPLY DOCUMENT REVISION" } }); fireEvent.click(ui.getByRole("button", { name: "Approve staged revision" }));
  await ui.findByText("Saved invoice changed; refresh the revision preview");
  fireEvent.click(ui.getByRole("button", { name: "Refresh revision preview" }));
  await waitFor(() => assert.equal(ui.getByRole("button", { name: "Refresh revision preview" }).disabled, false));
  assert.equal(ui.queryByLabelText("Type APPLY DOCUMENT REVISION"), null);
  fireEvent.click(ui.getByRole("button", { name: "Review revision" }));
  assert.equal(ui.getByLabelText("Type APPLY DOCUMENT REVISION").value, ""); assert.equal(ui.getByRole("button", { name: "Approve staged revision" }).disabled, true);
  fireEvent.change(ui.getByLabelText("Reason for approving this revision"), { target: { value: "Rechecked updated invoice" } }); fireEvent.change(ui.getByLabelText("Type APPLY DOCUMENT REVISION"), { target: { value: "APPLY DOCUMENT REVISION" } }); fireEvent.click(ui.getByRole("button", { name: "Approve staged revision" }));
  await ui.findByText(/Revision approved for the staged source version/);
  assert.equal(writes[0].expectedDestinationHash, "original-destination"); assert.equal(writes[1].expectedDestinationHash, "updated-destination"); assert.equal(writes[1].expectedSourceHash, "reviewed-source");
});

test("source revisions request server pages, retain session totals, and reset review on navigation", async () => {
  const calls = [];
  request = async path => {
    calls.push(path); const current = Number(new URL(path, "http://localhost").searchParams.get("page"));
    return { items: [{ voucherId: `v${current}`, documentType: "SALE", number: `S-${current + 1}`, sourceHash: "source", destinationHash: "destination", outcome: "CHANGED", eligible: true, approved: false, stockMovementCount: 0, existingTotal: 10, proposedTotal: 12, existingLines: [{ productName: "Rice", quantity: 2, rate: 5, amount: 10 }], proposedLines: [{ productName: "Rice", quantity: 3, rate: 4, amount: 12 }], stockChanges: [], existingLineCount: 250, proposedLineCount: 251, linePreviewTruncated: true }], page: current, size: 20, totalElements: 21, totalPages: 2, newCount: 1000, unchangedCount: 2000, changedOnly: true };
  };
  const ui = mount(load("components/DocumentRevisionReview").DocumentRevisionReview, { sessionId: "s" });
  fireEvent.click(await ui.findByRole("button", { name: "Review revision" }));
  assert.ok(ui.getByText(/showing 1 of 250 saved lines and 1 of 251 source lines/));
  fireEvent.change(ui.getByLabelText("Reason for approving this revision"), { target: { value: "First page review" } });
  fireEvent.click(ui.getByRole("button", { name: "Next" }));
  await ui.findByText("S-2"); assert.ok(calls.some(path => path.endsWith("/document-revisions?page=1&size=20&changedOnly=true"))); assert.equal(ui.queryByLabelText("Reason for approving this revision"), null); assert.ok(ui.getByText("Across this session: 1000 new · 2000 unchanged documents.")); assert.equal(ui.getByRole("button", { name: "Next" }).disabled, true);
});

test("read-only import roles never request sensitive document revision previews", () => {
  permissions = ["imports.view"]; let calls = 0; request = async () => { calls++; throw new Error("Forbidden"); };
  const ui = mount(load("components/DocumentRevisionReview").DocumentRevisionReview, { sessionId: "s" });
  assert.ok(ui.getByText("Import validation permission is required to view source document revisions.")); assert.equal(calls, 0);
});
