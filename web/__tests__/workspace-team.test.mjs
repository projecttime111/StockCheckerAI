import assert from "node:assert/strict";
import test, { after, afterEach } from "node:test";
import { readFileSync, existsSync } from "node:fs";
import { createRequire } from "node:module";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import ts from "typescript";
import { JSDOM } from "jsdom";
const dom = new JSDOM("<!doctype html><html><body></body></html>", { url: "http://localhost/team" });
Object.assign(globalThis, { window: dom.window, document: dom.window.document, HTMLElement: dom.window.HTMLElement, IS_REACT_ACT_ENVIRONMENT: true });
Object.defineProperty(globalThis, "navigator", { value: dom.window.navigator, configurable: true });
const { render, fireEvent, cleanup, waitFor, within } = await import("@testing-library/react");
const require = createRequire(import.meta.url), React = require("react");
const { QueryClient, QueryClientProvider } = require("@tanstack/react-query");
const root = fileURLToPath(new URL("..", import.meta.url));
let request, signedIn = true;
const modules = new Map(), clients = [], redirects = [], sessions = [];
const apiModule = { api: (...args) => request(...args), getToken: () => signedIn ? "session-token" : null, DEMO_MODE: false, CLERK_ENABLED: false, clearSession: () => sessions.push("cleared"), setSession: (...args) => sessions.push(args) };
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
    if (name === "next/navigation") return { useSearchParams: () => new URLSearchParams() };
    if (name.startsWith("@/")) return load(resolve(root, name.slice(2)));
    if (name.startsWith("./")) return load(resolve(dirname(filename), name));
    return require(name);
  };
  new Function("require", "module", "exports", "window", source)(localRequire, module, module.exports, { location: { assign: path => redirects.push(path) } });
  modules.set(filename, module.exports); return module.exports;
}
const Team = load(resolve(root, "app/team/page")).default;
const Billing = load(resolve(root, "app/billing/page")).default;
const { InvitationAcceptance } = load(resolve(root, "components/workspace/InvitationAcceptance"));
function mount(component, props) { const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } }); clients.push(client); return render(React.createElement(QueryClientProvider, { client }, React.createElement(component, props))); }
afterEach(() => { cleanup(); clients.splice(0).forEach(client => client.clear()); redirects.length = 0; sessions.length = 0; signedIn = true; apiModule.CLERK_ENABLED = false; });
after(() => dom.window.close());
const access = { userId: "owner-1", email: "owner@example.test", fullName: "Owner", tenantId: "workspace-1", role: "OWNER", permissions: ["users.view", "users.manage", "billing.view"], workspaces: [], emailVerified: true, authProvider: "local" };
const page = (content, number = 0, totalPages = 1) => ({ content, number, totalPages, totalElements: totalPages > 1 ? 25 : content.length });
const member = { userId: "staff-1", fullName: "Team Member", email: "member@example.test", role: "STAFF", active: true, joinedAt: "2026-01-01T00:00:00Z" };
const pending = { id: "invite-1", email: "new@example.test", role: "STAFF", status: "PENDING", expiresAt: "2030-01-01T00:00:00Z", createdAt: "2026-01-01T00:00:00Z" };
const preview = { workspaceName: "Acme Retail", email: "member@example.test", role: "STAFF", accountExists: true, clerkManaged: false };
function teamReply(path, session = access) {
  if (path === "/api/auth/session") return session;
  if (path === "/api/workspace/team/roles") return ["ADMIN", "MANAGER", "STAFF", "VIEWER"];
  if (path.startsWith("/api/workspace/team/members")) return page([{ ...member, userId: access.userId, fullName: "Workspace Owner", email: access.email, role: "OWNER" }, member]);
  if (path.startsWith("/api/workspace/team/invitations")) return page([pending]);
  throw new Error(`Unexpected request ${path}`);
}

test("team page does not read team data without verified users.view", async () => {
  const calls = []; request = async path => { calls.push(path); return { ...access, role: "VIEWER", permissions: [] }; };
  const ui = mount(Team); await ui.findByText("Your role does not have access to team management.");
  assert.deepEqual(calls, ["/api/auth/session"]);
});

