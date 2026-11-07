import Link from "next/link";
import { ReactNode } from "react";

export function AccountFrame({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return <main className="min-h-screen bg-[#eef7f0] px-5 py-10"><div className="mx-auto max-w-3xl space-y-5"><Link href="/" className="text-lg font-bold text-moss">StockPilot AI</Link><section className="rounded border border-ink/10 bg-white p-6 shadow-soft"><h1 className="text-2xl font-semibold">{title}</h1>{description ? <p className="mt-2 text-sm text-ink/65">{description}</p> : null}<div className="mt-6 space-y-4">{children}</div></section></div></main>;
}
