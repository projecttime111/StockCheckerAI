"use client";

import { ErrorState } from "./ErrorState";

type RequestState = { error?: unknown; isLoading?: boolean; isPending?: boolean; isSuccess?: boolean; refetch?: () => unknown };
export function QueryFeedback({ queries }: { queries: RequestState[] }) {
  const failures = queries.filter(query => query.error);
  if (failures.length) return <div role="alert" className="mb-4 space-y-2">{failures.map((query, index) => <div key={index}><ErrorState error={query.error} />{query.refetch ? <button type="button" className="mt-2 underline" onClick={() => void query.refetch?.()}>Try again</button> : null}</div>)}</div>;
  if (queries.some(query => query.isLoading)) return <p role="status" className="mb-4 text-sm text-ink/60">Loading data…</p>;
  return null;
}

export function MutationFeedback({ mutation, success = "Saved successfully." }: { mutation: RequestState; success?: string }) {
  return mutation.error ? <ErrorState error={mutation.error} /> : mutation.isPending ? <p role="status" className="text-sm text-ink/60">Saving…</p> : mutation.isSuccess ? <p role="status" className="text-sm text-moss">{success}</p> : null;
}
