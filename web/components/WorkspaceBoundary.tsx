"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { ReactNode, useEffect } from "react";
import { getToken, signOutSession } from "@/lib/api";
import { useSession } from "@/lib/session";
import { LoadingState } from "@/components/LoadingState";

const protectedWorkspace = /^\/(dashboard|products|stock|sales|purchases|customers|suppliers|forecasts?|dead-stock|data-quality|assistant|ai-assistant|imports|integrations|reports|audit-logs|settings|team(?!\/accept)|billing|portal)(\/|$)/;
export function WorkspaceBoundary({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  return protectedWorkspace.test(pathname) ? <BusinessAccessGate>{children}</BusinessAccessGate> : <>{children}</>;
}
function BusinessAccessGate({ children }: { children: ReactNode }) {
  const session = useSession();
  const router = useRouter();
  const signedIn = !!getToken();
  useEffect(() => { if (!signedIn) router.replace(`/login?next=${encodeURIComponent(window.location.pathname + window.location.search)}`); }, [router, signedIn]);
  if (!signedIn || session.isLoading) return <div className="mx-auto max-w-xl p-8"><LoadingState title="Checking workspace access…" description="Securely opening your business workspace." /></div>;
  if (session.error) return <div className="mx-auto mt-12 max-w-lg rounded border bg-white p-6" role="alert"><p>{session.error.message}</p><button className="mt-4 underline" onClick={() => session.refetch()}>Retry</button><Link className="ml-5 underline" href="/workspace/select">Switch workspace</Link><button className="ml-5 underline" onClick={() => void signOutSession()}>Sign out</button></div>;
  if (!session.data?.tenantId || session.data.role?.startsWith("PLATFORM_")) return <div className="mx-auto mt-12 max-w-lg rounded border bg-white p-6"><h1 className="text-xl font-semibold">Choose a business workspace</h1><p className="mt-2 text-sm text-ink/65">Select a workspace you belong to before accessing business data.</p><Link className="mt-5 inline-block rounded bg-moss px-4 py-2 text-white" href="/workspace/select">Choose workspace</Link>{session.data?.platformRole ? <Link className="ml-4 underline" href="/admin">Platform administration</Link> : null}</div>;
  return <>{children}</>;
}
