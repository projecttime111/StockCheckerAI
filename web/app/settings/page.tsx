"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, RotateCcw, Trash2, UserX, X } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { useRouter } from "next/navigation";
import { FormEvent, useEffect, useState } from "react";
import { AppShell } from "@/components/AppShell";
import { BusinessMode, BusinessModeSelector } from "@/components/BusinessModeSelector";
import { Field, PrimaryButton, SecondaryButton, TextInput } from "@/components/FormControls";
import { MutationFeedback, QueryFeedback } from "@/components/QueryFeedback";
import { Panel } from "@/components/Panel";
import { useSession } from "@/lib/session";
import { api, clearSession, CLERK_ENABLED, signOutSession, clearClerkOrganization, setSession, Warehouse } from "@/lib/api";

type Tenant = { id: string; name: string; businessMode: string; currency: string; gstEnabled: boolean; allowNegativeStock: boolean; portalShowAllActiveProducts: boolean };
const modeLabels: Record<string, string> = { RETAIL: "Retail", WHOLESALE: "Wholesale", HYBRID: "Hybrid" };
type DeleteImpact = {
  products: number;
  customers: number;
  suppliers: number;
  warehouses: number;
  stockMovements: number;
  salesInvoices: number;
  purchaseInvoices: number;
  importBatches: number;
  importFiles: number;
  forecastResults: number;
  deadStockInsights: number;
  auditLogs: number;
  uploadedFiles: number;
};
type LifecycleAction = "reset" | "workspace" | "account";
type WorkspaceDeletion = { accessToken: string; role: string | null; onboardingRequired: boolean };

