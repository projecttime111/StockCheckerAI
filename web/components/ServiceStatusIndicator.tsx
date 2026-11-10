"use client";

import Link from "next/link";
import { ChevronRight } from "lucide-react";
import { useServiceHealth } from "@/lib/serviceHealth";
import { ServiceStatusBadge } from "./ServiceStatusBadge";

export function ServiceStatusIndicator() {
  const { frontend, backend } = useServiceHealth();
  return <div className="flex min-h-10 items-center justify-end border-b border-ink/5 bg-white px-3 py-1.5 print:hidden">
    <Link href="/status" aria-label={`Service status: frontend ${frontend.status}, backend ${backend.status}. View details.`} className="focus-ring inline-flex flex-wrap items-center justify-end gap-x-3 gap-y-1 rounded-full border border-ink/10 bg-cream/50 px-3 py-1.5 text-[11px] text-ink/70 hover:border-moss/30 hover:text-moss">
      <span className="inline-flex items-center gap-1.5"><span>Frontend</span><ServiceStatusBadge probe={frontend} compact /></span>
      <span aria-hidden="true" className="h-3 border-l border-ink/15" />
      <span className="inline-flex items-center gap-1.5"><span>Backend</span><ServiceStatusBadge probe={backend} compact /></span>
      <ChevronRight size={13} aria-hidden="true" />
    </Link>
  </div>;
}
