"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ShoppingCart, Wallet } from "lucide-react";
import { useMemo, useState } from "react";
import { AppShell } from "@/components/AppShell";
import { PrimaryButton } from "@/components/FormControls";
import { MutationFeedback, QueryFeedback } from "@/components/QueryFeedback";
import { Panel } from "@/components/Panel";
import { StatCard } from "@/components/StatCard";
import { api } from "@/lib/api";
import { Permission, useMoney } from "@/lib/business";

type Product = { productId: string; productName: string; price: number; availableStock: number };
type Order = { orderId: string; orderNumber: string; status: string; totalAmount: number };
type Outstanding = { customerName: string; outstanding: number };
type Invoice = { invoiceId: string; invoiceNumber: string; invoiceDate: string; totalAmount: number };
type Me = { role: string };

export default function PortalPage() {
  const money = useMoney();
  const queryClient = useQueryClient();
  const [query, setQuery] = useState("");
  const [cart, setCart] = useState<Record<string, number>>({});
  const me = useQuery({ queryKey: ["me"], queryFn: () => api<Me>("/api/auth/me") });
  const isCustomerUser = me.data?.role === "CUSTOMER_USER";
  const products = useQuery({ queryKey: ["portal", "products"], enabled: isCustomerUser, queryFn: () => api<Product[]>("/api/portal/products") });
  const orders = useQuery({ queryKey: ["portal", "orders"], enabled: isCustomerUser, queryFn: () => api<Order[]>("/api/portal/orders") });
  const outstanding = useQuery({ queryKey: ["portal", "outstanding"], enabled: isCustomerUser, queryFn: () => api<Outstanding>("/api/portal/outstanding") });
  const invoices = useQuery({ queryKey: ["portal", "invoices"], enabled: isCustomerUser, queryFn: () => api<Invoice[]>("/api/portal/invoices") });
  const place = useMutation({
    mutationFn: () => api<Order>("/api/portal/orders", { method: "POST", body: JSON.stringify({ items: Object.entries(cart).filter(([, qty]) => qty > 0).map(([productId, quantity]) => ({ productId, quantity })) }) }),
    onSuccess: () => {
      setCart({});
      queryClient.invalidateQueries({ queryKey: ["portal", "orders"] });
    }
  });
  const filtered = useMemo(() => (products.data ?? []).filter((product) => product.productName.toLowerCase().includes(query.toLowerCase())), [products.data, query]);
  const cartTotal = Object.entries(cart).reduce((sum, [id, qty]) => {
    const product = products.data?.find((row) => row.productId === id);
    return sum + Number(product?.price ?? 0) * qty;
  }, 0);

  function add(productId: string) {
    setCart((current) => ({ ...current, [productId]: (current[productId] ?? 0) + 1 }));
  }

  return (
    <AppShell title="Dealer Portal">
      <QueryFeedback queries={[me, products, orders, outstanding, invoices]} />
      <MutationFeedback mutation={place} success="Order placed successfully." />
      <div className="space-y-6">
        <Panel title="Customer Ordering">
          <p className="text-sm text-ink/65">Wholesale customers can view assigned prices, available stock, place sales orders, reorder previous items, and track outstanding without touching accounting records.</p>
        </Panel>
        {me.data && !isCustomerUser ? (
          <Panel title="Portal Preview">
            <p className="text-sm text-ink/65">This page is for dealer/customer logins linked to a customer account. Sign in with your assigned dealer account.</p>
          </Panel>
        ) : null}
        {isCustomerUser ? (
          <>
        <div className="grid gap-4 md:grid-cols-3">
          <StatCard label="Outstanding" value={money(outstanding.data?.outstanding)} icon={<Wallet size={18} />} />
          <StatCard label="Open orders" value={orders.data?.filter((order) => order.status === "OPEN").length ?? 0} icon={<ShoppingCart size={18} />} />
          <StatCard label="Cart value" value={money(cartTotal)} icon={<ShoppingCart size={18} />} />
        </div>
        <div className="grid gap-4 xl:grid-cols-[1fr_360px]">
          <Panel title="Available Products" action={<input className="h-10 rounded border border-ink/15 px-3 text-sm" placeholder="Search products" value={query} onChange={(event) => setQuery(event.target.value)} />}>
            <div className="grid gap-3 md:grid-cols-2">
              {filtered.map((product) => (
                <div key={product.productId} className="rounded border border-ink/10 p-3">
                  <div className="font-semibold">{product.productName}</div>
                  <div className="text-sm text-ink/55">Stock {product.availableStock} - {money(product.price)}</div>
                  <Permission permission="portal.customer.order_create"><button disabled={place.isPending} className="mt-3 rounded bg-moss px-3 py-2 text-sm font-semibold text-white" onClick={() => add(product.productId)}>Add</button></Permission>
                </div>
              ))}
              {!products.isLoading && !products.error && filtered.length === 0 ? (
                <div className="rounded border border-ink/10 bg-paper p-4 text-sm text-ink/60 md:col-span-2">
                  No products have been assigned to your catalog yet.
                </div>
              ) : null}
            </div>
          </Panel>
          <Panel title="Cart and Orders" action={<Permission permission="portal.customer.order_create"><PrimaryButton onClick={() => place.mutate()} disabled={place.isPending || !Object.values(cart).some((qty) => qty > 0)}>Place order</PrimaryButton></Permission>}>
            <div className="space-y-2">
              {Object.keys(cart).length ? <button disabled={place.isPending} className="text-sm underline" onClick={() => setCart({})}>Clear cart</button> : null}
              {Object.entries(cart).map(([id, qty]) => {
                const product = products.data?.find((row) => row.productId === id);
                return <div key={id} className="flex justify-between rounded border border-ink/10 p-2 text-sm"><span>{product?.productName}</span><div className="flex gap-2"><input aria-label={`Quantity for ${product?.productName}`} className="w-20 rounded border p-1" type="number" min="1" step="1" value={qty} disabled={place.isPending} onChange={event => { const quantity = Number(event.target.value); if (Number.isInteger(quantity) && quantity > 0) setCart(current => ({ ...current, [id]: quantity })); }} /><button disabled={place.isPending} type="button" aria-label={`Remove ${product?.productName}`} onClick={() => setCart(current => { const next = { ...current }; delete next[id]; return next; })}>Remove</button></div></div>;
              })}
              <div className="pt-4">
                <div className="font-semibold">Recent orders</div>
                {(orders.data ?? []).slice(0, 5).map((order) => <div key={order.orderId} className="mt-2 rounded border border-ink/10 p-2 text-sm">{order.orderNumber} - {order.status} - {money(order.totalAmount)}</div>)}
              </div>
              <div className="pt-4">
                <div className="font-semibold">Invoices</div>
                {(invoices.data ?? []).slice(0, 5).map((invoice) => <div key={invoice.invoiceId} className="mt-2 rounded border border-ink/10 p-2 text-sm">{invoice.invoiceNumber} - {invoice.invoiceDate} - {money(invoice.totalAmount)}</div>)}
              </div>
            </div>
          </Panel>
        </div>
          </>
        ) : null}
      </div>
    </AppShell>
  );
}
