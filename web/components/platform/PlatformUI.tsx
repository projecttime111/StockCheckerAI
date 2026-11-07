"use client";

import Link from "next/link";
import { ArrowLeft, ArrowRight, Building2, ChevronLeft, ChevronRight } from "lucide-react";
import { ReactNode } from "react";

export function PlatformHeading({ eyebrow, title, description, action }: { eyebrow?: string; title: string; description: string; action?: ReactNode }) {
  return <div className="mb-7 flex flex-wrap items-start justify-between gap-4"><div className="max-w-3xl">{eyebrow ? <p className="mb-2 text-xs font-semibold uppercase tracking-widest text-moss">{eyebrow}</p> : null}<h1 className="text-2xl font-semibold tracking-tight text-ink sm:text-3xl">{title}</h1><p className="mt-2 text-sm leading-6 text-ink/60">{description}</p></div>{action}</div>;
}

export function StatusBadge({ value }: { value?: string | null }) {
  const text = value ? value.replaceAll("_", " ").toLowerCase() : "Not recorded";
  const tone = ["ACTIVE", "COMMITTED", "COMPLETED", "UP", "HEALTHY", "SUCCESS"].includes(value ?? "") ? "bg-mint text-moss" : ["SUSPENDED", "FAILED", "ERROR", "DOWN", "PAST_DUE", "DELETED"].includes(value ?? "") ? "bg-coral/10 text-[#ad4534]" : "bg-slate-100 text-slate-600";
  return <span className={`inline-flex items-center rounded-full px-2.5 py-1 text-xs font-semibold capitalize ${tone}`}>{text}</span>;
}

export function PlatformEmpty({ title, children }: { title: string; children?: ReactNode }) {
  return <div className="flex flex-col items-center rounded-lg border border-dashed border-ink/15 px-6 py-12 text-center"><div className="mb-4 rounded-xl bg-slate-50 p-3 text-ink/40"><Building2 size={24} /></div><p className="font-semibold text-ink">{title}</p>{children ? <p className="mt-2 max-w-lg text-sm leading-6 text-ink/55">{children}</p> : null}</div>;
}

export function PlatformPager({ data, page, busy, onChange }: { data?: { totalElements: number; totalPages: number }; page: number; busy?: boolean; onChange: (page: number) => void }) {
  if (!data) return null;
  return <nav aria-label="Pagination" className="mt-5 flex flex-wrap items-center justify-between gap-4 border-t border-ink/10 pt-4 text-sm"><p className="text-ink/55">{data.totalElements.toLocaleString()} records <span className="mx-2 text-ink/20">/</span> Page {page + 1} of {Math.max(1, data.totalPages)}</p><div className="flex gap-2"><button type="button" className="focus-ring inline-flex items-center gap-1 rounded border border-ink/15 px-3 py-2 disabled:opacity-40" disabled={busy || page === 0} onClick={() => onChange(page - 1)}><ChevronLeft size={16} />Previous</button><button type="button" className="focus-ring inline-flex items-center gap-1 rounded border border-ink/15 px-3 py-2 disabled:opacity-40" disabled={busy || page + 1 >= data.totalPages} onClick={() => onChange(page + 1)}>Next<ChevronRight size={16} /></button></div></nav>;
}

export function PlatformLink({ href, children, back = false }: { href: string; children: ReactNode; back?: boolean }) {
  return <Link href={href} className="focus-ring inline-flex items-center gap-2 rounded text-sm font-semibold text-moss hover:underline">{back ? <ArrowLeft size={16} /> : null}{children}{back ? null : <ArrowRight size={16} />}</Link>;
}

export function DetailItem({ label, children }: { label: string; children: ReactNode }) {
  return <div className="min-w-0"><dt className="text-xs font-medium uppercase tracking-wide text-ink/45">{label}</dt><dd className="mt-1.5 break-words text-sm text-ink">{children ?? "—"}</dd></div>;
}

export function formatTimestamp(value?: string | null) {
  if (!value) return "Not recorded";
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? "Not recorded" : new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(date);
}

export function formatCount(value?: number | null) {
  return value == null ? "—" : value.toLocaleString();
}

export const tableClass = "w-full text-left text-sm [&_th]:whitespace-nowrap [&_th]:border-b [&_th]:border-ink/10 [&_th]:px-3 [&_th]:py-3 [&_th]:text-xs [&_th]:font-semibold [&_th]:uppercase [&_th]:tracking-wide [&_th]:text-ink/45 [&_td]:border-b [&_td]:border-ink/5 [&_td]:px-3 [&_td]:py-4 [&_td]:align-top";
export const textAreaClass = "focus-ring min-h-24 w-full rounded border border-ink/15 bg-white p-3 text-sm text-ink";
