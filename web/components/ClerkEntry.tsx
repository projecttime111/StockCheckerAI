"use client";

import { SignIn, SignUp, useAuth } from "@clerk/nextjs";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, type ComponentProps } from "react";
import { ArrowRight, LoaderCircle, ShieldCheck } from "lucide-react";
import { CLERK_ENABLED, signOutSession } from "@/lib/api";
import { AuthShell } from "@/components/marketing/AuthShell";

const RECOVERY_DELAY_MS = 15_000;
const appearance: ComponentProps<typeof SignIn>["appearance"] = {
  variables: {
    colorPrimary: "#195840",
    colorBackground: "#ffffff",
    colorForeground: "#162c23",
    colorMutedForeground: "#67756c",
    colorInput: "#ffffff",
    colorInputForeground: "#162c23",
    colorDanger: "#b83e32",
    borderRadius: "0.75rem",
    fontFamily: "inherit",
    fontSize: "0.9375rem"
  },
  elements: {
    rootBox: "sp-clerk-root",
    cardBox: "sp-clerk-card-box",
    card: "sp-clerk-card",
    headerTitle: "sp-clerk-title",
    headerSubtitle: "sp-clerk-subtitle",
    socialButtonsBlockButton: "sp-clerk-social",
    formButtonPrimary: "sp-clerk-primary",
    formFieldInput: "sp-clerk-input",
    footerActionLink: "sp-clerk-link",
    footer: "sp-clerk-footer"
  }
};

export function ClerkEntry({ signup = false }: { signup?: boolean }) {
  if (!CLERK_ENABLED) return <AuthShell signup={signup}><div className="sp-auth-state"><span className="sp-auth-state-icon"><ShieldCheck size={25} /></span><h2>{signup ? "Your next chapter starts here." : "Welcome back."}</h2><p>{signup ? "Create your account and bring your business into focus." : "Sign in to pick up where you left off."}</p><Link className="sp-button sp-button-dark mt-6" href={signup ? "/register" : "/login"}>Continue to {signup ? "registration" : "sign in"}<ArrowRight size={17} /></Link></div></AuthShell>;
  return <EnabledClerkEntry signup={signup} />;
}

function EnabledClerkEntry({ signup }: { signup: boolean }) {
  // Pending sessions remain signed out here so Clerk can display organization/MFA tasks.
  const { isLoaded, isSignedIn, sessionId } = useAuth();
  const router = useRouter();
  const [destination, setDestination] = useState<string | null>(null);
  const [stalled, setStalled] = useState(false);
  const [navigationError, setNavigationError] = useState(false);
  const redirected = useRef<string | null>(null);
  const active = isLoaded && isSignedIn === true;

  useEffect(() => {
    setDestination(clerkEntryDestination(window.location.search, window.location.origin));
  }, []);

  useEffect(() => {
    if (!active || !destination) return;
    const attempt = `${sessionId}:${destination}`;
    if (redirected.current === attempt) return;
    redirected.current = attempt;
    try { router.replace(destination); } catch { setNavigationError(true); }
  }, [active, destination, router, sessionId]);

  useEffect(() => {
    if (!active) return;
    const timer = setTimeout(() => setStalled(true), RECOVERY_DELAY_MS);
    return () => clearTimeout(timer);
  }, [active]);

  return <AuthShell signup={signup}>
    {active ? <div className="sp-auth-state">
      <span className="sp-auth-state-icon"><LoaderCircle size={26} className="sp-loading-spinner" aria-hidden="true" /></span>
      <h2>You’re signed in</h2>
      <p role="status" aria-live="polite">Opening your workspace…</p>
      <div className="sp-loading-track" aria-hidden="true"><span /></div>
      {stalled || navigationError ? <EntryRecovery destination={destination ?? "/auth/complete"} /> : null}
    </div> : !isLoaded || !destination ? <EntryFallback signup={signup} /> : signup
      ? <SignUp routing="path" path="/sign-up" signInUrl="/sign-in" forceRedirectUrl={destination} appearance={appearance} fallback={<EntryFallback signup />} />
      : <SignIn routing="path" path="/sign-in" signUpUrl="/sign-up" forceRedirectUrl={destination} appearance={appearance} fallback={<EntryFallback />} />}
    {!active ? <p className="sp-auth-account-switch">{signup ? "Already have an account?" : "New to StockPilot?"} <Link href={signup ? "/sign-in" : "/sign-up"}>{signup ? "Sign in" : "Create an account"}<ArrowRight size={14} /></Link></p> : null}
  </AuthShell>;
}