test("read-only team members cannot see invitation or role mutation controls", async () => {
  request = async path => teamReply(path, { ...access, permissions: ["users.view"] });
  const ui = mount(Team); await ui.findByText(member.fullName);
  assert.equal(ui.queryByRole("button", { name: "Send invitation" }), null);
  assert.equal(ui.queryByRole("button", { name: /Change role for/ }), null);
  assert.equal(ui.queryByRole("button", { name: /Revoke invitation/ }), null);
});

test("role management protects owner/self and submits confirmed business role", async () => {
  let current = member; const writes = [];
  request = async (path, init) => { if (init?.method === "PATCH") { const body = JSON.parse(init.body); writes.push({ path, body }); current = { ...current, role: body.role }; return current; } if (path.startsWith("/api/workspace/team/members")) return page([{ ...member, userId: access.userId, fullName: "Workspace Owner", email: access.email, role: "OWNER" }, current]); return teamReply(path); };
  const ui = mount(Team); await ui.findByText(member.fullName);
  assert.equal(ui.queryByRole("button", { name: `Change role for ${access.email}` }), null);
  fireEvent.click(ui.getByRole("button", { name: `Change role for ${member.email}` }));
  assert.equal(ui.getByRole("button", { name: "Confirm role change" }).disabled, true);
  fireEvent.change(ui.getByLabelText("New workspace role"), { target: { value: "VIEWER" } });
  assert.equal(ui.queryByRole("option", { name: "Owner" }), null);
  fireEvent.click(ui.getByRole("button", { name: "Confirm role change" }));
  await ui.findByText("Team access updated.");
  assert.deepEqual(writes, [{ path: "/api/workspace/team/members/staff-1", body: { role: "VIEWER" } }]);
});

test("member removal requires email confirmation and retains failed confirmation for retry", async () => {
  let failed = true; const writes = [];
  request = async (path, init) => { if (init?.method === "DELETE") { writes.push(path); if (failed) throw new Error("Removal unavailable"); return { status: "REMOVED" }; } return teamReply(path); };
  const ui = mount(Team); fireEvent.click(await ui.findByRole("button", { name: `Remove ${member.email}` }));
  assert.equal(ui.getByRole("button", { name: "Confirm removal" }).disabled, true);
  fireEvent.change(ui.getByLabelText(`Type ${member.email} to confirm removal`), { target: { value: member.email } });
  fireEvent.click(ui.getByRole("button", { name: "Confirm removal" })); await ui.findByText("Removal unavailable");
  assert.equal(ui.getByLabelText(`Type ${member.email} to confirm removal`).value, member.email);
  failed = false; fireEvent.click(ui.getByRole("button", { name: "Confirm removal" })); await ui.findByText("Team access updated.");
  assert.deepEqual(writes, ["/api/workspace/team/members/staff-1", "/api/workspace/team/members/staff-1"]);
});

test("team search pages on backend and resets page for a new query", async () => {
  const calls = []; request = async path => { calls.push(path); if (path.startsWith("/api/workspace/team/members")) { const p = new URL(path, "http://localhost").searchParams; return page([{ ...member, fullName: p.get("page") === "1" ? "Later teammate" : member.fullName }], Number(p.get("page")), 2); } return teamReply(path); };
  const ui = mount(Team); await ui.findByText(member.fullName);
  const panel = ui.getByRole("heading", { name: "Workspace members" }).closest("section");
  fireEvent.click(within(panel).getByRole("button", { name: "Next" })); await ui.findByText("Later teammate");
  fireEvent.change(ui.getByLabelText("Search team"), { target: { value: "another@example.test" } }); fireEvent.click(ui.getByRole("button", { name: "Search" }));
  await waitFor(() => assert.ok(calls.includes("/api/workspace/team/members?page=0&size=20&query=another%40example.test")));
});

