"use client";

import { LoaderCircle } from "lucide-react";
import { useEffect, useState, useSyncExternalStore } from "react";
import { getRequestActivity, subscribeRequestActivity } from "@/lib/api";

const idle = { pending: 0, writes: 0 };
const serverActivity = () => idle;

export function RequestProgress() {
  const activity = useSyncExternalStore(subscribeRequestActivity, getRequestActivity, serverActivity);
  return activity.pending > 0 ? <ActiveProgress writing={activity.writes > 0} /> : null;
}

function ActiveProgress({ writing }: { writing: boolean }) {
  const [visible, setVisible] = useState(false);
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    const show = window.setTimeout(() => setVisible(true), 300);
    const explain = window.setTimeout(() => setSlow(true), 10_000);
    return () => { window.clearTimeout(show); window.clearTimeout(explain); };
  }, []);
  if (!visible) return null;
  return <>
    <div aria-hidden="true" className="pointer-events-none fixed inset-x-0 top-0 z-[80] h-1 overflow-hidden bg-mint"><div className="h-full w-full bg-moss/70 motion-safe:animate-pulse" /></div>
    <div role="status" aria-live="polite" className="pointer-events-none fixed bottom-5 right-5 z-[80] flex max-w-sm items-start gap-3 rounded-2xl border border-moss/15 bg-white px-4 py-3 text-sm shadow-lg">
      <LoaderCircle aria-hidden="true" size={18} className="mt-0.5 shrink-0 text-moss motion-safe:animate-spin" />
      <div><p className="font-medium text-ink">{writing ? "Processing your request…" : "Loading your data…"}</p>{slow && <p className="mt-1 text-xs leading-5 text-ink/60">Still working. Some requests take longer after inactivity.</p>}</div>
    </div>
  </>;
}
