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
const cache = new Map();
let request;

function component(path) {
  if (cache.has(path)) return cache.get(path);
  const source = readFileSync(new URL(`../${path}.tsx`, import.meta.url), "utf8");
  const output = ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true
  } }).outputText;
  const module = { exports: {} };
  const localRequire = (name) => {
    if (name === "@/lib/api") return { api: (...args) => request(...args) };
    if (name === "next/link") return { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) };
    if (name.startsWith("@/components/")) return component(name.slice(2));
    return require(name);
  };
  new Function("require", "module", "exports", output)(localRequire, module, module.exports);
  cache.set(path, module.exports);
  return module.exports;
}
const { RetailSaleReviewForm } = component("components/RetailSaleReviewForm");
const { BulkRetailSaleForm } = component("components/BulkRetailSaleForm");
const { CashbookReviewPanel } = component("components/CashbookReviewPanel");
afterEach(() => cleanup());
after(() => dom.window.close());

test("empty cashbook review can recheck unposted entries without posting a payment", async () => {
  let entries = [];
  const posted = [];
  request = async (path, options) => {
    if (options?.method === "POST") {
      posted.push(path);
      entries = [{ id: "expense", sourceRowNumber: 50, partyName: "Cash", amount: 200,
        direction: "PAYMENT", cashbookMatchStatus: "UNMATCHED_REVIEW", candidates: [],
        sourceLedgerDetails: "OTHER_CHARGE:Travelling A/c.:-200" }];
      return { unpostedReviewCount: 1, businessRecordsChanged: 0 };
    }
    return entries;
  };
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  const ui = render(React.createElement(QueryClientProvider, { client }, React.createElement(CashbookReviewPanel, { sessionId: "session" })));
  fireEvent.click(await ui.findByRole("button", { name: "Recheck unposted entries" }));
  await waitFor(() => assert.match(ui.getByRole("status").textContent, /1 unposted entries need review/));
  assert.equal(posted.length, 1);
  assert.equal(posted[0], "/api/import-sessions/session/cashbook-review/recheck");
  assert.match((await ui.findByText(/Source ledgers:/)).textContent, /Travelling/);
  client.clear();
});

test("retail form requires method and both confirmations; preserves combined method and paise", () => {
  const recorded = [];
  const ui = render(React.createElement(RetailSaleReviewForm, {
    amount: 250.75, direction: "RECEIPT", pending: false, onRecord: (value) => recorded.push(value), onCancel() {}
  }));
  const button = ui.getByRole("button", { name: "Confirm and record retail sale" });
  assert.equal(button.disabled, true);
  assert.match(ui.getByRole("heading").textContent, /250\.75/);
  fireEvent.change(ui.getByLabelText("Collection method"), { target: { value: "CASH_AND_UPI" } });
  fireEvent.click(ui.getByLabelText(/I confirm this is money received/));
  assert.equal(button.disabled, true);
  fireEvent.click(ui.getByLabelText(/This amount is not already included/));
  fireEvent.click(button);
  assert.equal(recorded.length, 1);
  assert.equal(recorded[0].collectionMethod, "CASH_AND_UPI");
  assert.equal(recorded[0].confirmation, "RECORD RETAIL SALES");
  assert.equal(recorded[0].notAlreadyRecorded, true);
});

test("source PAYMENT requires explanation and cancel does not record", () => {
  let records = 0;
  let cancelled = false;
  const ui = render(React.createElement(RetailSaleReviewForm, {
    amount: 500, direction: "PAYMENT", pending: false, onRecord: () => records++, onCancel: () => { cancelled = true; }
  }));
  fireEvent.change(ui.getByLabelText("Collection method"), { target: { value: "UPI" } });
  ui.getAllByRole("checkbox").forEach((checkbox) => fireEvent.click(checkbox));
  const button = ui.getByRole("button", { name: "Confirm and record retail sale" });
  assert.equal(button.disabled, true);
  fireEvent.change(ui.getByLabelText("Reason this is retail money received"), { target: { value: "Shop collections grouped in Tally" } });
  assert.equal(button.disabled, false);
  fireEvent.click(ui.getByRole("button", { name: "Cancel" }));
  assert.equal(cancelled, true);
  assert.equal(records, 0);
});

test("bulk form previews the exact selection and requires an explanation for payment-direction rows", () => {
  const recorded = [];
  const ui = render(React.createElement(BulkRetailSaleForm, {
    rows: [
      { entryDate: "2026-06-20", amount: 150.25, direction: "RECEIPT" },
      { entryDate: "2026-06-21", amount: 500.50, direction: "PAYMENT" }
    ],
    pending: false,
    onRecord: (value) => recorded.push(value),
    onCancel() {}
  }));
  assert.match(ui.getByText(/2 selected/).textContent, /650\.75/);
  const button = ui.getByRole("button", { name: "Confirm and record 2 retail sales" });
  fireEvent.change(ui.getByLabelText("Collection method for every selected entry"), { target: { value: "CASH_AND_UPI" } });
  fireEvent.click(ui.getByLabelText(/I checked these entries/));
  fireEvent.click(ui.getByLabelText(/These amounts are not already included/));
  assert.equal(button.disabled, true);
  fireEvent.change(ui.getByLabelText("Reason these are retail receipts"), { target: { value: "Verified Cash and GPay counter totals" } });
  assert.equal(button.disabled, false);
  fireEvent.click(button);
  assert.equal(recorded.length, 1);
  assert.equal(recorded[0].collectionMethod, "CASH_AND_UPI");
  assert.equal(recorded[0].note, "Verified Cash and GPay counter totals");
  assert.equal(recorded[0].receivedFromRetailSales, true);
  assert.equal(recorded[0].notAlreadyRecorded, true);
});

