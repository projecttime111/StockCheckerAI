"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef, useState } from "react";
import { api, clearSession, DEMO_MODE, loginDestination, invitationDestination, setSession, setSessionPermissions } from "@/lib/api";

type AuthResponse = {
  accessToken: string;
  tenantId: string | null;
  role: string | null;
};

type PermissionResponse = {
  permissions: string[];
};

type GoogleCredentialResponse = {
  credential?: string;
};

type GoogleAccountsId = {
  initialize(options: { client_id: string; callback: (response: GoogleCredentialResponse) => void }): void;
  renderButton(parent: HTMLElement, options: { theme: string; size: string; width: number; text: string; shape: string }): void;
};

declare global {
  interface Window {
    google?: {
      accounts: {
        id: GoogleAccountsId;
      };
    };
  }
}

const googleClientId = process.env.NEXT_PUBLIC_GOOGLE_CLIENT_ID ?? "";

export function GoogleSignInButton({ label = "Continue with Google" }: { label?: string }) {
  const queryClient = useQueryClient();
  const buttonRef = useRef<HTMLDivElement>(null);
  const rendered = useRef(false);
  const [status, setStatus] = useState("");
  const googleLogin = useMutation({
    mutationFn: (idToken: string) => api<AuthResponse>("/api/auth/google", { method: "POST", body: JSON.stringify({ idToken }) }),
    onSuccess: async (data) => {
      clearSession();
      queryClient.clear();
      setSession(data.accessToken, data.role ?? undefined);
      const invitation = invitationDestination();
      if (invitation) { window.location.assign(invitation); return; }
      if (data.role?.startsWith("PLATFORM_")) { window.location.assign(loginDestination(data.role)); return; }
      if (!data.tenantId || !data.role) {
        window.location.assign("/onboarding");
        return;
      }
      try {
        const access = await api<PermissionResponse>("/api/auth/me/permissions");
        setSessionPermissions(access.permissions ?? []);
      } catch {
        setSessionPermissions([]);
      }
      window.location.assign(loginDestination(data.role));
    },
    onError: (error) => {
      setStatus(error instanceof Error ? error.message : "Google sign-in failed.");
    }
  });

  useEffect(() => {
    if (!googleClientId || DEMO_MODE || rendered.current) {
      return;
    }
    const renderGoogleButton = () => {
      if (!buttonRef.current || !window.google || rendered.current) {
        return;
      }
      window.google.accounts.id.initialize({
        client_id: googleClientId,
        callback: (response) => {
          if (!response.credential) {
            setStatus("Google did not return a sign-in token.");
            return;
          }
          setStatus("");
          googleLogin.mutate(response.credential);
        }
      });
      window.google.accounts.id.renderButton(buttonRef.current, {
        theme: "outline",
        size: "large",
        width: 320,
        text: "continue_with",
        shape: "rectangular"
      });
      rendered.current = true;
    };

    if (window.google) {
      renderGoogleButton();
      return;
    }

    const existingScript = document.querySelector<HTMLScriptElement>("script[data-google-identity]");
    if (existingScript) {
      existingScript.addEventListener("load", renderGoogleButton, { once: true });
      return;
    }

    const script = document.createElement("script");
    script.src = "https://accounts.google.com/gsi/client";
    script.async = true;
    script.defer = true;
    script.dataset.googleIdentity = "true";
    script.addEventListener("load", renderGoogleButton, { once: true });
    script.addEventListener("error", () => setStatus("Google sign-in could not load. Check browser/network restrictions."), { once: true });
    document.head.appendChild(script);
  }, [googleLogin]);

  if (DEMO_MODE) {
    return (
      <div className="rounded border border-ink/10 bg-ink/[0.02] p-3 text-sm text-ink/60">
        Google sign-in is disabled in browser demo mode.
      </div>
    );
  }

  if (!googleClientId) {
    return (
      <div className="rounded border border-ink/10 bg-ink/[0.02] p-3 text-sm text-ink/60">
        <button type="button" disabled className="h-10 w-full rounded border border-ink/15 bg-white px-4 font-semibold text-ink/45">
          {label}
        </button>
        <p className="mt-2">Set `NEXT_PUBLIC_GOOGLE_CLIENT_ID` in Vercel to enable Google sign-in.</p>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div ref={buttonRef} className="min-h-10" aria-label={label} />
      {googleLogin.isPending ? <p className="text-sm text-ink/60">Signing in with Google...</p> : null}
      {status ? <p className="text-sm text-coral">{status}</p> : null}
    </div>
  );
}
