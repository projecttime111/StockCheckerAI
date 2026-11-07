import { ArrowDownUp, ArrowUpRight, Box, ChartNoAxesCombined, Check, ChevronDown, LayoutDashboard, Package, Search, Users } from "lucide-react";

export function DashboardPreview() {
  return <figure className="sp-dashboard-wrap" aria-label="Illustrative StockPilot dashboard using example inventory data">
    <figcaption className="sp-preview-caption"><span className="sp-preview-dot" /> A look inside your workspace <span>Illustrative preview · example data</span></figcaption>
    <div className="sp-dashboard">
      <aside className="sp-preview-sidebar" aria-hidden="true">
        <div className="sp-preview-business"><span>G</span><div>Greenway Stores<small>Business workspace</small></div><ChevronDown size={12} /></div>
        <span className="sp-preview-label">WORKSPACE</span>
        <div className="sp-preview-nav selected"><LayoutDashboard size={15} />Overview</div>
        <div className="sp-preview-nav"><Package size={15} />Products</div>
        <div className="sp-preview-nav"><ArrowDownUp size={15} />Stock movements</div>
        <div className="sp-preview-nav"><ChartNoAxesCombined size={15} />Forecasts</div>
        <div className="sp-preview-nav"><Users size={15} />Team</div>
        <div className="sp-preview-bottom"><span>GS</span><div>Your business<small>One connected view</small></div></div>
      </aside>
      <div className="sp-preview-main">
        <div className="sp-preview-toolbar"><span>Workspace / <strong>Overview</strong></span><span><Search size={14} /><span className="sp-preview-avatar">GS</span></span></div>
        <div className="sp-preview-content">
          <div className="sp-preview-title"><div><h3>Your business, at a glance</h3><p>A little clarity for the day ahead.</p></div><span className="sp-example-tag">EXAMPLE</span></div>
          <div className="sp-preview-stats"><div><span>Products in your catalog</span><strong>248 <Package size={17} /></strong><small>Across your categories</small></div><div><span>Low-stock products</span><strong>12 <Box size={17} /></strong><small className="sp-preview-warning">Ready for a closer look</small></div><div><span>Warehouses</span><strong>3 <ArrowUpRight size={17} /></strong><small>Stock in one place</small></div></div>
          <div className="sp-preview-panels">
            <div className="sp-preview-chart"><div className="sp-preview-section-title">Demand outlook <span>Example trend</span></div><div className="sp-chart-key"><i /> Sales history <i /> Forecast</div><svg viewBox="0 0 440 145" role="img" aria-label="Example chart illustrating a historical sales trend and a projected trend"><defs><linearGradient id="sp-chart-fill" x1="0" x2="0" y1="0" y2="1"><stop offset="0%" stopColor="#acd8bd" stopOpacity=".65" /><stop offset="100%" stopColor="#acd8bd" stopOpacity="0" /></linearGradient></defs><path d="M10 30H430M10 65H430M10 100H430M10 135H430" stroke="#e9eeea" strokeWidth="1" /><path d="M10 114C40 114 45 86 75 93S116 66 145 78 185 43 218 62 251 55 275 40V140H10Z" fill="url(#sp-chart-fill)" /><path d="M10 114C40 114 45 86 75 93S116 66 145 78 185 43 218 62 251 55 275 40" stroke="#217052" strokeWidth="3" fill="none" strokeLinecap="round" /><path d="M275 40C305 25 321 48 345 30S397 28 430 12" stroke="#75ac8f" strokeWidth="3" strokeDasharray="6 6" fill="none" /><circle cx="275" cy="40" r="5" fill="#217052" stroke="white" strokeWidth="3" /></svg><div className="sp-chart-months"><span>Sales history</span><span>Planning ahead →</span></div></div>
            <div className="sp-preview-actions"><div className="sp-preview-section-title">Worth a look <span>Inventory</span></div><div><span className="sp-preview-product-icon"><Box size={16} /></span><p>Everyday essentials<small>Review reorder levels</small></p><span className="sp-stock-pill">Low stock</span></div><div><span className="sp-preview-product-icon"><Package size={16} /></span><p>Seasonal collection<small>Review slow-moving items</small></p><ArrowUpRight size={14} /></div><div className="sp-preview-success"><Check size={15} /> Turn stock data into your next decision.</div></div>
          </div>
        </div>
      </div>
    </div>
  </figure>;
}
