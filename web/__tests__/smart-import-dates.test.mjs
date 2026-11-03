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
const { render, fireEvent, cleanup } = await import("@testing-library/react");
const require = createRequire(import.meta.url);
const React = require("react");
const source = readFileSync(new URL("../components/SmartImportSnapshotDates.tsx", import.meta.url), "utf8");
const output = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX } }).outputText;
const module = { exports: {} };
new Function("require", "module", "exports", output)(require, module, module.exports);
const { SmartImportSnapshotDates, SavedSnapshotDates, snapshotDatesFromSummary, snapshotDatesValid, snapshotDatesMatch } = module.exports;
afterEach(cleanup);
after(() => dom.window.close());

const summary = {
  snapshotDatePolicyVersion: 1, requestedSnapshotDate: "2026-08-31", snapshotDateConfirmed: true,
  outstandingSnapshotDate: "2026-08-30", outstandingSnapshotDateConfirmed: true
};

test("requires separate dates and confirmations, never infers today or legacy confirmations", () => {
  const blank = snapshotDatesFromSummary();
  assert.equal(blank.snapshotDate, "");
  assert.equal(snapshotDatesValid(blank, true, true), false);
  assert.equal(snapshotDatesValid(blank), true);
  assert.equal(snapshotDatesMatch(blank, { requestedSnapshotDate: "2026-09-21" }), false);
  assert.deepEqual(snapshotDatesFromSummary({ requestedSnapshotDate: "2026-09-21" }), blank);
  const saved = snapshotDatesFromSummary(summary);
  assert.equal(snapshotDatesValid(saved, true, true), true);
  assert.equal(snapshotDatesMatch(saved, summary), true);
  assert.equal(snapshotDatesMatch({ ...saved, outstandingSnapshotDate: "2026-08-31" }, summary), false);
  assert.equal(snapshotDatesMatch({ ...saved, snapshotDateConfirmed: false }, summary), false);
});

test("editing either date clears only its confirmation and requires a new preview", () => {
  let current;
  function Harness() {
    const [value, setValue] = React.useState(snapshotDatesFromSummary(summary));
    current = value;
    return React.createElement(SmartImportSnapshotDates, { value, onChange: setValue, stockMissing: 1916, outstandingMissing: 236 });
  }
  const ui = render(React.createElement(Harness));
  assert.ok(ui.getByText("1916 stock rows without a source date"));
  fireEvent.change(ui.getByLabelText(/Closing-stock as-of date/), { target: { value: "2026-08-29" } });
  assert.equal(current.snapshotDateConfirmed, false);
  assert.equal(current.outstandingSnapshotDateConfirmed, true);
  assert.equal(snapshotDatesValid(current, true, true), false);
  fireEvent.click(ui.getByLabelText(/I confirm this as-of date for undated closing-stock/));
  assert.equal(snapshotDatesValid(current, true, true), true);
  assert.equal(snapshotDatesMatch(current, summary), false);
  fireEvent.change(ui.getByLabelText(/Outstanding as-of date/), { target: { value: "2026-08-28" } });
  assert.equal(current.outstandingSnapshotDateConfirmed, false);
  assert.equal(current.snapshotDateConfirmed, true);
});

test("preview and commit display persisted dates and warn about old or changed previews", () => {
  const ui = render(React.createElement(SavedSnapshotDates, { summary, changed: true }));
  assert.ok(ui.getByText("2026-08-31"));
  assert.ok(ui.getByText("2026-08-30"));
  assert.match(ui.getByRole("alert").textContent, /Run a new dry run/);
  ui.rerender(React.createElement(SavedSnapshotDates, { summary: {} }));
  assert.match(ui.getByRole("alert").textContent, /predates snapshot-date confirmation/);
});

test("workspace sends both dates and confirmations and guards commit against edited dates", () => {
  const page = readFileSync(new URL("../app/imports/smart/page.tsx", import.meta.url), "utf8");
  assert.match(page, /snapshotDate: snapshotDates.snapshotDate \|\| undefined/);
  assert.match(page, /outstandingSnapshotDate: snapshotDates.outstandingSnapshotDate \|\| undefined/);
  assert.match(page, /snapshotDateConfirmed: snapshotDates.snapshotDateConfirmed/);
  assert.match(page, /outstandingSnapshotDateConfirmed: snapshotDates.outstandingSnapshotDateConfirmed/);
  assert.match(page, /&& datesValid && datesMatchPreview/);
  assert.match(page, /if \(!datesMatchPreview\) throw new Error/);
  assert.equal((page.match(/<SavedSnapshotDates/g) || []).length, 2);
  assert.doesNotMatch(page, /"Commit date"/);
});