export default function SettingsPage() {
  const router = useRouter();
  const queryClient = useQueryClient();
  const tenant = useQuery({ queryKey: ["tenant"], queryFn: () => api<Tenant>("/api/tenants/current") });
  const warehouses = useQuery({ queryKey: ["warehouses"], queryFn: () => api<Warehouse[]>("/api/warehouses") });
  const role = useSession().data?.role ?? null;
  const deleteImpact = useQuery({
    queryKey: ["delete-impact"],
    queryFn: () => api<DeleteImpact>("/api/tenants/current/delete-impact"),
    enabled: role === "OWNER" || role === "ADMIN"
  });
  const [business, setBusiness] = useState({ name: "", currency: "INR", gstEnabled: true, allowNegativeStock: false, portalShowAllActiveProducts: false, businessMode: "HYBRID" });
  const [warehouse, setWarehouse] = useState({ name: "", code: "", address: "" });
  const [activeAction, setActiveAction] = useState<LifecycleAction | null>(null);
  const [confirmation, setConfirmation] = useState("");
  useEffect(() => {
    if (tenant.data) {
      setBusiness({
        name: tenant.data.name,
        currency: tenant.data.currency,
        gstEnabled: tenant.data.gstEnabled,
        allowNegativeStock: tenant.data.allowNegativeStock,
        portalShowAllActiveProducts: tenant.data.portalShowAllActiveProducts,
        businessMode: tenant.data.businessMode
      });
    }
  }, [tenant.data]);
  const saveBusiness = useMutation({
    mutationFn: async () => {
      await api("/api/tenants/current", { method: "PUT", body: JSON.stringify({
        name: business.name,
        currency: business.currency,
        gstEnabled: business.gstEnabled,
        allowNegativeStock: business.allowNegativeStock,
        portalShowAllActiveProducts: business.portalShowAllActiveProducts
      }) });
      await api("/api/tenants/current/business-mode", { method: "POST", body: JSON.stringify({ businessMode: business.businessMode }) });
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["tenant"] })
  });
  const createWarehouse = useMutation({
    mutationFn: () => api("/api/warehouses", { method: "POST", body: JSON.stringify(warehouse) }),
    onSuccess: () => {
      setWarehouse({ name: "", code: "", address: "" });
      queryClient.invalidateQueries({ queryKey: ["warehouses"] });
    }
  });
  const lifecycleAction = useMutation({
    mutationFn: async () => {
      if (!activeAction) throw new Error("Select an action");
      const request = { body: JSON.stringify({ confirmation }) };
      if (activeAction === "reset") {
        return api("/api/tenants/current/reset-business-data", { ...request, method: "POST" });
      }
      if (activeAction === "workspace") {
        return api<WorkspaceDeletion>("/api/tenants/current", { ...request, method: "DELETE" });
      }
      return api("/api/account", { ...request, method: "DELETE" });
    },
    onSuccess: async (result) => {
      const completedAction = activeAction;
      setActiveAction(null);
      setConfirmation("");
      queryClient.clear();
      if (completedAction === "reset") {
        router.push("/dashboard");
        return;
      }
      if (completedAction === "workspace") {
        if (CLERK_ENABLED) { await clearClerkOrganization(); window.location.assign("/workspace/select"); return; }
        const workspace = result as WorkspaceDeletion;
        clearSession();
        setSession(workspace.accessToken, workspace.role ?? undefined, []);
        router.push(workspace.onboardingRequired ? "/onboarding" : "/dashboard");
        return;
      }
      void signOutSession();
    }
  });

  const actionConfig = activeAction ? lifecycleActions[activeAction] : null;

  return (
    <AppShell title="Settings">
      <QueryFeedback queries={[tenant, warehouses]} />
      <div className="grid gap-4 xl:grid-cols-2">
        <Panel title="Business Info">
          <form onSubmit={(event: FormEvent) => { event.preventDefault(); saveBusiness.mutate(); }} className="space-y-3">
            <Field label="Business name"><TextInput value={business.name} onChange={(e) => setBusiness({ ...business, name: e.target.value })} /></Field>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="Currency"><TextInput required pattern="[A-Z]{3}" maxLength={3} title="Three-letter currency code, for example INR or USD" value={business.currency} onChange={(e) => setBusiness({ ...business, currency: e.target.value.toUpperCase() })} /></Field>
              <div className="rounded border border-moss/20 bg-mint/60 p-3 text-sm">
                <div className="text-xs font-semibold uppercase tracking-normal text-ink/50">Current tenant mode</div>
                <div className="mt-1 text-base font-semibold text-moss">{business.businessMode} - {modeLabels[business.businessMode] ?? business.businessMode}</div>
              </div>
            </div>
            <div>
              <div className="mb-2 text-sm font-medium text-ink/70">Choose tenant mode</div>
              <BusinessModeSelector value={business.businessMode} compact onChange={(value: BusinessMode) => setBusiness({ ...business, businessMode: value })} />
            </div>
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={business.gstEnabled} onChange={(e) => setBusiness({ ...business, gstEnabled: e.target.checked })} /> GST enabled</label>
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={business.allowNegativeStock} onChange={(e) => setBusiness({ ...business, allowNegativeStock: e.target.checked })} /> Allow negative stock movements</label>
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={business.portalShowAllActiveProducts} onChange={(e) => setBusiness({ ...business, portalShowAllActiveProducts: e.target.checked })} /> Show all active products in dealer portal when no price list is assigned</label>
            <MutationFeedback mutation={saveBusiness} success="Settings saved." />

            <PrimaryButton disabled={saveBusiness.isPending}>Save settings</PrimaryButton>
          </form>
        </Panel>
        <Panel title="Warehouses">
          <div className="mb-4 space-y-2">
            {(warehouses.data ?? []).map((item) => <div key={item.id} className="rounded border border-ink/10 p-3 text-sm"><div className="font-medium">{item.name}</div><div className="text-ink/50">{item.code}</div></div>)}
          </div>
          <MutationFeedback mutation={createWarehouse} success="Warehouse added." />
          <form onSubmit={(event: FormEvent) => { event.preventDefault(); createWarehouse.mutate(); }} className="grid gap-3 sm:grid-cols-3">
            <Field label="Name"><TextInput value={warehouse.name} onChange={(e) => setWarehouse({ ...warehouse, name: e.target.value })} required /></Field>
            <Field label="Code"><TextInput value={warehouse.code} onChange={(e) => setWarehouse({ ...warehouse, code: e.target.value })} /></Field>
            <Field label="Address"><TextInput value={warehouse.address} onChange={(e) => setWarehouse({ ...warehouse, address: e.target.value })} /></Field>
            <PrimaryButton className="sm:col-span-3" disabled={createWarehouse.isPending}>Add warehouse</PrimaryButton>
          </form>
        </Panel>
        <Panel title="Users and Roles">
          <p className="text-sm text-ink/60">Role-based access is enforced by the backend for OWNER, ADMIN, MANAGER, STAFF, and VIEWER. User invitation management is reserved for the next production hardening pass.</p>
        </Panel>
        <Panel title="Import Templates">
          <p className="text-sm text-ink/60">Saved mapping templates are available through the import API and used by the import workflow for repeat Tally, CSV, Excel, JSON, and XML uploads.</p>
        </Panel>
        {role === "OWNER" || role === "ADMIN" ? (
          <section className="border border-coral/35 bg-white shadow-sm xl:col-span-2">
            <div className="flex items-center gap-3 border-b border-coral/20 px-4 py-4">
              <AlertTriangle className="text-coral" size={20} />
              <div>
                <h2 className="font-semibold text-ink">Danger Zone</h2>
                <p className="text-sm text-ink/55">Destructive workspace and account controls. Review the impact and type the confirmation phrase before continuing.</p>
              </div>
            </div>
            <div className="divide-y divide-ink/10">
              {role === "OWNER" ? (
                <DangerAction icon={RotateCcw} title="Reset workspace data" description="Delete all products, stock, imports, sales, purchases, customers, suppliers, forecasts, and reports. Your account and workspace remain." button="Reset workspace data" onClick={() => openAction("reset", setActiveAction, setConfirmation)} />
              ) : null}
              {role === "OWNER" ? (
                <DangerAction icon={Trash2} title="Delete workspace" description="Delete this workspace and all its business data. You will be moved to another workspace or a clean onboarding workspace." button="Delete workspace" onClick={() => openAction("workspace", setActiveAction, setConfirmation)} />
              ) : null}
              <DangerAction icon={UserX} title="Delete my account" description="Deactivate and anonymize your account. Owned workspaces must be deleted or transferred first." button="Delete my account" onClick={() => openAction("account", setActiveAction, setConfirmation)} />
            </div>
          </section>
        ) : null}
      </div>
      {actionConfig ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-ink/45 p-4" role="dialog" aria-modal="true" aria-labelledby="danger-dialog-title">
          <div className="max-h-[90vh] w-full max-w-xl overflow-y-auto rounded bg-white shadow-xl">
            <div className="flex items-start justify-between gap-4 border-b border-ink/10 p-4">
              <div>
                <h2 id="danger-dialog-title" className="text-lg font-semibold">{actionConfig.title}</h2>
                <p className="mt-1 text-sm text-ink/60">{actionConfig.warning}</p>
              </div>
              <button className="focus-ring p-2 text-ink/55" aria-label="Close confirmation" onClick={() => setActiveAction(null)}><X size={18} /></button>
            </div>
            <div className="space-y-4 p-4">
              <div>
                <div className="mb-2 text-sm font-semibold">Current workspace impact</div>
                {deleteImpact.isLoading ? <p className="text-sm text-ink/55">Loading counts...</p> : (
                  <div className="grid grid-cols-2 gap-x-5 gap-y-2 border-y border-ink/10 py-3 text-sm sm:grid-cols-3">
                    {Object.entries(deleteImpact.data ?? {}).map(([key, value]) => (
                      <div key={key} className="flex justify-between gap-2"><span className="text-ink/55">{impactLabels[key] ?? key}</span><strong>{value}</strong></div>
                    ))}
                  </div>
                )}
              </div>
              <div className="border-l-4 border-coral bg-coral/5 px-3 py-2 text-sm text-ink/70">Reset workspace data is destructive. Use only after backup or export.</div>
              <Field label={`Type ${actionConfig.phrase} to confirm`}>
                <TextInput value={confirmation} autoComplete="off" onChange={(event) => setConfirmation(event.target.value)} />
              </Field>
              {lifecycleAction.error ? <p className="text-sm text-coral">{lifecycleAction.error.message}</p> : null}
              <div className="flex justify-end gap-2">
                <SecondaryButton onClick={() => setActiveAction(null)}>Cancel</SecondaryButton>
                <button className="focus-ring inline-flex h-10 items-center justify-center rounded bg-coral px-4 text-sm font-semibold text-white disabled:cursor-not-allowed disabled:opacity-50" disabled={confirmation !== actionConfig.phrase || lifecycleAction.isPending} onClick={() => lifecycleAction.mutate()}>
                  {lifecycleAction.isPending ? "Processing..." : actionConfig.button}
                </button>
              </div>
            </div>
          </div>
        </div>
      ) : null}
    </AppShell>
  );
}

