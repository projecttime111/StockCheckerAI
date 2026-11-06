import assert from "node:assert/strict";
import test, { after, afterEach } from "node:test";
import { readFileSync, existsSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
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
const root = fileURLToPath(new URL("..", import.meta.url));
let request;
const modules = new Map();
const apiModule = {
  api: (...args) => request(...args), getToken: () => "test-token",
  newOperationId: () => crypto.randomUUID(), localDate: () => "2026-11-06",
  money: (value, currency = "INR") => value == null ? "—" : new Intl.NumberFormat("en-IN", { style: "currency", currency, minimumFractionDigits: 2 }).format(value)
};
function load(path) {
  let filename = path;
  if (!existsSync(filename)) filename += existsSync(path + ".tsx") ? ".tsx" : ".ts";
  if (modules.has(filename)) return modules.get(filename);
  const module = { exports: {} };
  const source = ts.transpileModule(readFileSync(filename, "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true, target: ts.ScriptTarget.ES2022 } }).outputText;
  const localRequire = name => {
    if (name === "@/lib/api" || (name === "./api" && filename.includes("lib/"))) return apiModule;
    if (name === "@/components/AppShell") return { AppShell: ({ children }) => React.createElement("main", null, children) };
    if (name === "next/link") return { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) };
    if (name.startsWith("@/")) return load(resolve(root, name.slice(2)));
    if (name.startsWith("./")) return load(resolve(dirname(filename), name));
    return require(name);
  };
  new Function("require", "module", "exports", source)(localRequire, module, module.exports);
  modules.set(filename, module.exports);
  return module.exports;
}
const { InvoiceWorkspace } = load(resolve(root, "components/InvoiceWorkspace"));
const Portal = load(resolve(root, "app/portal/page")).default;
const StockPage = load(resolve(root, "app/stock/page")).default;
const { RecordPicker } = load(resolve(root, "components/RecordPicker"));
const clients = [];
function mount(component, props) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  clients.push(client);
  return render(React.createElement(QueryClientProvider, { client }, React.createElement(component, props)));
}
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.clear()); });
after(() => dom.window.close());
const page = content => ({ content, number: 0, totalPages: 1, totalElements: content.length });

test("sale workflow uses selected warehouse stock, saves, resets invoice and refreshes availability", async () => {
  let quantity = 7;
  const writes = [];
  request = async (path, init) => {
    if (path === "/api/auth/me/permissions") return { permissions: ["sales.create", "sales.view"], role: "OWNER" };
    if (path === "/api/tenants/current") return { currency: "INR" };
    if (path === "/api/warehouses") return [{ id: "w1", name: "First" }, { id: "w2", name: "Second" }];
    if (path.startsWith("/api/products")) return page([{ id: "p1", name: "Product", defaultSalesPrice: 12.50 }]);
    if (path.startsWith("/api/customers")) return page([]);
    if (path.startsWith("/api/stock/current")) return [{ currentStock: path.includes("warehouseId=w2") ? 0 : quantity }];
    if (init?.method === "POST") { const body = JSON.parse(init.body); writes.push(body); quantity -= body.items[0].quantity; return { invoiceNumber: body.invoiceNumber }; }
    return page([]);
  };
  const ui = mount(InvoiceWorkspace, { kind: "sales" });
  await ui.findByRole("button", { name: "Create sale" });
  await ui.findByRole("option", { name: "Product" });
  fireEvent.change(ui.getByLabelText("Warehouse"), { target: { value: "w2" } });
  fireEvent.change(ui.getByLabelText("Product"), { target: { value: "p1" } });
  await waitFor(() => assert.match(ui.getByText(/Available in selected warehouse/).textContent, /0$/));
  fireEvent.change(ui.getByLabelText("Warehouse"), { target: { value: "w1" } });
  await waitFor(() => assert.match(ui.getByText(/Available in selected warehouse/).textContent, /7$/));
  const firstNumber = ui.getByLabelText("Invoice no").value;
  fireEvent.submit(ui.getByLabelText("Invoice no").closest("form"));
  await ui.findByText(`Saved invoice ${firstNumber}. Ready for another sale.`);
  assert.equal(writes[0].items[0].rate, 12.50);
  assert.notEqual(ui.getByLabelText("Invoice no").value, firstNumber);
  assert.equal(ui.getByLabelText("Product").value, "");
});

test("read-only roles do not see invoice creation controls", async () => {
  request = async path => path === "/api/auth/me/permissions" ? { role: "AUDITOR", permissions: ["sales.view"] } : path === "/api/tenants/current" ? { currency: "INR" } : page([]);
  const ui = mount(InvoiceWorkspace, { kind: "sales" });
  await ui.findByText("No invoices yet.");
  assert.equal(ui.queryByRole("button", { name: "Create sale" }), null);
});

