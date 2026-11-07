import Link from "next/link";
import { ReactNode } from "react";
import { ArrowLeft } from "lucide-react";
import { Brand } from "@/components/marketing/Brand";

export function AccountFrame({ title, description, children }: { title: string; description?: string; children: ReactNode }) {
  return <main className="sp-account-page"><div className="sp-account-container"><header className="sp-account-header"><Brand /><Link href="/" className="sp-back-link focus-ring"><ArrowLeft size={15} /> Back to home</Link></header><section className="sp-account-card"><div className="sp-account-accent" /><p className="sp-eyebrow">Your StockPilot workspace</p><h1>{title}</h1>{description ? <p className="sp-account-description">{description}</p> : null}<div className="mt-7 min-w-0 space-y-4">{children}</div></section><p className="sp-account-footer">A clearer view. A more confident next step.</p></div></main>;
}
