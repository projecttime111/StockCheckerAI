"use client";

import { SignIn, SignUp } from "@clerk/nextjs";
import Link from "next/link";
import { useEffect, useState } from "react";
import { CLERK_ENABLED } from "@/lib/api";

export function ClerkEntry({ signup = false }: { signup?: boolean }) {
  const [destination, setDestination] = useState("/auth/complete");
  useEffect(() => {
    const next = new URLSearchParams(window.location.search).get("next");
    if (next?.startsWith("/") && !next.startsWith("//") && !next.includes("\\")) setDestination(`/auth/complete?next=${encodeURIComponent(next)}`);
  }, []);
  if (!CLERK_ENABLED) return <main className="p-10"><Link className="underline" href={signup ? "/register" : "/login"}>Continue to {signup ? "registration" : "sign in"}</Link></main>;
  return <main className="flex min-h-screen items-center justify-center bg-[#eef7f0] p-5">{signup
    ? <SignUp routing="path" path="/sign-up" signInUrl="/sign-in" forceRedirectUrl={destination} />
    : <SignIn routing="path" path="/sign-in" signUpUrl="/sign-up" forceRedirectUrl={destination} />}</main>;
}
