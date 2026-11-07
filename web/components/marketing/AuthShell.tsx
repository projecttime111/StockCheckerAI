import Link from "next/link";
import { ArrowLeft, Check, Layers3, TrendingUp } from "lucide-react";
import type { ReactNode } from "react";
import { Brand } from "./Brand";

export function AuthShell({ children, signup = false }: { children: ReactNode; signup?: boolean }) {
  return <main className="sp-auth">
    <aside className="sp-auth-story">
      <Brand light />
      <div className="sp-auth-story-content">
        <span className="sp-eyebrow sp-eyebrow-light">A clearer view of your business</span>
        <h1>Your stock.<br />Your team.<br /><span>In sync.</span></h1>
        <p>Bring your inventory, everyday operations, and next decisions into one workspace.</p>
        <div className="sp-auth-illustration" aria-hidden="true">
          <div className="sp-auth-mini-heading"><span className="sp-mini-icon"><Layers3 size={18} /></span><span>Inventory overview<small>Everything in its place</small></span><span className="sp-mini-dot" /></div>
          <div className="sp-auth-bars">{[35, 55, 43, 72, 60, 86, 77, 95].map((height, index) => <i key={index} style={{ height: `${height}%` }} />)}</div>
          <div className="sp-auth-mini-note"><TrendingUp size={17} /><span>See patterns. Plan your next move.</span></div>
        </div>
        <ul className="sp-auth-benefits">
          <li><Check size={16} />Stock across your warehouses</li>
          <li><Check size={16} />Tally &amp; Excel import workflows</li>
          <li><Check size={16} />The right access for your team</li>
        </ul>
      </div>
      <p className="sp-auth-footnote">Built for the way retailers and distributors work.</p>
    </aside>
    <section className="sp-auth-main" aria-label={signup ? "Create your account" : "Access your account"}>
      <div className="sp-auth-top"><Link className="sp-back-link focus-ring" href="/"><ArrowLeft size={16} /> Back to home</Link><div className="sp-auth-mobile-brand"><Brand /></div></div>
      <div className="sp-auth-content">{children}</div>
      <p className="sp-auth-bottom">Your business. Your workspace. A clearer way forward.</p>
    </section>
  </main>;
}
