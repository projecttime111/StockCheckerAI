import assert from "node:assert/strict";
import test, { after, afterEach } from "node:test";
import { readFileSync, existsSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";
import { JSDOM } from "jsdom";
const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "https://stockpilot.test/onboarding" });
Object.assign(globalThis, { window: dom.window, document: dom.window.document, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true });
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
const { render, cleanup, fireEvent, waitFor, act } = await import("@testing-library/react");
const require = createRequire(import.meta.url), React = require("react");
const { QueryClient, QueryClientProvider } = require("@tanstack/react-query");
const root = fileURLToPath(new URL("..", import.meta.url));
const modules = new Map(), clients = [], redirects = [], calls = [], activations = [], timers = new Map();
let signedIn = true, refetches = 0, reloads = 0, timerId = 0, organizationProps, organizationReady = true;
let session = {}, request = async () => ({ accessToken: null, role: "OWNER" }), activate = async () => {};
const location = { search: "", assign: path => redirects.push(path), replace: path => redirects.push(path), reload: () => { reloads++; } };
const api = {
  CLERK_ENABLED: true, DEMO_MODE: false, ApiClientError: class extends Error {}, getToken: () => signedIn ? "clerk:user:org" : null,
  api: async (path, init) => { calls.push({ path, ...init }); return request(path, init); },
  clearSession: () => {}, setSession: () => {}, loginDestination: () => "/dashboard", signOutSession: async () => {},
};
function load(path) {
  let filename = path;
  if (!existsSync(filename)) filename += existsSync(path + ".tsx") ? ".tsx" : ".ts";
  if (modules.has(filename)) return modules.get(filename);
  const module = { exports: {} };
  const source = ts.transpileModule(readFileSync(filename, "utf8"), { compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX, esModuleInterop: true, target: ts.ScriptTarget.ES2022 } }).outputText;
  new Function("require", "module", "exports", "window", source)(name => {
    if (name === "@/lib/api") return api;
    if (name === "@/lib/session") return { useSession: () => ({ isLoading: false, isFetching: false, ...session, refetch: async () => { refetches++; } }) };
    if (name === "@/components/GoogleSignInButton") return { GoogleSignInButton: () => null };
    if (name === "@clerk/nextjs") return {
      useClerk: () => ({ setActive: async options => { activations.push(options); await activate(options); } }),
      OrganizationList: props => { organizationProps = props; return organizationReady ? React.createElement("div", null, "Business organization picker") : props.fallback; },
    };
    if (name === "next/link") return { __esModule: true, default: ({ children, ...props }) => React.createElement("a", props, children) };
    if (name === "next/navigation") return { redirect: path => { redirects.push(path); throw new Error("REDIRECT"); } };
    if (name.startsWith("@/")) return load(resolve(root, name.slice(2)));
    if (name.startsWith("./")) return load(resolve(dirname(filename), name));
    return require(name);
  }, module, module.exports, { location, setTimeout: (callback, delay) => { const id = ++timerId; timers.set(id, { callback, delay }); return id; }, clearTimeout: id => timers.delete(id) });
  modules.set(filename, module.exports); return module.exports;
}
const Onboarding = load(resolve(root, "app/onboarding/page")).default;
const Workspaces = load(resolve(root, "app/workspace/select/page")).default;
const AccountLink = load(resolve(root, "app/account/link/page")).default;
const Login = load(resolve(root, "app/login/page")).default;
const Register = load(resolve(root, "app/register/page")).default;
const { BusinessOrganizationPicker } = load(resolve(root, "components/workspace/BusinessOrganizationPicker"));
function mount(Component) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } }); clients.push(client);
  return render(React.createElement(QueryClientProvider, { client }, React.createElement(Component)));
}
const newAccount = { clerkOrganizationId: null, tenantId: null, role: null, platformRole: null, workspaces: [], accessState: "ORGANIZATION_REQUIRED" };
const newOrganization = { ...newAccount, clerkOrganizationId: "org-new", accessState: "ORGANIZATION_UNLINKED" };
const workspace = { tenantId: "tenant-one", name: "First business", role: "OWNER", businessMode: "RETAIL", clerkOrganizationId: "org-one" };
afterEach(() => {
  cleanup(); clients.splice(0).forEach(client => client.clear()); session = {}; signedIn = true; api.CLERK_ENABLED = true; refetches = 0; reloads = 0;
  organizationProps = undefined; organizationReady = true; location.search = ""; timers.clear(); redirects.length = 0; calls.length = 0; activations.length = 0;
  request = async () => ({ accessToken: null, role: "OWNER" }); activate = async () => {};
});
after(() => dom.window.close());
function assertNoMigration(ui) {
  assert.equal(ui.container.querySelector('a[href^="/account/link"]'), null);
  assert.equal(ui.queryByText(/existing StockPilot|connect existing|link existing|DELETE MY ACCOUNT/i), null);
}
test("new Clerk account chooses or creates an organization without migration prompts", () => {
  session = { data: newAccount }; const ui = mount(Onboarding);
  assert.ok(ui.getByText("Business organization picker")); assert.equal(ui.queryByLabelText("Business name"), null);
  assert.equal(organizationProps.afterSelectOrganizationUrl, "/auth/complete"); assert.equal(organizationProps.afterCreateOrganizationUrl, "/onboarding");
  assert.equal(organizationProps.hidePersonal, true); assert.equal(organizationProps.skipInvitationScreen, true); assertNoMigration(ui);
});
test("new organization creates a workspace through the Clerk API then opens its dashboard", async () => {
  session = { data: newOrganization }; const ui = mount(Onboarding); assertNoMigration(ui);
  assert.equal(ui.getByRole("button", { name: "Create workspace" }).disabled, true);
  fireEvent.change(ui.getByLabelText("Business name"), { target: { value: "  New business  " } }); fireEvent.submit(ui.getByLabelText("Business name").closest("form"));
  await waitFor(() => assert.deepEqual(redirects, ["/dashboard"])); assert.equal(calls.length, 1); assert.equal(calls[0].path, "/api/auth/clerk/workspace");
  assert.deepEqual(JSON.parse(calls[0].body), { businessName: "New business", businessMode: "RETAIL", currency: "INR", gstEnabled: true });
});
test("workspace creation visibly stays busy and blocks duplicate submissions", async () => {
  let complete; request = () => new Promise(resolve => { complete = resolve; }); session = { data: newOrganization }; const ui = mount(Onboarding);
  fireEvent.change(ui.getByLabelText("Business name"), { target: { value: "New business" } }); fireEvent.submit(ui.getByLabelText("Business name").closest("form"));
  const busy = await ui.findByRole("button", { name: "Creating your workspace…" }); assert.equal(busy.disabled, true); assert.equal(busy.getAttribute("aria-busy"), "true");
  fireEvent.submit(ui.getByLabelText("Business name").closest("form")); assert.equal(calls.length, 1);
  await act(async () => { complete({ accessToken: null, role: "OWNER" }); });
});
test("organization members without workspace access cannot open a creation form", () => {
  session = { data: { ...newOrganization, accessState: "MEMBERSHIP_REQUIRED" } }; const ui = mount(Onboarding);
  assert.match(ui.getByRole("alert").textContent, /Ask its owner to invite your email/); assert.equal(ui.queryByLabelText("Business name"), null);
  assert.equal(ui.queryByRole("button", { name: "Create workspace" }), null); assertNoMigration(ui);
});
test("failed account response gives recovery instead of a setup form or stuck loader", () => {
  session = { error: new Error("Service is waking up. Please retry.") }; const ui = mount(Onboarding);
  assert.match(ui.getByRole("alert").textContent, /Service is waking up/); assert.equal(ui.queryByText("Business organization picker"), null); assert.equal(ui.queryByLabelText("Business name"), null);
  fireEvent.click(ui.getByRole("button", { name: "Try again" })); assert.equal(refetches, 1); assertNoMigration(ui);
});
test("unsigned visitor goes to Clerk sign-in before setup is mounted", () => {
  signedIn = false; session = { data: newOrganization }; const ui = mount(Onboarding);
  assert.equal(ui.queryByLabelText("Business name"), null); assert.deepEqual(redirects, ["/sign-in?next=%2Fonboarding"]);
});
test("workspace picker activates a permitted Clerk organization and never offers migration", async () => {
  session = { data: { ...newAccount, workspaces: [workspace, { ...workspace, tenantId: "legacy", name: "Old workspace", clerkOrganizationId: null }] } };
  const ui = mount(Workspaces); assert.equal(ui.queryByText("Old workspace"), null); assertNoMigration(ui);
  fireEvent.click(ui.getByRole("button", { name: /First business/ })); await waitFor(() => assert.deepEqual(redirects, ["/auth/complete"])); assert.deepEqual(activations, [{ organization: "org-one" }]);
});
test("workspace errors keep retry and signout available without unverified organizations", () => {
  session = { error: new Error("Account verification unavailable") }; const ui = mount(Workspaces);
  assert.equal(ui.queryByText("Business organization picker"), null); assert.ok(ui.getByRole("button", { name: "Sign out" }));
  fireEvent.click(ui.getByRole("button", { name: "Try again" })); assert.equal(refetches, 1); assertNoMigration(ui);
});
test("stalled Clerk workspace selection releases busy state and offers recovery", async () => {
  session = { data: { ...newAccount, workspaces: [workspace] } }; activate = () => new Promise(() => {});
  const ui = mount(Workspaces); fireEvent.click(ui.getByRole("button", { name: /First business/ }));
  await ui.findByText("Opening your workspace…"); const timer = [...timers.values()].find(value => value.delay === 30_000); assert.ok(timer);
  await act(async () => timer.callback());
  await ui.findByText(/Workspace selection is taking longer than expected/);
  assert.equal(ui.getByRole("button", { name: /First business/ }).disabled, false);
  fireEvent.click(ui.getByRole("button", { name: "Reload workspaces" })); assert.equal(reloads, 1); assert.deepEqual(redirects, []);
});
test("missing workspace response is actionable rather than an empty screen", () => {
  const ui = mount(Workspaces); assert.match(ui.getByRole("alert").textContent, /couldn’t load your workspaces/); assert.ok(ui.getByRole("button", { name: "Try again" }));
});
test("organization component stall has visible status and delayed reload recovery", () => {
  organizationReady = false; const ui = mount(BusinessOrganizationPicker); assert.match(ui.getByRole("status").textContent, /Loading your business organizations/);
  assert.equal(ui.queryByRole("button", { name: "Reload organizations" }), null); const timer = [...timers.values()].find(timer => timer.delay === 15_000); assert.ok(timer);
  act(() => timer.callback()); fireEvent.click(ui.getByRole("button", { name: "Reload organizations" })); assert.equal(reloads, 1);
});
test("old account-link bookmark redirects to the standard handoff without credential forms", () => {
  assert.throws(() => AccountLink(), /REDIRECT/); assert.deepEqual(redirects, ["/auth/complete"]); api.CLERK_ENABLED = false; redirects.length = 0;
  assert.throws(() => AccountLink(), /REDIRECT/); assert.deepEqual(redirects, ["/login"]);
});
test("login and registration aliases preserve invitation return paths in Clerk mode", () => {
  location.search = "?next=%2Fteam%2Faccept%3Ftoken%3Dinvite"; const login = mount(Login);
  assert.equal(login.queryByLabelText("Password"), null); assert.deepEqual(redirects, ["/sign-in" + location.search]); login.unmount(); redirects.length = 0;
  const register = mount(Register); assert.equal(register.queryByLabelText("Password"), null); assert.deepEqual(redirects, ["/sign-up" + location.search]);
});
