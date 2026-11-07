import { demoApi } from "./demoApi";

export const DEMO_MODE = process.env.NEXT_PUBLIC_DEMO_MODE === "true";
export const CLERK_ENABLED = process.env.NEXT_PUBLIC_CLERK_ENABLED === "true" && !DEMO_MODE;
export const SESSION_CLEARED_EVENT = "stockpilot:session-cleared";
export const SESSION_CHANGED_EVENT = "stockpilot:session-changed";
export const WORKSPACE_CHANGED_EVENT = "stockpilot:workspace-changed";
let sessionTokenSnapshot: string | null | undefined;
type ClerkBridge = { identity: string | null; getToken: () => Promise<string | null>; signOut: () => Promise<void>; clearOrganization?: () => Promise<void> };
let clerkBridge: ClerkBridge | null = null;
export function configureClerkSession(bridge: ClerkBridge | null) {
  const previous = clerkBridge?.identity;
  clerkBridge = bridge;
  sessionTokenSnapshot = bridge?.identity ?? null;
  if (typeof window !== "undefined" && previous !== bridge?.identity) window.dispatchEvent(new Event(WORKSPACE_CHANGED_EVENT));
}
export function clerkSessionReady(identity: string | null) { return clerkBridge !== null && clerkBridge.identity === identity; }
export async function getClerkSessionToken(timeoutMs = 10_000): Promise<string | null> {
  let timer: ReturnType<typeof setTimeout> | undefined;
  try { return await Promise.race([clerkBridge?.getToken() ?? Promise.resolve(null), new Promise<never>((_, reject) => {
    timer = setTimeout(() => reject(new ApiClientError("Sign-in verification timed out. Please try again.", 408, "AUTH_TIMEOUT")), timeoutMs);
  })]); } finally { clearTimeout(timer); }
}
export async function clearClerkOrganization() { await clerkBridge?.clearOrganization?.(); }
export async function signOutSession() {
  clearSession();
  if (CLERK_ENABLED && clerkBridge) await clerkBridge.signOut();
  else window.location.assign("/login");
}
const configuredApiUrl = process.env.NEXT_PUBLIC_API_URL?.trim().replace(/\/+$/, "");

if (process.env.NODE_ENV === "production" && !DEMO_MODE) {
  if (!configuredApiUrl) {
    throw new Error("NEXT_PUBLIC_API_URL is required for a production backend-mode build");
  }
  if (process.env.VERCEL === "1" && !configuredApiUrl.startsWith("https://")) {
    throw new Error("NEXT_PUBLIC_API_URL must use HTTPS for a Vercel production backend-mode build");
  }
}

export const API_URL = configuredApiUrl ?? "http://localhost:8080";

export function isDemoMode() {
  return DEMO_MODE;
}

export type Page<T> = {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
};

export type Product = {
  id: string;
  sku?: string;
  name: string;
  categoryName?: string;
  brandName?: string;
  unitCode?: string;
  barcode?: string;
  defaultPurchasePrice: number | null;
  defaultSalesPrice: number;
  reorderPoint: number;
  currentStock: number;
};

export type Warehouse = {
  id: string;
  name: string;
  code?: string;
  address?: string;
};

export class ApiClientError extends Error {
  status: number;
  code?: string;

  constructor(message: string, status: number, code?: string) {
    super(message);
    this.name = "ApiClientError";
    this.status = status;
    this.code = code;
  }
}

export function getToken() {
  if (typeof window === "undefined") return null;
  if (CLERK_ENABLED) return clerkBridge?.identity ?? null;
  return window.localStorage.getItem("stockpilot.token");
}

export function getSessionRole() {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem("stockpilot.role");
}

export function getSessionPermissions() {
  if (typeof window === "undefined") return [];
  const raw = window.localStorage.getItem("stockpilot.permissions");
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === "string") : [];
  } catch {
    return [];
  }
}

export function setSessionPermissions(permissions: string[]) {
  window.localStorage.setItem("stockpilot.permissions", JSON.stringify(permissions));
}

export function setSession(token: string, role?: string, permissions?: string[]) {
  sessionTokenSnapshot = token;
  window.localStorage.setItem("stockpilot.token", token);
  if (role) {
    window.localStorage.setItem("stockpilot.role", role);
  } else {
    window.localStorage.removeItem("stockpilot.role");
  }
  if (permissions) {
    setSessionPermissions(permissions);
  } else {
    window.localStorage.removeItem("stockpilot.permissions");
  }
}

export function clearSession() {
  sessionTokenSnapshot = undefined;
  window.localStorage.removeItem("stockpilot.token");
  window.localStorage.removeItem("stockpilot.role");
  window.localStorage.removeItem("stockpilot.permissions");
  window.dispatchEvent(new Event(SESSION_CLEARED_EVENT));
}

export function loginDestination(role: string, search = window.location.search) {
  const platform = role.startsWith("PLATFORM_");
  const fallback = platform ? "/admin" : role === "CUSTOMER_USER" ? "/portal" : "/dashboard";
  const next = new URLSearchParams(search).get("next");
  if (!next?.startsWith("/") || next.startsWith("//") || next.includes("\\")) return fallback;
  const destination = new URL(next, window.location.origin);
  if (destination.origin !== window.location.origin || /^\/(login|register|sign-in|sign-up|auth|reset-password|forgot-password)(\/|$)/.test(destination.pathname)) return fallback;
  if (role === "CUSTOMER_USER" && !/^\/portal(\/|$)/.test(destination.pathname)) return fallback;
  if (platform && !/^\/admin(\/|$)/.test(destination.pathname)) return fallback;
  if (!platform && /^\/admin(\/|$)/.test(destination.pathname)) return fallback;
  return destination.pathname + destination.search + destination.hash;
}

