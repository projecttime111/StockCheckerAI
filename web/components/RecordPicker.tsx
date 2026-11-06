"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { api, Page } from "@/lib/api";
import { Pagination } from "./Pagination";
import { QueryFeedback } from "./QueryFeedback";

export function RecordPicker<T extends { id: string; name: string }>({ path, label, value, onChange, optionalLabel }: { path: string; label: string; value: string; onChange: (row: T | null) => void; optionalLabel?: string }) {
  const [search, setSearch] = useState("");
  const [page, setPage] = useState(0);
  const [selectedName, setSelectedName] = useState("");
  const query = useQuery({ queryKey: [path.replace("/api/", ""), "picker", search, page], queryFn: () => api<Page<T>>(`${path}?size=25&page=${page}&query=${encodeURIComponent(search)}`) });
  return <fieldset className="rounded border border-ink/15 p-3"><legend className="px-1 text-sm font-medium">{label}</legend><input aria-label={`Search ${label.toLowerCase()}`} className="w-full rounded border border-ink/15 p-2 text-sm" value={search} placeholder={`Search ${label.toLowerCase()}`} onChange={event => { setSearch(event.target.value); setPage(0); }} /><QueryFeedback queries={[query]} /><select aria-label={label} className="mt-2 w-full rounded border border-ink/15 p-2 text-sm" value={value} required={!optionalLabel} onChange={event => { const row = query.data?.content.find(row => row.id === event.target.value) ?? null; setSelectedName(row?.name ?? ""); onChange(row); }}><option value="">{optionalLabel ?? `Select ${label.toLowerCase()}`}</option>{value && !query.data?.content.some(row => row.id === value) ? <option value={value}>{selectedName || "Selected record"}</option> : null}{(query.data?.content ?? []).map(row => <option key={row.id} value={row.id}>{row.name}</option>)}</select><Pagination data={query.data} page={page} onChange={setPage} /></fieldset>;
}