test("cashbook keyword selection posts one guarded bulk request for all filtered rows", async () => {
  let entries = [
    { id: "cash", sourceRowNumber: 2, entryDate: "2026-06-20", partyName: "Cash Counter",
      amount: 150.25, direction: "RECEIPT", referenceNumber: "CASH-1", cashbookMatchStatus: "UNMATCHED_REVIEW", candidates: [] },
    { id: "gpay", sourceRowNumber: 3, entryDate: "2026-06-21", partyName: "Cash GPay Counter",
      amount: 500.50, direction: "PAYMENT", referenceNumber: "GPAY-1", cashbookMatchStatus: "UNMATCHED_REVIEW", candidates: [] },
    { id: "transfer", sourceRowNumber: 4, entryDate: "2026-06-22", partyName: "Owner Transfer",
      amount: 900, direction: "RECEIPT", referenceNumber: "TRANSFER-1", cashbookMatchStatus: "UNMATCHED_REVIEW", candidates: [] }
  ];
  const posted = [];
  const confirmations = [];
  const previousConfirm = window.confirm;
  window.confirm = (message) => { confirmations.push(message); return true; };
  request = async (path, options) => {
    if (options?.method === "POST") {
      const body = JSON.parse(options.body);
      posted.push({ path, body });
      entries = entries.filter((entry) => !body.entryIds.includes(entry.id));
      return {
        requestedCount: 2, recordedCount: 2, duplicateCount: 0, failedCount: 0,
        recordedAmount: 650.75, partialSuccess: false,
        results: body.entryIds.map((entryId, index) => ({ entryId, status: "RECORDED", message: "Recorded", sourceRowNumber: index + 2 }))
      };
    }
    return entries;
  };
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  try {
    const ui = render(React.createElement(QueryClientProvider, { client }, React.createElement(CashbookReviewPanel, { sessionId: "session" })));
    const filter = await ui.findByLabelText("Filter cashbook entries");
    fireEvent.change(filter, { target: { value: "Cash" } });
    fireEvent.click(ui.getByRole("button", { name: "Select all 2 filtered" }));
    assert.match(ui.getAllByText(/2 selected/).map((item) => item.textContent).join(" "), /650\.75/);
    fireEvent.change(ui.getByLabelText("Collection method for every selected entry"), { target: { value: "CASH_AND_UPI" } });
    fireEvent.change(ui.getByLabelText("Reason these are retail receipts"), { target: { value: "Verified shop counter receipts" } });
    fireEvent.click(ui.getByLabelText(/I checked these entries/));
    fireEvent.click(ui.getByLabelText(/These amounts are not already included/));
    fireEvent.click(ui.getByRole("button", { name: "Confirm and record 2 retail sales" }));

    await waitFor(() => assert.match(ui.getByRole("status").textContent, /2 retail sales recorded/));
    assert.equal(posted.length, 1);
    assert.equal(posted[0].path, "/api/import-sessions/session/cashbook-review/retail-sales/bulk");
    assert.deepEqual(posted[0].body.entryIds, ["cash", "gpay"]);
    assert.equal(posted[0].body.confirmation, "RECORD 2 RETAIL SALES");
    assert.equal(posted[0].body.collectionMethod, "CASH_AND_UPI");
    assert.match(confirmations[0], /2 selected entries totaling/);
    assert.match(confirmations[0], /650\.75/);
  } finally {
    window.confirm = previousConfirm;
    client.clear();
  }
});

test("cashbook action posts explicit retail decision and refreshes review and sales", async () => {
  let entries = [{ id: "entry", sourceRowNumber: 12, entryDate: "2026-08-31", partyName: "Cash",
    amount: 500, direction: "RECEIPT", cashbookMatchStatus: "UNMATCHED_REVIEW", candidates: [] }];
  const posted = [];
  request = async (path, options) => {
    if (options?.method === "POST") {
      posted.push({ path, body: JSON.parse(options.body) });
      entries = [];
      return { retailSaleRecorded: true };
    }
    return entries;
  };
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  const invalidated = [];
  const original = client.invalidateQueries.bind(client);
  client.invalidateQueries = (filters) => { invalidated.push(filters.queryKey[0]); return original(filters); };
  const ui = render(React.createElement(QueryClientProvider, { client }, React.createElement(CashbookReviewPanel, { sessionId: "session" })));
  fireEvent.click(await ui.findByRole("button", { name: "Retail counter sale" }));
  fireEvent.change(ui.getByLabelText("Collection method"), { target: { value: "CASH" } });
  fireEvent.click(ui.getByLabelText(/I confirm this is money received/));
  fireEvent.click(ui.getByLabelText(/This amount is not already included/));
  fireEvent.click(ui.getByRole("button", { name: "Confirm and record retail sale" }));
  await waitFor(() => assert.match(ui.getByRole("status").textContent, /Retail counter sale recorded/));
  assert.equal(posted.length, 1);
  assert.equal(posted[0].path, "/api/import-sessions/session/cashbook-review/entry/resolve");
  assert.equal(posted[0].body.action, "RECORD_RETAIL_SALE");
  assert.equal(posted[0].body.targetId, undefined);
  assert.ok(invalidated.includes("retail-counter-sales"));
  assert.ok(invalidated.includes("smart-import-staging"));
  client.clear();
});
