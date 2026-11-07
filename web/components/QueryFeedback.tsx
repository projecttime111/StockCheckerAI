"use client";

import { ErrorState } from "./ErrorState";
import { LoadingState } from "./LoadingState";

type RequestState = { error?: unknown; isLoading?: boolean; isPending?: boolean; isPaused?: boolean; isSuccess?: boolean; refetch?: () => unknown };
export function QueryFeedback({ queries }: { queries: RequestState[] }) {
  const failures = queries.filter(query => query.error);
  if (failures.length) return <div role="alert" className="mb-4 space-y-2">{failures.map((query, index) => <div key={index}><ErrorState error={query.error} />{query.refetch ? <button type="button" className="mt-2 underline" onClick={() => void query.refetch?.()}>Try again</button> : null}</div>)}</div>;
  if (queries.some(query => query.isPaused)) return <p role="status" className="mb-4 rounded-lg bg-amber/10 p-3 text-sm">Waiting for a connection. This request will resume when you’re back online.</p>;
  if (queries.some(query => query.isLoading)) return <div className="mb-4"><LoadingState compact title="Loading data…" /></div>;
  return null;
}

export function MutationFeedback({ mutation, success = "Saved successfully." }: { mutation: RequestState; success?: string }) {
  return mutation.error ? <ErrorState error={mutation.error} /> : mutation.isPaused ? <p role="status" className="rounded-lg bg-amber/10 p-3 text-sm">Waiting for a connection. Keep this page open to finish your request.</p> : mutation.isPending ? <LoadingState compact title="Processing your request…" /> : mutation.isSuccess ? <p role="status" className="text-sm text-moss">{success}</p> : null;
}
