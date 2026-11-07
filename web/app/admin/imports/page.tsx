"use client";

import { useQuery } from "@tanstack/react-query";
import { FormEvent, useState } from "react";
import { Field, PrimaryButton, SecondaryButton, SelectInput, TextInput } from "@/components/FormControls";
import { Panel } from "@/components/Panel";
import { QueryFeedback } from "@/components/QueryFeedback";
import { ImportTable } from "@/components/platform/ImportTable";
import { PlatformHeading, PlatformPager } from "@/components/platform/PlatformUI";
import { platformApi } from "@/lib/platform";

const initial = { search: "", status: "", source: "", failedOnly: false };
const statuses = ["UPLOADED", "PARSED", "MAPPED", "VALIDATED", "VALIDATION_FAILED", "CREATED", "FILES_UPLOADED", "CLASSIFIED", "STAGED", "PLAN_READY", "NEEDS_REVIEW", "COMMITTING", "COMMITTED", "ROLLED_BACK", "FAILED", "CANCELLED"];
const sources = ["TALLY", "EXCEL", "CSV", "JSON", "XML", "OTHER_ERP", "TALLY_EXCEL", "TALLY_XML", "SMART_IMPORT"];

export default function PlatformImportsPage() {
  const [draft, setDraft] = useState(initial);
  const [filters, setFilters] = useState(initial);
  const [page, setPage] = useState(0);
  const imports = useQuery({ queryKey: ["platform", "imports", filters, page], queryFn: () => platformApi.imports({ ...filters, search: filters.search.trim(), failedOnly: filters.failedOnly || undefined, page }) });
  function apply(event: FormEvent) { event.preventDefault(); setPage(0); setFilters({ ...draft }); }
  return <><PlatformHeading eyebrow="Operations" title="Import monitoring" description="Review standard and smart imports across client workspaces. This console monitors recorded activity; corrections happen in the authorized business workflow." />
    <Panel title="Import activity"><form onSubmit={apply} className="mb-6 flex flex-wrap items-end gap-3"><div className="min-w-52 flex-1"><Field label="Search imports"><TextInput maxLength={200} value={draft.search} placeholder="Business or import name" onChange={event => setDraft({ ...draft, search: event.target.value })} /></Field></div><Field label="Import status"><SelectInput disabled={draft.failedOnly} value={draft.status} onChange={event => setDraft({ ...draft, status: event.target.value })}><option value="">All statuses</option>{statuses.map(value => <option key={value}>{value}</option>)}</SelectInput></Field><Field label="Source"><SelectInput value={draft.source} onChange={event => setDraft({ ...draft, source: event.target.value })}><option value="">All sources</option>{sources.map(value => <option key={value}>{value}</option>)}</SelectInput></Field><label className="flex h-10 items-center gap-2 text-sm"><input type="checkbox" checked={draft.failedOnly} onChange={event => setDraft({ ...draft, failedOnly: event.target.checked, status: event.target.checked ? "" : draft.status })} />Failed only</label><PrimaryButton type="submit">Apply filters</PrimaryButton><SecondaryButton type="button" onClick={() => { setDraft(initial); setFilters(initial); setPage(0); }}>Reset</SecondaryButton></form>
      <QueryFeedback queries={[imports]} />{imports.data && !imports.error ? <><ImportTable rows={imports.data.content} /><PlatformPager data={imports.data} page={page} onChange={setPage} busy={imports.isFetching} /></> : null}
    </Panel>
  </>;
}