function EntryFallback({ signup = false }: { signup?: boolean }) {
  const [slow, setSlow] = useState(false);
  useEffect(() => {
    const timer = setTimeout(() => setSlow(true), RECOVERY_DELAY_MS);
    return () => clearTimeout(timer);
  }, []);
  return <div className="sp-auth-state">
    <span className="sp-auth-state-icon"><LoaderCircle size={26} className="sp-loading-spinner" aria-hidden="true" /></span>
    <h2>{signup ? "Create your account" : "Sign in to StockPilot"}</h2>
    <p role="status" aria-live="polite">{slow ? "The secure sign-in form is taking longer than expected. Check your connection and reload to try again." : "Loading the secure sign-in form…"}</p>
    <div className="sp-auth-skeleton" aria-hidden="true"><span /><span /><span /></div>
    {slow ? <button type="button" className="focus-ring mt-4 rounded border border-ink/15 px-3 py-2 text-sm font-semibold text-moss" onClick={() => window.location.reload()}>Reload sign-in form</button> : null}
  </div>;
}

function EntryRecovery({ destination }: { destination: string }) {
  const [signingOut, setSigningOut] = useState(false);
  const [error, setError] = useState<string | null>(null);
  async function signOut() {
    setSigningOut(true);
    setError(null);
    try { await signOutSession(); } catch {
      setError("Could not sign out. Check your connection and try again.");
      setSigningOut(false);
    }
  }
  return <div className="mt-5 space-y-4 border-t border-ink/10 pt-4">
    <p className="text-sm leading-6 text-ink/65">Navigation is taking longer than expected. Continue directly or reload this page.</p>
    <div className="flex flex-wrap items-center gap-4 text-sm">
      <a className="focus-ring rounded font-semibold text-moss underline" href={destination}>Continue to workspace</a>
      <button type="button" className="focus-ring rounded text-moss underline" onClick={() => window.location.reload()}>Reload page</button>
      <button type="button" className="focus-ring rounded text-moss underline disabled:opacity-50" disabled={signingOut} onClick={() => void signOut()}>{signingOut ? "Signing out…" : "Sign out"}</button>
    </div>
    {error ? <p role="alert" className="text-sm text-coral">{error}</p> : null}
  </div>;
}

export function clerkEntryDestination(search: string, origin: string) {
  const params = new URLSearchParams(search);
  const next = params.get("next");
  const requested = next ?? params.get("redirect_url");
  if (!requested || requested.startsWith("//") || /[\\\u0000-\u001f]/.test(requested)) return "/auth/complete";
  // `next` is app-relative; Clerk's redirect_url may be an absolute same-origin URL.
  if (next !== null && !next.startsWith("/")) return "/auth/complete";
  try {
    let url = new URL(requested, origin);
    if (url.origin !== origin || url.username || url.password) return "/auth/complete";
    // Clerk can preserve our completion URL as redirect_url. Unwrap it once only;
    // the normal route checks below still reject another auth URL or external path.
    if (next === null && url.pathname === "/auth/complete") {
      const nested = url.searchParams.get("next");
      if (!nested?.startsWith("/") || nested.startsWith("//") || /[\\\u0000-\u001f]/.test(nested)) return "/auth/complete";
      url = new URL(nested, origin);
    }
    const path = decodeURIComponent(url.pathname);
    if (url.origin !== origin || url.username || url.password || path.startsWith("//") || path.includes("\\") || path === "/" || /^\/(login|register|sign-in|sign-up|auth|reset-password|forgot-password)(\/|$)/.test(path)) return "/auth/complete";
    return `/auth/complete?next=${encodeURIComponent(url.pathname + url.search + url.hash)}`;
  } catch {
    return "/auth/complete";
  }
}
