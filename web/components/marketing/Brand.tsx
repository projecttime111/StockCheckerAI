import Link from "next/link";

export function Brand({ light = false }: { light?: boolean }) {
  return <Link href="/" className={`sp-brand focus-ring ${light ? "sp-brand-light" : ""}`} aria-label="StockPilot home">
    <span className="sp-brand-mark" aria-hidden="true"><svg width="24" height="24" viewBox="0 0 24 24" fill="none"><path d="m12 3 8 4.5v9L12 21l-8-4.5v-9L12 3Z" stroke="currentColor" strokeWidth="1.7" /><path d="m4 7.5 8 4.5 8-4.5M12 12v9m-4-16.5 8 4.5" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" /></svg></span>
    <span>StockPilot<span className="sp-brand-ai">AI</span></span>
  </Link>;
}
