import type { Metadata } from "next";
import Link from "next/link";
import { ArrowLeft, Boxes } from "lucide-react";
import { ServiceStatusPanel } from "@/components/ServiceStatusPanel";

export const metadata: Metadata = { title: "Service status | StockPilot", description: "Check the StockPilot frontend and backend connection before signing in." };

export default function ServiceStatusPage() {
  return <div className="min-h-screen bg-cream px-5 pb-16 pt-7 sm:px-8"><div className="mx-auto max-w-5xl">
    <header className="flex flex-wrap items-center justify-between gap-4 border-b border-ink/10 pb-6"><Link href="/" className="focus-ring inline-flex items-center gap-2 rounded text-lg font-semibold tracking-tight text-moss"><Boxes size={24} aria-hidden="true" />StockPilot</Link><Link href="/" className="focus-ring inline-flex items-center gap-2 rounded text-sm text-ink/60 hover:text-moss"><ArrowLeft size={15} aria-hidden="true" />Back to home</Link></header>
    <main className="py-10 sm:py-14"><p className="text-xs font-semibold uppercase tracking-[0.18em] text-moss">Connection and availability</p><h1 className="mt-3 text-3xl font-semibold tracking-tight text-ink sm:text-4xl">Service status</h1><p className="mb-8 mt-4 max-w-2xl text-sm leading-7 text-ink/60">See which service is responding and which is still waiting. No account or workspace access is needed to run these checks.</p><ServiceStatusPanel /></main>
    <footer className="border-t border-ink/10 pt-5 text-xs leading-6 text-ink/50">StockPilot service checks are separate from your business data and import results.</footer>
  </div></div>;
}
