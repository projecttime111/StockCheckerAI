import type { Metadata } from "next";
import { ServiceStatusIndicator } from "@/components/ServiceStatusIndicator";
import { ModeBanner } from "@/components/ModeBanner";
import { Providers } from "@/lib/query";
import { ClerkProvider } from "@clerk/nextjs";
import { IdentityProvider } from "@/components/IdentityProvider";
import { WorkspaceBoundary } from "@/components/WorkspaceBoundary";
import { CLERK_ENABLED } from "@/lib/api";
import "./globals.css";

export const metadata: Metadata = {
  title: "StockPilot AI",
  description: "AI-powered inventory, forecasting, and business intelligence for growing retailers and distributors."
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  if (CLERK_ENABLED && !process.env.NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY) throw new Error("NEXT_PUBLIC_CLERK_PUBLISHABLE_KEY is required when Clerk is enabled");
  const content = <Providers><IdentityProvider><WorkspaceBoundary>{children}</WorkspaceBoundary></IdentityProvider></Providers>;
  return (
    <html lang="en">
      <body>
        <ServiceStatusIndicator />
        <ModeBanner />
        {CLERK_ENABLED ? <ClerkProvider signInUrl="/sign-in" signUpUrl="/sign-up" signInFallbackRedirectUrl="/auth/complete" signUpFallbackRedirectUrl="/auth/complete">{content}</ClerkProvider> : content}
      </body>
    </html>
  );
}
