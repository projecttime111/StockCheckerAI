"use client";

import { useState } from "react";
import { Pagination } from "@/components/Pagination";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { AppShell } from "@/components/AppShell";
import { ErrorState } from "@/components/ErrorState";
import { LoadingState } from "@/components/LoadingState";
import { Panel } from "@/components/Panel";
import { api, Page } from "@/lib/api";

type AuditLog = {
  id: string;
  actorUserId?: string;
  action: string;
  entityType: string;
  entityId?: string;
  details: Record<string, unknown>;
  createdAt: string;
};

export default function AuditLogsPage() {
  const [page, setPage] = useState(0);
  const logs = useQuery({ queryKey: ["audit-logs", page], queryFn: () => api<Page<AuditLog>>(`/api/audit-logs?size=25&page=${page}&sort=createdAt,desc`) });

  return (
    <AppShell title="Audit Logs">
      <Panel
        title="Important Activity"
        action={<Link className="rounded border border-ink/15 px-3 py-2 text-sm font-semibold text-ink/70" href="/reports">Export logs</Link>}
      >
        {logs.isLoading ? <LoadingState /> : logs.error ? <ErrorState error={logs.error} /> : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-ink/50">
                <tr>
                  <th className="py-2">Time</th>
                  <th>Action</th>
                  <th>Entity</th>
                  <th>Actor</th>
                  <th>Details</th>
                </tr>
              </thead>
              <tbody>
                {(logs.data?.content ?? []).map((log) => (
                  <tr key={log.id} className="border-t border-ink/10 align-top">
                    <td className="min-w-40 py-3 text-ink/60">{formatDate(log.createdAt)}</td>
                    <td className="font-medium">{humanize(log.action)}</td>
                    <td>{log.entityType}<div className="text-xs text-ink/45">{log.entityId ?? "-"}</div></td>
                    <td className="text-xs text-ink/55">{log.actorUserId ?? "system"}</td>
                    <td className="max-w-xl text-xs text-ink/60">{formatDetails(log.details)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!(logs.data?.content ?? []).length ? <p className="py-8 text-center text-sm text-ink/55">No audit logs yet. Stock adjustments, imports, payment reminders, and invoice actions will appear here.</p> : null}
          </div>
        )}
        <Pagination data={logs.data} page={page} onChange={setPage} />
      </Panel>
    </AppShell>
  );
}

function humanize(value: string) {
  return value.replaceAll("_", " ").toLowerCase().replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function formatDate(value: string) {
  if (!value) return "-";
  return new Intl.DateTimeFormat("en-IN", { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

function formatDetails(details: Record<string, unknown>) {
  const entries = Object.entries(details ?? {});
  if (!entries.length) return "-";
  return entries.map(([key, value]) => `${key}: ${String(value)}`).join(" | ");
}
