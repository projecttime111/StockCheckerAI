import { LoaderCircle } from "lucide-react";

export function LoadingState({ label, title, description, compact = false }: { label?: string; title?: string; description?: string; compact?: boolean }) {
  return <div role="status" aria-live="polite" className={compact ? "flex items-start gap-3 py-2" : "flex items-start gap-4 rounded-2xl border border-ink/10 bg-white p-6"}>
    <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-mint text-moss" aria-hidden="true"><LoaderCircle size={22} className="motion-safe:animate-spin" /></span>
    <div className="min-w-0 pt-1"><p className="font-medium text-ink">{title ?? label ?? "Loading your data…"}</p>{description ? <p className="mt-1 text-sm leading-6 text-ink/60">{description}</p> : null}
      {!compact && <div className="mt-4 flex gap-1.5" aria-hidden="true">{[0, 1, 2].map(step => <span key={step} className="h-1.5 w-8 rounded-full bg-moss/25 motion-safe:animate-pulse" style={{ animationDelay: `${step * 180}ms` }} />)}</div>}
    </div>
  </div>;
}
