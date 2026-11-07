"use client";

import { Activity, ArrowUpRight, Building2, CircleHelp, FileClock, LayoutDashboard, LogOut, ShieldCheck, Upload } from "lucide-react";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { createContext, ReactNode, useContext, useEffect, useState } from "react";
import { signOutSession } from "@/lib/api";
import { useSession } from "@/lib/session";
import { LoadingState } from "@/components/LoadingState";
import { isPlatformSession, PlatformSession } from "@/lib/platform";
import { QueryFeedback } from "@/components/QueryFeedback";

const navigation = [
  { href: "/admin", label: "Overview", icon: LayoutDashboard, permission: "platform.system_health.view" },
  { href: "/admin/businesses", label: "Clients", icon: Building2, permission: "platform.tenants.view" },
  { href: "/admin/imports", label: "Import monitoring", icon: Upload, permission: "platform.audit.view" },
  { href: "/admin/audit", label: "Audit trail", icon: FileClock, permission: "platform.audit.view" },
  { href: "/admin/system", label: "System status", icon: Activity, permission: "platform.system_health.view" }
];
const roleNames: Record<string, string> = { PLATFORM_SUPER_ADMIN: "Platform administrator", PLATFORM_SUPPORT: "Platform support", PLATFORM_BILLING_ADMIN: "Billing administrator" };
const AccessContext = createContext<PlatformSession | null>(null);

export function usePlatformAccess() {
  const session = useContext(AccessContext);
  return { session, can: (permission: string) => !!session?.permissions.includes(permission) };
}