const lifecycleActions = {
  reset: { title: "Reset workspace data", phrase: "RESET WORKSPACE DATA", button: "Reset workspace data", warning: "All business data in this workspace will be permanently deleted. Your account, workspace, and membership remain." },
  workspace: { title: "Delete workspace", phrase: "DELETE WORKSPACE", button: "Delete workspace", warning: "This workspace and all of its business data will be deleted. This cannot be undone." },
  account: { title: "Delete my account", phrase: "DELETE MY ACCOUNT", button: "Delete my account", warning: "Your login will be deactivated and anonymized. This is blocked while you own an active workspace." }
} as const;

const impactLabels: Record<string, string> = {
  products: "Products", customers: "Customers", suppliers: "Suppliers", warehouses: "Warehouses",
  stockMovements: "Stock movements", salesInvoices: "Sales invoices", purchaseInvoices: "Purchase invoices",
  importBatches: "Import batches", importFiles: "Import files", forecastResults: "Forecasts",
  deadStockInsights: "Dead stock", auditLogs: "Audit logs", uploadedFiles: "Uploaded files"
};

function DangerAction({ icon: Icon, title, description, button, onClick }: { icon: LucideIcon; title: string; description: string; button: string; onClick: () => void }) {
  return (
    <div className="flex flex-col gap-4 px-4 py-4 sm:flex-row sm:items-center sm:justify-between">
      <div className="flex gap-3">
        <Icon className="mt-0.5 shrink-0 text-coral" size={19} />
        <div><h3 className="font-medium text-ink">{title}</h3><p className="mt-1 max-w-3xl text-sm text-ink/55">{description}</p></div>
      </div>
      <button className="focus-ring h-10 shrink-0 rounded border border-coral/60 px-4 text-sm font-semibold text-coral hover:bg-coral/5" onClick={onClick}>{button}</button>
    </div>
  );
}

function openAction(action: LifecycleAction, setAction: (action: LifecycleAction) => void, setConfirmation: (value: string) => void) {
  setConfirmation("");
  setAction(action);
}
