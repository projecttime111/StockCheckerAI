"use client";
import Link from "next/link";
import { AppShell } from "@/components/AppShell";
import { InvoiceWorkspace } from "@/components/InvoiceWorkspace";

export default function SalesPage() {
  return <AppShell title="Sales"><nav aria-label="Sales views" className="mb-5 flex flex-wrap gap-6 border-b border-ink/15 text-sm"><Link className="border-b-2 border-moss py-3 font-semibold text-moss" aria-current="page" href="/sales">Sales invoices</Link><Link className="py-3 text-ink/65" href="/sales/retail-counter">Retail counter sales</Link></nav><InvoiceWorkspace kind="sales" /></AppShell>;
}