export function PlatformShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const session = useSession("platform");
  const [signingOut, setSigningOut] = useState(false);
  const [signOutError, setSignOutError] = useState<string | null>(null);
  const verified = session.isFetchedAfterMount && !session.error && isPlatformSession(session.data);
  const can = (permission: string) => verified && !!session.data?.permissions.includes(permission);
  const billingLanding = verified && pathname === "/admin" && !can("platform.system_health.view") && can("platform.tenants.view");

  useEffect(() => { if (billingLanding) router.replace("/admin/businesses"); }, [billingLanding, router]);

  async function signOut() {
    setSigningOut(true);
    setSignOutError(null);
    try { await signOutSession(); } catch (error) { setSignOutError(error instanceof Error ? error.message : "Sign out failed. Please try again."); setSigningOut(false); }
  }

  if (!session.isFetchedAfterMount || session.isPending) return <main className="flex min-h-screen items-center justify-center bg-slate-50 p-6"><LoadingState title="Verifying platform access…" description="Checking your administrator permissions." /></main>;
  if (session.error) return <main className="mx-auto max-w-lg px-5 py-20"><h1 className="mb-4 text-2xl font-semibold">Platform access</h1><QueryFeedback queries={[session]} /><div className="flex flex-wrap gap-4"><Link className="text-sm font-semibold text-moss underline" href="/workspace/select">Choose a workspace</Link><Link className="text-sm font-semibold text-moss underline" href="/login">Return to sign in</Link></div></main>;
  if (!verified) return <main className="mx-auto max-w-lg px-5 py-20"><ShieldCheck className="mb-5 text-moss" size={32} /><h1 className="text-2xl font-semibold">Platform access required</h1><p role="alert" className="mt-3 text-sm leading-6 text-ink/60">This area is for StockPilot platform staff. Business owner and workspace admin accounts cannot access it.</p><Link className="mt-6 inline-block font-semibold text-moss underline" href="/workspace/select">Return to your workspace</Link></main>;

  const current = navigation.find(item => item.href !== "/admin" && (pathname === item.href || pathname.startsWith(`${item.href}/`))) ?? navigation[0];
  const permitted = can(current.permission);
  const visibleNavigation = navigation.filter(item => can(item.permission));
  const verifiedSession = session.data!;

  return <AccessContext.Provider value={verifiedSession}><div className="min-h-screen bg-[#f6f8fa]">
    <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col bg-[#102b26] text-white lg:flex">
      <Link href={can("platform.system_health.view") ? "/admin" : "/admin/businesses"} className="focus-ring flex h-24 items-center gap-3 border-b border-white/10 px-6"><span className="rounded-xl bg-white/10 p-2.5"><ShieldCheck size={23} /></span><span><span className="block text-lg font-semibold tracking-tight">StockPilot</span><span className="text-xs tracking-wide text-white/45">PLATFORM CONSOLE</span></span></Link>
      <div className="px-6 pb-3 pt-7 text-[10px] font-semibold uppercase tracking-widest text-white/35">Operations</div>
      <nav aria-label="Platform navigation" className="space-y-1 px-3">{visibleNavigation.map(item => { const Icon = item.icon; const active = current.href === item.href; return <Link key={item.href} href={item.href} aria-current={active ? "page" : undefined} className={`focus-ring flex items-center gap-3 rounded-lg px-3 py-3 text-sm transition ${active ? "bg-white/10 font-semibold text-white" : "text-white/60 hover:bg-white/5 hover:text-white"}`}><Icon size={18} />{item.label}</Link>; })}</nav>
      <div className="mt-auto p-5"><div className="rounded-lg border border-white/10 bg-white/[0.03] p-4"><CircleHelp className="mb-3 text-white/45" size={18} /><p className="text-xs leading-5 text-white/55">Manage subscribed businesses and platform operations. Business inventory stays in each client workspace.</p></div></div>
    </aside>
    <div className="lg:pl-64"><header className="border-b border-ink/10 bg-white"><div className="mx-auto flex min-h-20 max-w-screen-2xl flex-wrap items-center justify-between gap-3 px-5 py-4 sm:px-8"><div><p className="text-xs font-semibold uppercase tracking-wider text-ink/40">Platform operations</p><p className="mt-1 text-sm font-medium text-ink">{roleNames[verifiedSession.role ?? ""] ?? "Platform staff"}</p></div><div className="flex flex-wrap items-center gap-4">{verifiedSession.workspaces?.length ? <Link href="/workspace/select" className="focus-ring inline-flex items-center gap-1 text-xs font-semibold text-moss">Business workspace<ArrowUpRight size={14} /></Link> : null}<div className="hidden text-right sm:block"><p className="text-sm font-medium">{verifiedSession.fullName || "Platform staff"}</p><p className="text-xs text-ink/45">{verifiedSession.email}</p></div><button type="button" disabled={signingOut} onClick={() => void signOut()} className="focus-ring inline-flex items-center gap-2 rounded-lg border border-ink/10 px-3 py-2 text-xs font-medium disabled:opacity-50"><LogOut size={15} />{signingOut ? "Signing out…" : "Sign out"}</button></div></div><nav aria-label="Mobile platform navigation" className="flex gap-2 overflow-x-auto border-t border-ink/5 px-5 py-3 lg:hidden">{visibleNavigation.map(item => <Link key={item.href} href={item.href} aria-current={current.href === item.href ? "page" : undefined} className={`focus-ring whitespace-nowrap rounded-md px-3 py-2 text-xs font-semibold ${current.href === item.href ? "bg-mint text-moss" : "text-ink/60"}`}>{item.label}</Link>)}</nav></header>
      <main className="mx-auto max-w-screen-2xl px-5 py-7 sm:px-8 sm:py-9">{signOutError ? <p role="alert" className="mb-5 text-sm text-coral">{signOutError}</p> : null}{billingLanding ? <p role="status">Opening client management…</p> : permitted ? children : <div role="alert" className="rounded-lg border border-ink/10 bg-white p-6"><h1 className="text-xl font-semibold">Access restricted</h1><p className="mt-2 text-sm text-ink/60">Your platform role does not have permission to open this area.</p><Link href="/admin/businesses" className="mt-4 inline-block text-sm font-semibold text-moss underline">Go to clients</Link></div>}</main>
    </div>
  </div></AccessContext.Provider>;
}
