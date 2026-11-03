import assert from "node:assert/strict";
import test, { afterEach, after } from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import ts from "typescript";
import { JSDOM } from "jsdom";

const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost" });
globalThis.window = dom.window;
globalThis.document = dom.window.document;
globalThis.HTMLElement = dom.window.HTMLElement;
globalThis.IS_REACT_ACT_ENVIRONMENT = true;
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
const { render, fireEvent, cleanup, waitFor } = await import("@testing-library/react");
const require = createRequire(import.meta.url);
const React = require("react");
const { QueryClient, QueryClientProvider } = require("@tanstack/react-query");
let request = () => { throw new Error("Unexpected API request"); };
function component(path) {
  const source = readFileSync(new URL(`../${path}.tsx`, import.meta.url), "utf8");
  const output = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true
  } }).outputText;
  const module = { exports: {} };
  new Function("require", "module", "exports", output)((name) => {
    if (name === "@/lib/api") return { api: (...args) => request(...args) };
    if (name.startsWith("@/components/")) return component(name.slice(2));
    return require(name);
  }, module, module.exports);
  return module.exports;
}
const { VoucherRateReviewForm } = component("components/VoucherRateReviewForm");
afterEach(cleanup);
after(() => dom.window.close());

test("bulk exclusion supplies the saved workspace to the queue after confirmation", async () => {
  const calls = [];
  const saved = { reviewItems: [], summary: { vouchersStaged: 2 } };
  request = async (path, init) => {
    calls.push({ path, body: JSON.parse(init.body) });
    return path.endsWith("/preview")
      ? { matchingRows: 2, matchingVouchers: 2, confirmation: "APPLY TO 2 MATCHING ROWS", rows: [] }
      : saved;
  };
  let refreshed;
  const ui = render(React.createElement(QueryClientProvider, {
    client: new QueryClient({ defaultOptions: { mutations: { gcTime: 0 } } })
  }, React.createElement(VoucherRateReviewForm, {
    sessionId: "session", item: { id: "issue", code: "INVALID_RATE", context: { eligibleActions: ["EXCLUDE_VOUCHER"] } },
    onResolved: async (workspace) => { refreshed = workspace; }, onRebuild: async () => {}
  })));
  fireEvent.click(ui.getByRole("button", { name: "Preview matching rows" }));
  const apply = await ui.findByRole("button", { name: "Apply to 2 matching rows" });
  assert.equal(apply.disabled, true);
  fireEvent.click(ui.getByLabelText(/I reviewed this preview/));
  fireEvent.click(apply);
  await waitFor(() => assert.equal(refreshed, saved));
  assert.equal(calls[1].body.expectedCount, 2);
  assert.equal(calls[1].body.confirmation, "APPLY TO 2 MATCHING ROWS");
  assert.equal(calls[1].body.action, "EXCLUDE_VOUCHER");
});

test("legacy review offers rebuild, then displays refreshed actions without remounting", async () => {
  let rebuilds = 0;
  const client = new QueryClient({ defaultOptions: { mutations: { gcTime: 0 } } });
  const view = (context) => React.createElement(QueryClientProvider, { client },
    React.createElement(VoucherRateReviewForm, {
      sessionId: "session", item: { id: "issue", code: "INVALID_RATE", context },
      onResolved: async () => {}, onRebuild: async () => { rebuilds++; }
    }));
  const ui = render(view(undefined));
  assert.equal(ui.queryByRole("combobox"), null);
  fireEvent.click(ui.getByRole("button", { name: "Rebuild to enable actions" }));
  await waitFor(() => assert.equal(rebuilds, 1));
  ui.rerender(view({ eligibleActions: ["CONFIRM_ZERO_VALUE", "ENTER_VERIFIED_RATE", "EXCLUDE_VOUCHER", "KEEP_UNRESOLVED"] }));
  assert.equal(ui.getByRole("combobox").value, "CONFIRM_ZERO_VALUE");
  assert.ok(ui.getByRole("option", { name: "Enter verified source rate" }));
  assert.equal(ui.queryByRole("option", { name: "Derive rate from amount and quantity" }), null);
  assert.equal(ui.getByRole("button", { name: "Confirm zero-valued item" }).disabled, true);
});

test("empty eligibility displays rebuild failure instead of an empty dropdown", async () => {
  const ui = render(React.createElement(QueryClientProvider, { client: new QueryClient({ defaultOptions: { mutations: { gcTime: 0 } } }) },
    React.createElement(VoucherRateReviewForm, {
      sessionId: "session", item: { id: "issue", code: "INVALID_RATE", context: { eligibleActions: [] } },
      onResolved: async () => {}, onRebuild: async () => { throw new Error("Workspace rebuild failed"); }
    })));
  fireEvent.click(ui.getByRole("button", { name: "Rebuild to enable actions" }));
  await waitFor(() => assert.equal(ui.getByRole("alert").textContent, "Workspace rebuild failed"));
  assert.equal(ui.queryByRole("combobox"), null);
});
