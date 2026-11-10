import assert from "node:assert/strict";
import test from "node:test";
import { readFileSync } from "node:fs";
import ts from "typescript";

function load(relativePath, require = () => { throw new Error("Unexpected dependency"); }) {
  const module = { exports: {} };
  new Function("require", "module", "exports", ts.transpileModule(readFileSync(new URL(relativePath, import.meta.url), "utf8"), {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 }
  }).outputText)(require, module, module.exports);
  return module.exports;
}
test("frontend liveness response contains only public status and disables browser/CDN caching", async () => {
  const route = load("../app/api/health/route.ts");
  const response = route.GET(), body = await response.json();
  assert.equal(response.status, 200);
  assert.deepEqual(Object.keys(body).sort(), ["checkedAt", "service", "status"]);
  assert.equal(body.status, "UP"); assert.equal(body.service, "stockpilot-web"); assert.ok(Number.isFinite(Date.parse(body.checkedAt)));
  for (const header of ["Cache-Control", "CDN-Cache-Control", "Vercel-CDN-Cache-Control"]) assert.match(response.headers.get(header), /no-store/);
  assert.equal(route.dynamic, "force-dynamic");
});
test("public health routes bypass identity dependency while business routes retain Clerk protection", async t => {
  const oldEnabled = process.env.NEXT_PUBLIC_CLERK_ENABLED, oldDemo = process.env.NEXT_PUBLIC_DEMO_MODE;
  t.after(() => {
    if (oldEnabled === undefined) delete process.env.NEXT_PUBLIC_CLERK_ENABLED; else process.env.NEXT_PUBLIC_CLERK_ENABLED = oldEnabled;
    if (oldDemo === undefined) delete process.env.NEXT_PUBLIC_DEMO_MODE; else process.env.NEXT_PUBLIC_DEMO_MODE = oldDemo;
  });
  process.env.NEXT_PUBLIC_CLERK_ENABLED = "true"; process.env.NEXT_PUBLIC_DEMO_MODE = "false";
  let identityCalls = 0, protectedCalls = 0;
  const proxy = load("../proxy.ts", name => {
    if (name === "next/server") return { NextResponse: { next: () => "PASS" } };
    if (name === "@clerk/nextjs/server") return {
      createRouteMatcher: patterns => request => patterns.some(pattern => new RegExp(`^${pattern}$`).test(request.nextUrl.pathname)),
      clerkMiddleware: callback => async request => {
        identityCalls++;
        await callback({ protect: async () => { protectedCalls++; } }, request);
        return "CLERK";
      }
    };
    throw new Error(`Unexpected dependency: ${name}`);
  }).default;
  for (const path of ["/status", "/api/health"]) assert.equal(await proxy({ nextUrl: { pathname: path } }, {}), "PASS");
  assert.equal(identityCalls, 0);
  for (const path of ["/admin/system", "/dashboard", "/imports", "/auth/complete", "/team"]) {
    assert.equal(await proxy({ nextUrl: { pathname: path } }, {}), "CLERK");
  }
  assert.equal(identityCalls, 5); assert.equal(protectedCalls, 5);
});
