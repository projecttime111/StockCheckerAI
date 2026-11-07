"use client";

import clsx from "clsx";
import { useQueryClient } from "@tanstack/react-query";
import {
  BarChart3,
  Bot,
  Boxes,
  ClipboardCheck,
  FileText,
  FileUp,
  History,
  Home,
  LineChart,
  Package,
  ReceiptText,
  Settings,
  ShoppingCart,
  Sparkles,
  Truck,
  Users
} from "lucide-react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { ReactNode } from "react";
import { DEMO_MODE, signOutSession } from "@/lib/api";
import { useSession } from "@/lib/session";

type Role =
  | "OWNER"
  | "ADMIN"
  | "MANAGER"
  | "STAFF"
  | "WAREHOUSE_STAFF"
  | "SALES_STAFF"
  | "PURCHASE_MANAGER"
  | "ACCOUNTANT"
  | "VIEWER"
  | "AUDITOR"
  | "CUSTOMER_USER"
  | "SUPPLIER_USER"
  | "PLATFORM_SUPER_ADMIN"
  | "PLATFORM_SUPPORT"
  | "PLATFORM_BILLING_ADMIN";

type NavItem = { href: string; label: string; icon: typeof Home; roles: Role[]; permission?: string };

const businessRoles: Role[] = ["OWNER", "ADMIN", "MANAGER", "STAFF", "WAREHOUSE_STAFF", "SALES_STAFF", "PURCHASE_MANAGER", "ACCOUNTANT", "VIEWER", "AUDITOR"];
const productRoles: Role[] = ["OWNER", "ADMIN", "MANAGER", "STAFF", "WAREHOUSE_STAFF", "SALES_STAFF", "PURCHASE_MANAGER", "VIEWER", "AUDITOR"];
const stockRoles: Role[] = ["OWNER", "ADMIN", "MANAGER", "STAFF", "WAREHOUSE_STAFF", "SALES_STAFF", "PURCHASE_MANAGER", "VIEWER", "AUDITOR"];
const salesRoles: Role[] = ["OWNER", "ADMIN", "MANAGER", "STAFF", "SALES_STAFF", "ACCOUNTANT", "AUDITOR"];
const purchaseRoles: Role[] = ["OWNER", "ADMIN", "MANAGER", "PURCHASE_MANAGER", "ACCOUNTANT", "AUDITOR"];
const customerRoles: Role[] = ["OWNER", "ADMIN", "MANAGER", "STAFF", "SALES_STAFF", "ACCOUNTANT", "AUDITOR"];
const supplierRoles: Role[] = ["OWNER", "ADMIN", "MANAGER", "PURCHASE_MANAGER", "AUDITOR"];
const intelligenceRoles: Role[] = ["OWNER", "ADMIN", "MANAGER", "PURCHASE_MANAGER", "ACCOUNTANT", "VIEWER", "AUDITOR"];
const adminRoles: Role[] = ["OWNER", "ADMIN", "MANAGER", "ACCOUNTANT", "AUDITOR"];
const ownerAdmin: Role[] = ["OWNER", "ADMIN"];