test("invitations send selected role, reset after success, and revoke only after confirmation", async () => {
  const writes = []; request = async (path, init) => { if (init?.method) { writes.push({ path, method: init.method, body: init.body && JSON.parse(init.body) }); return pending; } return teamReply(path); };
  const ui = mount(Team); await ui.findByRole("button", { name: "Send invitation" }); await ui.findByRole("option", { name: "Staff" });
  fireEvent.change(ui.getByLabelText("Teammate email"), { target: { value: "colleague@example.test" } });
  fireEvent.change(ui.getByLabelText("Invitation role"), { target: { value: "MANAGER" } }); fireEvent.click(ui.getByRole("button", { name: "Send invitation" }));
  await ui.findByText("Invitation sent. The teammate can join using the email link."); assert.equal(ui.getByLabelText("Teammate email").value, "");
  fireEvent.click(ui.getByRole("button", { name: `Revoke invitation for ${pending.email}` })); assert.equal(writes.length, 1);
  fireEvent.click(ui.getByRole("button", { name: "Confirm revocation" })); await ui.findByText("Invitation revoked.");
  assert.deepEqual(writes[0], { path: "/api/workspace/team/invitations", method: "POST", body: { email: "colleague@example.test", role: "MANAGER" } });
  assert.equal(writes[1].path, "/api/workspace/team/invitations/invite-1"); assert.equal(writes[1].method, "DELETE");
});

test("existing account invitation preserves token in a safe login return path", async () => {
  signedIn = false; const calls = []; request = async (path, init) => { calls.push({ path, body: JSON.parse(init.body) }); return preview; };
  const ui = mount(InvitationAcceptance, { token: "secret-token" }); const link = await ui.findByRole("link", { name: "Sign in to accept" });
  assert.equal(new URL(link.href).searchParams.get("next"), "/team/accept?token=secret-token");
  assert.deepEqual(calls, [{ path: "/api/auth/invitations/preview", body: { token: "secret-token" } }]);
});

test("new invite registration validates confirmation and uses invitation-bound credentials", async () => {
  signedIn = false; const writes = [];
  request = async (path, init) => { if (path.endsWith("/preview")) return { ...preview, accountExists: false }; writes.push({ path, body: JSON.parse(init.body) }); return { accessToken: "accepted-token", role: "STAFF" }; };
  const ui = mount(InvitationAcceptance, { token: "invite-token" }); await ui.findByLabelText("Full name");
  fireEvent.change(ui.getByLabelText("Full name"), { target: { value: " New Colleague " } }); fireEvent.change(ui.getByLabelText("Password"), { target: { value: "new-password-123" } }); fireEvent.change(ui.getByLabelText("Confirm password"), { target: { value: "wrong-password-123" } });
  fireEvent.submit(ui.getByLabelText("Full name").closest("form")); await ui.findByText("The passwords do not match."); assert.equal(writes.length, 0);
  fireEvent.change(ui.getByLabelText("Confirm password"), { target: { value: "new-password-123" } }); fireEvent.submit(ui.getByLabelText("Full name").closest("form"));
  await waitFor(() => assert.deepEqual(redirects, ["/dashboard"]));
  assert.deepEqual(writes, [{ path: "/api/auth/invitations/register", body: { token: "invite-token", fullName: "New Colleague", password: "new-password-123" } }]);
  assert.deepEqual(sessions, ["cleared", ["accepted-token", "STAFF", []]]);
});

test("signed-in invitation checks email and accepts only the invited account", async () => {
  const calls = []; request = async (path, init) => { calls.push(path); if (path === "/api/auth/session") return { ...access, email: preview.email }; if (path.endsWith("/preview")) return preview; assert.deepEqual(JSON.parse(init.body), { token: "invite-token" }); return { accessToken: "accepted", role: "STAFF" }; };
  const ui = mount(InvitationAcceptance, { token: "invite-token" }); fireEvent.click(await ui.findByRole("button", { name: "Accept invitation" }));
  await waitFor(() => assert.deepEqual(redirects, ["/dashboard"])); assert.ok(calls.includes("/api/workspace/invitations/accept"));
});

