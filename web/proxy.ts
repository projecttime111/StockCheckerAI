import { clerkMiddleware, createRouteMatcher } from "@clerk/nextjs/server";
import { NextResponse, type NextFetchEvent, type NextRequest } from "next/server";

const protectedRoute = createRouteMatcher(["/admin(.*)", "/dashboard(.*)", "/products(.*)", "/stock(.*)", "/sales(.*)", "/purchases(.*)", "/customers(.*)", "/suppliers(.*)", "/imports(.*)", "/integrations(.*)", "/reports(.*)", "/settings(.*)", "/billing(.*)", "/workspace(.*)", "/account(.*)", "/auth/complete", "/forecasts(.*)", "/forecast(.*)", "/dead-stock(.*)", "/data-quality(.*)", "/assistant(.*)", "/ai-assistant(.*)", "/audit-logs(.*)", "/portal(.*)"]);
const withClerk = clerkMiddleware(async (auth, request) => {
  if (protectedRoute(request) || request.nextUrl.pathname === "/team") await auth.protect();
});
export default function proxy(request: NextRequest, event: NextFetchEvent) {
  // Availability must remain visible when Clerk or the API cannot be reached.
  if (request.nextUrl.pathname === "/api/health" || request.nextUrl.pathname === "/status") return NextResponse.next();
  if (process.env.NEXT_PUBLIC_CLERK_ENABLED !== "true" || process.env.NEXT_PUBLIC_DEMO_MODE === "true") return NextResponse.next();
  return withClerk(request, event);
}
export const config = { matcher: ["/((?!_next|[^?]*\\.(?:html?|css|js(?!on)|jpe?g|webp|png|gif|svg|ttf|woff2?|ico|csv|docx?|xlsx?|zip|webmanifest)).*)", "/(api|trpc)(.*)"] };