const navGroups: { title: string; items: NavItem[] }[] = [
  {
    title: "Workspace",
    items: [
      { href: "/dashboard", label: "Dashboard", icon: Home, roles: businessRoles, permission: "dashboard.view" },
      { href: "/products", label: "Products", icon: Package, roles: productRoles, permission: "products.view" },
      { href: "/stock", label: "Stock", icon: Boxes, roles: stockRoles, permission: "stock.view" },
      { href: "/sales", label: "Sales", icon: ReceiptText, roles: salesRoles, permission: "sales.view" }
    ]
  },
  {
    title: "Business",
    items: [
      { href: "/purchases", label: "Purchases", icon: Truck, roles: purchaseRoles, permission: "purchases.view" },
      { href: "/customers", label: "Customers", icon: Users, roles: customerRoles, permission: "customers.view" },
      { href: "/suppliers", label: "Suppliers", icon: ShoppingCart, roles: supplierRoles, permission: "suppliers.view" }
    ]
  },
  {
    title: "Intelligence",
    items: [
      { href: "/forecasts", label: "Forecasts", icon: LineChart, roles: intelligenceRoles, permission: "forecast.view" },
      { href: "/dead-stock", label: "Dead Stock", icon: Boxes, roles: intelligenceRoles, permission: "dead_stock.view" },
      { href: "/data-quality", label: "Data Quality", icon: Sparkles, roles: intelligenceRoles, permission: "data_quality.view" },
      { href: "/assistant", label: "AI Assistant", icon: Bot, roles: ["OWNER", "ADMIN", "MANAGER", "STAFF"], permission: "ai.assistant.use" }
    ]
  },
  {
    title: "Workspace management",
    items: [
      { href: "/imports", label: "Imports", icon: FileUp, roles: adminRoles, permission: "imports.view" },
      { href: "/integrations/tally", label: "Tally Hub", icon: ClipboardCheck, roles: adminRoles, permission: "integrations.tally.view" },
      { href: "/reports", label: "Reports", icon: FileText, roles: ["OWNER", "ADMIN", "MANAGER", "ACCOUNTANT", "VIEWER", "AUDITOR"], permission: "reports.view" },
      { href: "/audit-logs", label: "Audit Logs", icon: History, roles: ["OWNER", "ADMIN", "ACCOUNTANT", "AUDITOR"], permission: "audit.view" },
      { href: "/team", label: "Team", icon: Users, roles: ownerAdmin, permission: "users.view" },
      { href: "/billing", label: "Plan & usage", icon: ReceiptText, roles: ownerAdmin, permission: "billing.view" },
      { href: "/settings", label: "Settings", icon: Settings, roles: ownerAdmin, permission: "settings.view" }
    ]
  }
];

const portalNav = [
  { title: "Customer Portal", items: [{ href: "/portal", label: "Dealer Portal", icon: Users, roles: ["CUSTOMER_USER"] as Role[], permission: "portal.customer.view" }] }
];

const roleLabels: Record<Role, string> = {
  OWNER: "Owner panel",
  ADMIN: "Business admin",
  MANAGER: "Manager panel",
  STAFF: "Staff operations",
  WAREHOUSE_STAFF: "Warehouse panel",
  SALES_STAFF: "Sales panel",
  PURCHASE_MANAGER: "Purchase panel",
  ACCOUNTANT: "Finance panel",
  VIEWER: "Viewer dashboard",
  AUDITOR: "Auditor panel",
  CUSTOMER_USER: "Dealer portal",
  SUPPLIER_USER: "Supplier portal",
  PLATFORM_SUPER_ADMIN: "Platform admin",
  PLATFORM_SUPPORT: "Platform support",
  PLATFORM_BILLING_ADMIN: "Billing admin"
};