test("wrong signed-in email cannot accept another account's invitation", async () => {
  request = async path => path === "/api/auth/session" ? access : preview;
  const ui = mount(InvitationAcceptance, { token: "invite-token" }); await ui.findByText(/This invitation belongs to member@example.test/);
  assert.equal(ui.queryByRole("button", { name: "Accept invitation" }), null);
});

test("Clerk organization invitation offers organization flow without local registration", async () => {
  signedIn = false; request = async () => ({ ...preview, clerkManaged: true, accountExists: false });
  const ui = mount(InvitationAcceptance, { token: "invite-token" }); const link = await ui.findByRole("link", { name: "Continue to sign in" });
  assert.equal(link.getAttribute("href"), "/sign-in?next=%2Fauth%2Fcomplete"); assert.equal(ui.queryByLabelText("Password"), null);
});

test("expired invitation is an actionable error, not an empty registration form", async () => {
  signedIn = false; request = async () => { throw new Error("This invitation has expired or is no longer available"); };
  const ui = mount(InvitationAcceptance, { token: "invite-token" }); await ui.findByText("This invitation has expired or is no longer available");
  assert.ok(ui.getByText(/ask your workspace administrator for a new invitation/)); assert.equal(ui.queryByRole("button", { name: "Create account and join" }), null);
});

test("billing is permission-gated and displays recorded all-source usage without payment actions", async () => {
  request = async path => path === "/api/auth/session" ? access : { tenantId: access.tenantId, name: "Acme Retail", businessMode: "RETAIL", currency: "INR", plan: "PRO", subscriptionStatus: "ACTIVE", subscriptionEndsAt: null, clerkManaged: false, usage: { members: 2, products: 12, warehouses: 1, imports: 7 } };
  const ui = mount(Billing); await ui.findByText("PRO"); assert.ok(ui.getByText("Imports")); assert.ok(ui.getByText("7")); assert.ok(ui.getByText(/Online payments and payment-provider invoices are not available/)); assert.equal(ui.queryByRole("button"), null);
});

test("billing permission denial prevents account data from being requested", async () => {
  const calls = [];
  request = async path => { calls.push(path); return { ...access, permissions: ["dashboard.view"] }; };
  const ui = mount(Billing);
  await ui.findByText("Your role does not have access to this workspace’s plan details.");
  assert.deepEqual(calls, ["/api/auth/session"]);
});


test("Clerk mode blocks legacy local invitation registration and acceptance", async () => {
  apiModule.CLERK_ENABLED = true;
  request = async path => path === "/api/auth/session" ? { ...access, email: preview.email, authProvider: "clerk" } : { ...preview, accountExists: false, clerkManaged: false };
  const ui = mount(InvitationAcceptance, { token: "legacy-invite" });
  await ui.findByText(/Ask the workspace owner to connect the business to an organization and send a new invitation/);
  assert.equal(ui.queryByRole("button", { name: "Accept invitation" }), null);
  assert.equal(ui.queryByRole("button", { name: "Create account and join" }), null);
  assert.equal(ui.queryByLabelText("Password"), null);
  assert.equal(ui.getByRole("link", { name: "Choose a workspace" }).getAttribute("href"), "/workspace/select");
});

test("existing Clerk membership invitation shows backend delivery guidance instead of claiming a new email", async () => {
  const message = "Access invitation recorded. This teammate already belongs to the organization; sign in and select this workspace.";
  request = async (path, init) => init?.method === "POST" && path === "/api/workspace/team/invitations" ? { ...pending, deliveryStatus: "EXISTING_MEMBER", message } : teamReply(path);
  const ui = mount(Team);
  await ui.findByText(member.fullName);
  fireEvent.change(ui.getByLabelText("Teammate email"), { target: { value: pending.email } });
  fireEvent.click(ui.getByRole("button", { name: "Send invitation" }));
  await ui.findByText(message);
  assert.equal(ui.queryByText("Invitation sent. The teammate can join using the email link."), null);
});
