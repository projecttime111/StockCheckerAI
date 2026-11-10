import { Evidence } from "@/lib/assistant";

function label(value: string) { return value.replace(/([a-z])([A-Z])/g, "$1 $2").replaceAll("_", " "); }
function display(value: unknown): string { return value == null ? "Not available" : typeof value === "object" ? JSON.stringify(value) : String(value); }
export function AssistantEvidence({ evidence }: { evidence?: Record<string, Evidence> }) {
  if (!evidence || !Object.keys(evidence).length) return null;
  return <div className="mt-4 space-y-3" aria-label="Answer evidence">{Object.entries(evidence).map(([id, source]) => {
    if (!source || typeof source !== "object") return null;
    const data = source.data;
    const rows = Array.isArray(data) ? data : [];
    const columns = [...new Set(rows.flatMap(row => row && typeof row === "object" && !Array.isArray(row) ? Object.keys(row) : []))];
    return <details key={id} className="rounded-lg border border-moss/15 bg-mint/20 p-3"><summary className="cursor-pointer text-sm font-semibold text-moss">{source.title || label(source.tool || id)}</summary><div className="mt-3 space-y-3 text-xs">
      <p className="text-ink/60">{source.period ? `Period: ${source.period.from || "Unknown start"} to ${source.period.through || "Unknown end"}${source.period.timeZone ? ` (${source.period.timeZone})` : ""}. ` : ""}{source.period?.previousFrom ? `Comparison period: ${source.period.previousFrom} to ${source.period.previousThrough || "Unknown end"}. ` : ""}{source.asOf ? `Data as of ${source.asOf}.` : ""}</p>
      {rows.length && columns.length ? <div className="overflow-x-auto"><table className="w-full text-left"><thead><tr>{columns.map(column => <th className="p-2 font-semibold" key={column}>{label(column)}</th>)}</tr></thead><tbody>{rows.slice(0, 20).map((row, index) => <tr className="border-t border-ink/10" key={index}>{columns.map(column => <td className="p-2" key={column}>{display(row[column])}</td>)}</tr>)}</tbody></table>{rows.length > 20 ? <p className="mt-2 text-ink/60">Showing 20 of {rows.length} evidence rows. Expand the complete source below to inspect the rest.</p> : null}</div> : data && typeof data === "object" && !Array.isArray(data) ? <dl className="grid gap-2 sm:grid-cols-2">{Object.entries(data).map(([key, value]) => <div key={key} className="min-w-0 rounded bg-white/70 p-2"><dt className="text-ink/55">{label(key)}</dt><dd className="mt-1 break-words font-medium">{display(value)}</dd></div>)}</dl> : <p>{Array.isArray(data) && !data.length ? "No matching records in this period." : display(data)}</p>}
      {source.limitations?.length ? <ul className="list-disc space-y-1 pl-4 text-amber-900">{source.limitations.map((warning, index) => <li key={index}>{warning}</li>)}</ul> : null}
      {rows.length > 20 ? <details><summary className="cursor-pointer text-moss">Complete source data</summary><pre className="mt-2 max-h-80 overflow-auto whitespace-pre-wrap break-words">{JSON.stringify(data, null, 2)}</pre></details> : null}
      <p className="break-all text-[10px] text-ink/45">Source: {source.tool || id} · Evidence {source.id || id}</p>
    </div></details>;
  })}</div>;
}