export function AppShell({ children, title, actions }: { children: ReactNode; title: string; actions?: ReactNode }) {
  const pathname = usePathname();
  const queryClient = useQueryClient();
  const session = useSession();
  const role = (session.data?.role ?? null) as Role | null;
  const permissions = session.data?.permissions ?? [];
  const accessError = session.error?.message;
  const workspaceName = session.data?.workspaces.find(workspace => workspace.tenantId === session.data?.tenantId)?.name ?? "Workspace";

  const activeRole = role;
  const visibleGroups = activeRole === "CUSTOMER_USER"
    ? portalNav
    : navGroups
        .map((group) => ({ ...group, items: group.items.filter((item) => canAccess(item, activeRole, permissions) && (!DEMO_MODE || !["/team", "/billing"].includes(item.href))) }))
        .filter((group) => group.items.length > 0);
  const canUseAssistant = canUsePermission("ai.assistant.use", activeRole, permissions, ["OWNER", "ADMIN", "MANAGER", "STAFF"]);

  const route = [...navGroups, ...portalNav].flatMap(group => group.items).find(item => pathname === item.href || pathname.startsWith(`${item.href}/`));
  const permitted = !route || canAccess(route, activeRole, permissions);

  return (
    <div className="min-h-screen bg-[#f7faf8]">
      <aside className="fixed inset-y-0 left-0 z-20 hidden w-64 overflow-y-auto border-r border-ink/10 bg-white lg:block">
        <div className="flex h-16 items-center gap-3 border-b border-ink/10 px-5">
          <div className="flex h-10 w-10 items-center justify-center rounded bg-moss text-white">
            <BarChart3 size={21} />
          </div>
          <div>
            <div className="text-base font-bold tracking-normal">StockPilot AI</div>
            <div className="max-w-40 truncate text-xs text-ink/55">{workspaceName}</div>
          </div>
        </div>
        <nav className="p-3">
          {visibleGroups.map((group) => (
            <div key={group.title} className="mb-4">
              <div className="mb-2 px-3 text-[11px] font-bold uppercase tracking-normal text-ink/40">{group.title}</div>
              {group.items.map((item) => {
                const Icon = item.icon;
                const active = pathname.startsWith(item.href);
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    className={clsx(
                      "mb-1 flex h-10 items-center gap-3 rounded px-3 text-sm font-medium transition",
                      active ? "bg-mint text-moss" : "text-ink/70 hover:bg-ink/[0.04] hover:text-ink"
                    )}
                  >
                    <Icon size={18} />
                    {item.label}
                  </Link>
                );
              })}
            </div>
          ))}
        </nav>
      </aside>

      <div className="lg:pl-64">
        <header className="sticky top-0 z-10 border-b border-ink/10 bg-white/90 backdrop-blur">
          <div className="flex min-h-16 flex-wrap items-center justify-between gap-3 px-4 py-3 sm:px-6">
            <div>
              <h1 className="text-xl font-semibold tracking-normal text-ink">{title}</h1>
              <p className="text-sm text-ink/55">{workspaceName} - {activeRole ? roleLabels[activeRole] : "Workspace"} - Connect Tally or Excel. Predict stockouts. Reduce dead stock. Improve profit.</p>
            </div>
            <div className="flex items-center gap-2">
              {permitted ? actions : null}
              {!DEMO_MODE ? <Link className="rounded border border-ink/15 px-3 py-2 text-sm" href="/workspace/select">Switch workspace</Link> : null}
              <Link className="rounded border border-ink/15 px-3 py-2 text-sm" href="/account/profile">My account</Link>
              {session.data?.platformRole ? <Link className="text-sm font-semibold text-moss" href="/workspace/select">Platform admin</Link> : null}
              {activeRole !== "CUSTOMER_USER" && canUseAssistant ? (
                <Link
                  className="focus-ring inline-flex items-center gap-2 rounded bg-moss px-3 py-2 text-sm font-semibold text-white"
                  href="/assistant"
                >
                  <Bot size={16} />
                  AI Assistant
                </Link>
              ) : null}
              <button
                className="focus-ring rounded border border-ink/15 px-3 py-2 text-sm font-medium text-ink/70 hover:bg-ink/[0.04]"
                onClick={() => {
                  queryClient.clear();
                  void signOutSession();
                }}
              >
                Sign out
              </button>
            </div>
          </div>
          <div className="flex gap-2 overflow-x-auto border-t border-ink/10 px-4 py-2 lg:hidden">
            {visibleGroups.flatMap((group) => group.items).map((item) => (
                <Link
                  key={item.href}
                  href={item.href}
                  className={clsx(
                    "whitespace-nowrap rounded px-3 py-2 text-sm font-medium",
                    pathname.startsWith(item.href) ? "bg-mint text-moss" : "text-ink/65"
                  )}
                >
                  {item.label}
                </Link>
              ))}
          </div>
        </header>
        <main className="px-4 py-6 sm:px-6">{accessError ? <div role="alert"><p>{accessError}</p><button className="mt-3 underline" onClick={() => window.location.reload()}>Retry access check</button></div> : !activeRole ? <p role="status">Checking your workspace access…</p> : !permitted ? <div role="alert" className="rounded border border-amber/30 bg-white p-5">Your role does not have access to this page. Choose an available page from the navigation.</div> : children}</main>
      </div>
    </div>
  );
}

function canAccess(item: NavItem, role: Role | null, permissions: string[]) {
  return canUsePermission(item.permission, role, permissions, item.roles);
}

function canUsePermission(permission: string | undefined, role: Role | null, permissions: string[], fallbackRoles: Role[]) {
  if (!role) return false;
  if (permission) {
    return permissions.includes(permission);
  }
  return fallbackRoles.includes(role);
}
