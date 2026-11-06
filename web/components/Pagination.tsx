import { Page } from "@/lib/api";

export function Pagination({ data, page, onChange }: { data?: Page<unknown>; page: number; onChange: (page: number) => void }) {
  return <nav aria-label="Pagination" className="mt-4 flex flex-wrap items-center justify-between gap-3 text-sm"><span>{data?.totalElements ?? 0} records · Page {page + 1} of {Math.max(1, data?.totalPages ?? 1)}</span><div className="flex gap-3"><button type="button" className="rounded border px-3 py-2 disabled:opacity-40" disabled={page === 0} onClick={() => onChange(page - 1)}>Previous</button><button type="button" className="rounded border px-3 py-2 disabled:opacity-40" disabled={!data || page + 1 >= data.totalPages} onClick={() => onChange(page + 1)}>Next</button></div></nav>;
}
