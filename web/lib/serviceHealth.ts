"use client";

import { useEffect, useSyncExternalStore } from "react";
import { API_URL, DEMO_MODE } from "./api";
import { createServiceHealthMonitor, INITIAL_HEALTH } from "./serviceHealthClient";
export type { ProbeState, ServiceStatus } from "./serviceHealthClient";

const monitor = createServiceHealthMonitor({ frontendUrl: "/api/health", backendUrl: `${API_URL}/actuator/health/liveness`, demo: DEMO_MODE,
  isOnline: () => typeof navigator === "undefined" || navigator.onLine !== false });
let observers = 0;
function reconnect() { if (document.visibilityState !== "hidden") void monitor.check(); }
function offline() { monitor.setOffline(); }
function visible() {
  if (document.visibilityState === "hidden") { if (monitor.getSnapshot().checking) monitor.pause(); }
  else monitor.refreshIfStale();
}
function focus() { if (document.visibilityState !== "hidden") monitor.refreshIfStale(); }
export function useServiceHealth() {
  const snapshot = useSyncExternalStore(monitor.subscribe, monitor.getSnapshot, () => INITIAL_HEALTH);
  useEffect(() => {
    if (++observers === 1) {
      window.addEventListener("online", reconnect); window.addEventListener("offline", offline);
      window.addEventListener("focus", focus); document.addEventListener("visibilitychange", visible);
      if (document.visibilityState !== "hidden") monitor.refreshIfStale();
    }
    return () => {
      if (--observers === 0) {
        window.removeEventListener("online", reconnect); window.removeEventListener("offline", offline);
        window.removeEventListener("focus", focus); document.removeEventListener("visibilitychange", visible);
      }
    };
  }, []);
  return { ...snapshot, check: monitor.check };
}
