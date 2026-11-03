import { demoApi } from "./demoApi";

export const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";
export const DEMO_MODE = process.env.NEXT_PUBLIC_DEMO_MODE === "true";
export const SESSION_CLEARED_EVENT = "stockpilot:session-cleared";

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
  defaultPurchasePrice: number;
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
  window.localStorage.removeItem("stockpilot.token");
  window.localStorage.removeItem("stockpilot.role");
  window.localStorage.removeItem("stockpilot.permissions");
  window.dispatchEvent(new Event(SESSION_CLEARED_EVENT));
}

export function loginDestination(role: string, search = window.location.search) {
  const fallback = role === "CUSTOMER_USER" ? "/portal" : "/dashboard";
  const next = new URLSearchParams(search).get("next");
  if (!next?.startsWith("/") || next.startsWith("//") || next.includes("\\")) return fallback;
  const destination = new URL(next, window.location.origin);
  if (destination.origin !== window.location.origin || /^\/(login|register|reset-password|forgot-password)(\/|$)/.test(destination.pathname)) return fallback;
  if (role === "CUSTOMER_USER" && !/^\/portal(\/|$)/.test(destination.pathname)) return fallback;
  return destination.pathname + destination.search + destination.hash;
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (DEMO_MODE) {
    return demoApi<T>(path, init);
  }
  const publicAuth = /^\/api\/auth\/(login|google|register|forgot-password|reset-password|verify-email|resend-verification)$/.test(path);
  const token = publicAuth ? null : getToken();
  const headers = new Headers(init.headers);
  if (!(init.body instanceof FormData)) {
    headers.set("Content-Type", "application/json");
  }
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }
  const response = await fetch(`${API_URL}${path}`, { ...init, headers });
  if (!response.ok) {
    const body = await response.json().catch(() => ({ message: response.statusText }));
    if (response.status === 401 && token && getToken() === token) {
      clearSession();
      if (window.location.pathname !== "/login") {
        const next = window.location.pathname + window.location.search;
        window.location.replace(`/login?expired=1&next=${encodeURIComponent(next)}`);
      }
    }
    throw new ApiClientError(body.message ?? "Request failed", response.status, body.error);
  }
  if (response.status === 204) {
    return undefined as T;
  }
  return response.json() as Promise<T>;
}

export function money(value: number | string | null | undefined) {
  const amount = Number(value ?? 0);
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 0 }).format(amount);
}
