"use client";

import * as Sentry from "@sentry/nextjs";
import { useEffect } from "react";

export default function GlobalError({
  error,
  reset
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    Sentry.captureException(error);
  }, [error]);

  return (
    <html lang="en">
      <body>
        <main className="flex min-h-screen items-center justify-center bg-slate-50 px-6">
          <div className="max-w-md text-center">
            <h1 className="text-2xl font-semibold text-slate-900">Something went wrong</h1>
            <p className="mt-3 text-sm text-slate-600">
              Please try again. If the problem continues, contact support.
            </p>
            <button
              type="button"
              onClick={reset}
              className="mt-6 rounded-lg bg-slate-900 px-4 py-2 text-sm font-medium text-white"
            >
              Try again
            </button>
          </div>
        </main>
      </body>
    </html>
  );
}
