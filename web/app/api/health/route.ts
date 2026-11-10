// Independent of Clerk, backend and database: this observes the deployed web runtime.
export const dynamic = "force-dynamic";
export function GET() {
  return Response.json({ status: "UP", service: "stockpilot-web", checkedAt: new Date().toISOString() }, {
    headers: { "Cache-Control": "no-store, max-age=0", "CDN-Cache-Control": "no-store", "Vercel-CDN-Cache-Control": "no-store" }
  });
}
