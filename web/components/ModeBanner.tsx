"use client";

import { isDemoMode } from "@/lib/api";

export function ModeBanner() {
  if (isDemoMode()) {
    return (
      <div className="border-b border-amber-300 bg-amber-50 px-4 py-2 text-center text-sm font-medium text-amber-900">
        You’re exploring a demo workspace. Changes stay in this browser.
      </div>
    );
  }

  return null;
}