test("record picker retrieves later pages and searches the backend", async () => {
  const calls = [];
  request = async path => { calls.push(path); return { content: [{ id: path.includes("page=1") ? "late" : "first", name: path.includes("page=1") ? "Later product" : "First product" }], number: path.includes("page=1") ? 1 : 0, totalPages: 2, totalElements: 30 }; };
  let selected;
  const ui = mount(RecordPicker, { path: "/api/products", label: "Product", value: "", onChange: row => { selected = row; } });
  await ui.findByRole("option", { name: "First product" });
  fireEvent.click(ui.getByRole("button", { name: "Next" }));
  await ui.findByRole("option", { name: "Later product" });
  fireEvent.change(ui.getByLabelText("Product"), { target: { value: "late" } });
  assert.equal(selected.id, "late");
  fireEvent.change(ui.getByLabelText("Search product"), { target: { value: "special" } });
  await waitFor(() => assert.ok(calls.some(path => path.includes("page=0&query=special"))));
});

test("dealer cart supports correction and reports order failure visibly", async () => {
  request = async (path, init) => {
    if (path === "/api/auth/me/permissions") return { role: "CUSTOMER_USER", permissions: ["portal.customer.order_create"] };
    if (path === "/api/auth/me") return { role: "CUSTOMER_USER" };
    if (path === "/api/tenants/current") return { currency: "USD" };
    if (path === "/api/portal/products") return [{ productId: "p1", productName: "Test item", price: 10.50, availableStock: 10 }];
    if (path === "/api/portal/outstanding") return { outstanding: 0 };
    if (init?.method === "POST") throw new Error("Order could not be saved");
    return [];
  };
  const ui = mount(Portal);
  fireEvent.click(await ui.findByRole("button", { name: "Add" }));
  fireEvent.click(ui.getByRole("button", { name: "Add" }));
  assert.equal(ui.getByLabelText("Quantity for Test item").value, "2");
  fireEvent.change(ui.getByLabelText("Quantity for Test item"), { target: { value: "1" } });
  assert.equal(ui.getByLabelText("Quantity for Test item").value, "1");
  fireEvent.click(ui.getByRole("button", { name: "Remove Test item" }));
  assert.equal(ui.queryByLabelText("Quantity for Test item"), null);
  fireEvent.click(ui.getByRole("button", { name: "Add" }));
  fireEvent.click(ui.getByRole("button", { name: "Place order" }));
  await ui.findByText("Order could not be saved");
  assert.equal(ui.getByLabelText("Quantity for Test item").value, "1");
});

test("stock adjustment preserves its idempotency key after an uncertain result and rotates for the next operation", async () => {
  const writes = [];
  request = async (path, init) => {
    if (path === "/api/auth/me/permissions") return { role: "OWNER", permissions: ["stock.adjust"] };
    if (path === "/api/tenants/current") return { currency: "INR" };
    if (path === "/api/warehouses") return [{ id: "w1", name: "Main" }];
    if (path.startsWith("/api/products")) return page([{ id: "p1", name: "Product" }]);
    if (path === "/api/stock/adjustment") {
      writes.push(init);
      if (writes.length === 1) throw new Error("Request timed out; check status before retrying");
      return { id: "movement" };
    }
    return [];
  };
  const ui = mount(StockPage);
  await ui.findByRole("option", { name: "Product" });
  fireEvent.change(ui.getByLabelText("Product"), { target: { value: "p1" } });
  fireEvent.change(ui.getByLabelText("Warehouse"), { target: { value: "w1" } });
  fireEvent.change(ui.getByLabelText("Quantity delta"), { target: { value: "2" } });
  fireEvent.submit(ui.getByLabelText("Quantity delta").closest("form"));
  await ui.findAllByText(/Request timed out/);
  fireEvent.submit(ui.getByLabelText("Quantity delta").closest("form"));
  await ui.findByText("Stock adjustment saved.");
  assert.equal(writes[0].headers["Idempotency-Key"], writes[1].headers["Idempotency-Key"]);
  fireEvent.change(ui.getByLabelText("Product"), { target: { value: "p1" } });
  fireEvent.change(ui.getByLabelText("Warehouse"), { target: { value: "w1" } });
  fireEvent.change(ui.getByLabelText("Quantity delta"), { target: { value: "2" } });
  fireEvent.submit(ui.getByLabelText("Quantity delta").closest("form"));
  await waitFor(() => assert.equal(writes.length, 3));
  assert.notEqual(writes[1].headers["Idempotency-Key"], writes[2].headers["Idempotency-Key"]);
});
