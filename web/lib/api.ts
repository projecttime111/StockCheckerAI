import { demoApi } from "./demoApi";

export const DEMO_MODE = process.env.NEXT_PUBLIC_DEMO_MODE === "true";
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
  }
  if (permissions) {
    setSessionPermissions(permissions);
  }
}

export function clearSession() {
  window.localStorage.removeItem("stockpilot.token");
  window.localStorage.removeItem("stockpilot.role");
  window.localStorage.removeItem("stockpilot.permissions");
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (DEMO_MODE) {
    return demoApi<T>(path, init);
  }
  const token = getToken();
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
    if (response.status === 401) {
      clearSession();
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
