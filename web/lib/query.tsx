"use client";

import { MutationCache, QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { ReactNode, useEffect, useState } from "react";
import { ApiClientError, SESSION_CLEARED_EVENT, SESSION_CHANGED_EVENT, observeSessionChange } from "./api";

export function Providers({ children }: { children: ReactNode }) {
  const [client] = useState(() => {
    const nextClient: QueryClient = new QueryClient({
    mutationCache: new MutationCache({ onSuccess: () => nextClient.invalidateQueries({ predicate: query => !["permissions", "tenant", "me"].includes(String(query.queryKey[0])) }) }),
    defaultOptions: {
      queries: {
        staleTime: 30_000,
        retry: (failureCount, error) => !(error instanceof ApiClientError && error.status >= 400 && error.status < 500) && failureCount < 1
      }
    }
    });
    return nextClient;
  });

  useEffect(() => {
    const clear = () => client.clear();
    const changed = () => {
      client.clear();
      window.location.replace("/login?changed=1");
    };
    window.addEventListener(SESSION_CLEARED_EVENT, clear);
    window.addEventListener(SESSION_CHANGED_EVENT, changed);
    window.addEventListener("storage", observeSessionChange);
    return () => {
      window.removeEventListener(SESSION_CLEARED_EVENT, clear);
      window.removeEventListener(SESSION_CHANGED_EVENT, changed);
      window.removeEventListener("storage", observeSessionChange);
    };
  }, [client]);

  return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
}
