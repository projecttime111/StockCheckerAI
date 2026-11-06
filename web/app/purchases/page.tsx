"use client";
import { AppShell } from "@/components/AppShell";
import { InvoiceWorkspace } from "@/components/InvoiceWorkspace";

export default function PurchasesPage() {
  return <AppShell title="Purchases"><InvoiceWorkspace kind="purchases" /></AppShell>;
}
