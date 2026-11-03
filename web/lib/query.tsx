"use client";

import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReactNode, useEffect, useState } from "react";
import { ApiClientError, SESSION_CLEARED_EVENT } from "./api";

export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(() => new QueryClient({
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: (failureCount, error) => !(error instanceof ApiClientError && error.status === 401) && failureCount < 1
      }
    }
  }));

  useEffect(() => {
    const clear = () => client.clear();
    window.addEventListener(SESSION_CLEARED_EVENT, clear);
    return () => window.removeEventListener(SESSION_CLEARED_EVENT, clear);
  }, [client]);

  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
