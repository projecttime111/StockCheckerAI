import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";
import { JSDOM } from "jsdom";
import ts from "typescript";

const require = createRequire(import.meta.url);
const React = require("react");
const { renderToStaticMarkup } = require("react-dom/server");
let clerkEnabled = true, demoMode = false;

function load(path) {
  const module = { exports: {} };
  const source = ts.transpileModule(readFileSync(new URL(`../${path}`, import.meta.url), "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true, target: ts.ScriptTarget.ES2022 } }).outputText;
  new Function("require", "module", "exports", source)(name => {
    if (name === "@/lib/api") return { CLERK_ENABLED: clerkEnabled, isDemoMode: () => demoMode };
    if (name === "next/link") return { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) };
    if (name.startsWith("@/components/")) return load(name.replace("@/", "") + ".tsx");
    if (name === "./Brand") return load("components/marketing/Brand.tsx");
    return require(name);
  }, module, module.exports);
  return module.exports;
}

test("public homepage renders without a session and routes every account CTA to its configured provider", () => {
  for (const enabled of [true, false]) {
    clerkEnabled = enabled;
    const html = renderToStaticMarkup(React.createElement(load("app/page.tsx").default));
    const dom = new JSDOM(html);
    try {
      const doc = dom.window.document;
      assert.match(doc.querySelector("h1").textContent, /Less guesswork/);
      const links = [...doc.querySelectorAll("a")];
      const signIns = links.filter(link => link.textContent.trim().startsWith("Sign in"));
      const signUps = links.filter(link => /Get started|Create your workspace/.test(link.textContent));
      assert.equal(signIns.length, 3); assert.equal(signUps.length, 3);
      signIns.forEach(link => assert.equal(link.getAttribute("href"), enabled ? "/sign-in" : "/login"));
      signUps.forEach(link => assert.equal(link.getAttribute("href"), enabled ? "/sign-up" : "/register"));
      for (const link of links.filter(link => link.getAttribute("href").startsWith("#"))) assert.ok(doc.querySelector(link.getAttribute("href")), `Anchor exists: ${link.getAttribute("href")}`);
      assert.equal(doc.querySelectorAll("main").length, 1);
      assert.match(doc.querySelector("figcaption").textContent, /Illustrative preview · example data/);
      assert.match(doc.querySelector("figure").getAttribute("aria-label"), /example inventory data/);
      assert.doesNotMatch(html, /Backend mode|localhost:8080|owner@demo\.com|password123/);
    } finally { dom.window.close(); }
  }
});

test("production banner exposes no API information while demo clearly explains local-only changes", () => {
  demoMode = false;
  assert.equal(renderToStaticMarkup(React.createElement(load("components/ModeBanner.tsx").ModeBanner)), "");
  demoMode = true;
  const html = renderToStaticMarkup(React.createElement(load("components/ModeBanner.tsx").ModeBanner));
  assert.match(html, /demo workspace/); assert.match(html, /Changes stay in this browser/);
  assert.doesNotMatch(html, /API|database|localhost/);
});

test("account frame keeps child actions accessible and provides a real home link", () => {
  const { AccountFrame } = load("components/AccountFrame.tsx");
  const html = renderToStaticMarkup(React.createElement(AccountFrame, { title: "Choose your workspace", description: "Keep each business separate." }, React.createElement("button", null, "Open workspace")));
  const dom = new JSDOM(html);
  try {
    const doc = dom.window.document;
    assert.equal(doc.querySelector("h1").textContent, "Choose your workspace");
    assert.equal(doc.querySelector("button").textContent, "Open workspace");
    assert.ok([...doc.querySelectorAll("a")].some(link => link.textContent.includes("Back to home") && link.getAttribute("href") === "/"));
  } finally { dom.window.close(); }
});
