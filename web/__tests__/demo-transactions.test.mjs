import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import ts from "typescript";

function demo(storage = new Map()) {
  const window = { localStorage: { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value) } };
  const source = ts.transpileModule(readFileSync(new URL("../lib/demoApi.ts", import.meta.url), "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText;
  const module = { exports: {} };
  new Function("module", "exports", "window", "process", "setTimeout", source)(module, module.exports, window, { env: { NEXT_PUBLIC_DEMO_MODE: "true" } }, fn => { fn(); return 0; });
  return { api: module.exports.demoApi, storage };
}
const post = body => ({ method: "POST", body: JSON.stringify(body) });

test("demo invoice totals, stock, balances, dashboards and reloads share persisted state", async () => {
  const { api, storage } = demo();
  await api("/api/auth/register", post({ email: "ledger@example.test", businessName: "Ledger", businessMode: "RETAIL" }));
  const product = await api("/api/products", post({ name: "Test", defaultPurchasePrice: 10, defaultSalesPrice: 15, categoryName: "Test", reorderPoint: 1 }));
  const customer = await api("/api/customers", post({ name: "Buyer" }));
  const supplier = await api("/api/suppliers", post({ name: "Supplier" }));
  const purchase = await api("/api/purchases", post({ invoiceNumber: "P-1", invoiceDate: new Date().toISOString().slice(0, 10), supplierId: supplier.id, warehouseId: "w1", items: [{ productId: product.id, quantity: 2, rate: 10 }] }));
  assert.equal(purchase.totalAmount, 21);
  assert.equal((await api("/api/stock/current"))[0].currentStock, 2);
  assert.equal((await api("/api/suppliers?size=25")).content[0].payable, 21);
  const sale = await api("/api/sales", post({ invoiceNumber: "S-1", invoiceDate: new Date().toISOString().slice(0, 10), customerId: customer.id, warehouseId: "w1", items: [{ productId: product.id, quantity: 1, rate: 15 }] }));
  assert.equal(sale.totalAmount, 15.75);
  const payment = { ...post({ amount: 5.50, paymentDate: "2026-11-06" }), headers: { "Idempotency-Key": "payment-once" } };
  await api(`/api/customers/${customer.id}/payments`, payment);
  await api(`/api/customers/${customer.id}/payments`, payment);
  assert.equal((await api("/api/customers/summary")).outstandingReceivables, 10.25);
  const summary = await api("/api/dashboard/summary");
  assert.equal(summary.monthlySales, 15.75);
  assert.equal(summary.grossProfit, 5);
  assert.equal(summary.totalStockValue, 10);
  const loaded = demo(storage).api;
  assert.equal((await loaded("/api/stock/current"))[0].currentStock, 1);
  assert.equal((await loaded("/api/sales?size=25")).content[0].id, sale.id);
  await assert.rejects(api("/api/sales", post({ invoiceNumber: "S-1", invoiceDate: "2026-11-06", warehouseId: "w1", items: [{ productId: product.id, quantity: 1, rate: 15 }] })), /already exists/);
  assert.equal((await loaded("/api/stock/current"))[0].currentStock, 1);
});

test("demo selected warehouse adjustments and portal orders persist without changing unrelated stock", async () => {
  const { api, storage } = demo();
  await api("/api/auth/login", post({ email: "owner@demo.com" }));
  const before = await api("/api/stock/current?productId=p1");
  await api("/api/stock/adjustment", post({ productId: "p1", warehouseId: "w1", quantityDelta: 3, rate: 8.5 }));
  const after = await api("/api/stock/current?productId=p1");
  assert.equal(after.find(row => row.warehouseId === "w1").currentStock, before.find(row => row.warehouseId === "w1").currentStock + 3);
  assert.equal(after.find(row => row.warehouseId === "w2").currentStock, before.find(row => row.warehouseId === "w2").currentStock);
  await api("/api/auth/login", post({ email: "ravi@demo.com" }));
  const order = await api("/api/portal/orders", post({ items: [{ productId: "p1", quantity: 2 }] }));
  assert.equal(order.totalAmount, 24);
  assert.equal((await demo(storage).api("/api/portal/orders"))[0].orderId, order.orderId);
});

test("demo paginated searches reach products after the first 200 and isolate workspaces", async () => {
  const { api } = demo();
  await api("/api/auth/register", post({ email: "large@example.test" }));
  for (let i = 0; i < 205; i++) await api("/api/products", post({ name: `Product ${i}` }));
  const first = await api("/api/products?size=25&page=0");
  const last = await api("/api/products?size=25&page=8");
  assert.equal(first.totalElements, 205);
  assert.equal(last.content.length, 5);
  assert.equal((await api("/api/products?query=Product%200&size=25")).content[0].name, "Product 0");
  await api("/api/auth/register", post({ email: "other@example.test" }));
  assert.equal((await api("/api/products?size=25")).totalElements, 0);
  assert.equal((await api("/api/dashboard/summary")).totalStockValue, 0);
  await api("/api/auth/login", post({ email: "large@example.test" }));
  assert.equal((await api("/api/products?size=25")).totalElements, 205);
});