export function invitationDestination(search = window.location.search) {
  const next = new URLSearchParams(search).get("next");
  if (!next?.startsWith("/") || next.startsWith("//") || next.includes("\\")) return null;
  const destination = new URL(next, window.location.origin);
  const token = destination.searchParams.get("token");
  return destination.origin === window.location.origin && destination.pathname === "/team/accept" && token && token.length <= 128
    ? destination.pathname + destination.search : null;
}

export type ApiOptions = RequestInit & { timeoutMs?: number };

export function observeSessionChange(event: StorageEvent) {
  if (CLERK_ENABLED) return;
  if (event.key !== null && !["stockpilot.token", "stockpilot.role", "stockpilot.permissions"].includes(event.key)) return;
  if (sessionTokenSnapshot !== undefined && getToken() !== sessionTokenSnapshot) {
    window.dispatchEvent(new Event(SESSION_CHANGED_EVENT));
  }
}

export async function api<T>(path: string, init: ApiOptions = {}): Promise<T> {
  if (DEMO_MODE) {
    assertSessionUnchanged(path);
    return demoApi<T>(path, init);
  }
  return request(path, init, false) as Promise<T>;
}

export async function apiText(path: string, init: ApiOptions = {}): Promise<string> {
  if (DEMO_MODE) return api<string>(path, init);
  return request(path, init, true) as Promise<string>;
}

function assertSessionUnchanged(path: string) {
  if (isPublicAuth(path)) return;
  const current = getToken();
  if (sessionTokenSnapshot === undefined) sessionTokenSnapshot = current;
  if (sessionTokenSnapshot !== current) {
    window.dispatchEvent(new Event(SESSION_CHANGED_EVENT));
    throw new ApiClientError("Your account changed in another tab. Reload before continuing.", 409, "SESSION_CHANGED");
  }
}

function isPublicAuth(path: string) {
  return /^\/api\/auth\/(login|google|register|forgot-password|reset-password|verify-email|resend-verification|invitations\/(preview|register))$/.test(path);
}

async function request(path: string, init: ApiOptions, asText: boolean): Promise<unknown> {
  assertSessionUnchanged(path);
  const publicAuth = isPublicAuth(path);
  const identity = getToken();
  const headers = new Headers(init.headers);
  const token = publicAuth || headers.has("Authorization") ? null : CLERK_ENABLED ? await getClerkSessionToken(Math.min(init.timeoutMs ?? 10_000, 10_000)) : identity;
  if (!publicAuth && identity !== getToken()) throw new ApiClientError("Workspace changed. Please retry from the selected workspace.", 409, "SESSION_CHANGED");
  if (!(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  if (token && !headers.has("Authorization")) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  if (!publicAuth && !headers.has("X-StockPilot-Mode")) {
    const platform = path.startsWith("/api/platform/") || (typeof window !== "undefined" && /^\/admin(\/|$)/.test(window.location.pathname));
    if (platform) headers.set("X-StockPilot-Mode", "platform");
    else if (typeof window !== "undefined" && !/^\/(auth|account|workspace|sign-in|sign-up|login)(\/|$)/.test(window.location.pathname)) headers.set("X-StockPilot-Mode", "workspace");
  }
  const { timeoutMs = path.includes("/import") ? 180_000 : 30_000, ...options } = init;
  const controller = new AbortController();
  const cancel = () => controller.abort(init.signal?.reason);
  if (init.signal?.aborted) cancel();
  else init.signal?.addEventListener("abort", cancel, { once: true });
  let timedOut = false;
  const timer = setTimeout(() => { timedOut = true; controller.abort(); }, timeoutMs);
  try {
  const response = await fetch(`${API_URL}${path}`, { ...options, headers, signal: controller.signal });
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: response.statusText }));
    if (response.status === 401 && token && getToken() === identity && !CLERK_ENABLED) {
      clearSession();
      if (window.location.pathname !== "/login") {
        const next = window.location.pathname + window.location.search;
        window.location.replace(`/login?expired=1&next=${encodeURIComponent(next)}`);
      }
    }
    const fields = body.fieldErrors ? Object.entries(body.fieldErrors).map(([field, message]) => `${field}: ${message}`).join("; ") : "";
    throw new ApiClientError([body.message ?? "Request failed", fields].filter(Boolean).join(". "), response.status, body.error);
  }
  const result = response.status === 204 ? undefined : await (asText ? response.text() : response.json());
  // A response can finish after another tab signs into a different workspace.
  // Discard it even when the request itself succeeded, before it can refill caches.
  if (!publicAuth && getToken() !== identity) {
    window.dispatchEvent(new Event(CLERK_ENABLED ? WORKSPACE_CHANGED_EVENT : SESSION_CHANGED_EVENT));
    throw new ApiClientError("Your account changed while this request was running. Reload before continuing.", 409, "SESSION_CHANGED");
  }
  return result;
  } catch (error) {
    if (timedOut) {
      const write = !["GET", "HEAD"].includes((init.method ?? "GET").toUpperCase());
      throw new ApiClientError(write ? "The request timed out; it may still have completed. Check the saved record or import status before retrying." : "The request timed out. Check your connection and try again.", 408, "REQUEST_TIMEOUT");
    }
    throw error;
  } finally {
    clearTimeout(timer);
    init.signal?.removeEventListener("abort", cancel);
  }
}

export function money(value: number | string | null | undefined, currency = "INR") {
  if (value == null || !Number.isFinite(Number(value))) return "—";
  const code = /^[A-Z]{3}$/.test(currency) ? currency : "INR";
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: code, minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(Number(value));
}

export function localDate(offsetDays = 0, now = new Date()) {
  const date = new Date(now);
  date.setDate(date.getDate() + offsetDays);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

export function newOperationId() { return crypto.randomUUID(); }
