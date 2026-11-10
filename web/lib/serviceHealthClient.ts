export type ServiceStatus = "unknown" | "checking" | "waiting" | "online" | "unavailable" | "offline" | "demo";
export type ProbeState = { status: ServiceStatus; checkedAt: number | null; responseTimeMs: number | null; message: string };
export type HealthSnapshot = { frontend: ProbeState; backend: ProbeState; checking: boolean };
type Target = "frontend" | "backend";
type Options = {
  frontendUrl: string;
  backendUrl: string;
  demo?: boolean;
  fetcher?: typeof fetch;
  isOnline?: () => boolean;
  now?: () => number;
  frontendTimeoutMs?: number;
  backendTimeoutMs?: number;
  attemptTimeoutMs?: number;
  waitingAfterMs?: number;
  retryDelayMs?: number;
  staleAfterMs?: number;
};
const unknown = (): ProbeState => ({ status: "unknown", checkedAt: null, responseTimeMs: null, message: "Not checked yet." });
export const INITIAL_HEALTH: HealthSnapshot = { frontend: unknown(), backend: unknown(), checking: false };
class ProbeFailure extends Error {
  constructor(message: string, readonly retryable: boolean) { super(message); }
}

/** Public, credential-free observations; no session, business data or continuous keep-alive traffic. */
export function createServiceHealthMonitor(options: Options) {
  const fetcher = options.fetcher ?? ((...args: Parameters<typeof fetch>) => fetch(...args));
  const now = options.now ?? Date.now;
  const online = options.isOnline ?? (() => true);
  const frontendTimeout = options.frontendTimeoutMs ?? 8_000;
  const backendTimeout = options.backendTimeoutMs ?? 90_000;
  const attemptTimeout = options.attemptTimeoutMs ?? 15_000;
  const retryDelay = options.retryDelayMs ?? 5_000;
  const staleAfter = options.staleAfterMs ?? 120_000;
  const listeners = new Set<() => void>();
  let snapshot = INITIAL_HEALTH;
  let generation = 0;
  let controller: AbortController | null = null;
  let inFlight: Promise<void> | null = null;
  let staleTimer: ReturnType<typeof setTimeout> | undefined;
  let hasStarted = false;
  function publish(next: Partial<HealthSnapshot>) {
    snapshot = { ...snapshot, ...next };
    listeners.forEach(listener => listener());
  }
  function update(target: Target, value: Partial<ProbeState>, run: number) {
    if (run === generation) publish({ [target]: { ...snapshot[target], ...value } });
  }
  function scheduleExpiry(run: number) {
    if (run !== generation) return;
    clearTimeout(staleTimer);
    let nextExpiry = Infinity;
    for (const target of ["frontend", "backend"] as const) {
      const value = snapshot[target];
      if (value.checkedAt == null || !["online", "unavailable"].includes(value.status)) continue;
      const remaining = value.checkedAt + staleAfter - now();
      if (remaining <= 0) update(target, { status: "unknown", message: "The last check is over two minutes old. Check again for the current status." }, run);
      else nextExpiry = Math.min(nextExpiry, remaining);
    }
    if (run === generation && Number.isFinite(nextExpiry)) staleTimer = setTimeout(() => scheduleExpiry(run), nextExpiry);
  }
  function pause(message = "Check paused. Check again for a current result.") {
    generation++;
    controller?.abort(); controller = null; inFlight = null;
    clearTimeout(staleTimer);
    publish({ checking: false,
      frontend: { ...snapshot.frontend, status: "unknown", message },
      backend: { ...snapshot.backend, status: options.demo ? "demo" : "unknown", message: options.demo ? "Demo mode: no backend connection is used." : message }
    });
  }
  function setOffline() {
    pause();
    const state: ProbeState = { status: "offline", checkedAt: now(), responseTimeMs: null, message: "Your browser reports no network connection. Reconnect and check again." };
    publish({ frontend: state, backend: options.demo ? snapshot.backend : state });
  }
  async function requestOnce(target: Target, timeout: number, signal: AbortSignal) {
    const attempt = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let abortListener: () => void = () => {};
    const interrupted = new Promise<never>((_, reject) => {
      abortListener = () => { reject(new ProbeFailure("Check cancelled.", false)); attempt.abort(); };
      signal.addEventListener("abort", abortListener, { once: true });
      timer = setTimeout(() => { reject(new ProbeFailure("The service did not respond in time.", true)); attempt.abort(); }, timeout);
      if (signal.aborted) abortListener();
    });
    try {
      await Promise.race([interrupted, (async () => {
        const base = target === "frontend" ? options.frontendUrl : options.backendUrl;
        const url = `${base}${base.includes("?") ? "&" : "?"}check=${now()}`;
        const response = await fetcher(url, { method: "GET", credentials: "omit", cache: "no-store", redirect: "error", headers: { Accept: "application/json" }, signal: attempt.signal });
        if (!response.ok) {
          if ([502, 503, 504].includes(response.status)) {
            // A genuine liveness DOWN is different from a hosting proxy's startup/error page.
            if (response.headers.get("content-type")?.includes("application/json")) {
              const body = await response.json().catch(() => null);
              if (body?.status === "DOWN" || body?.status === "OUT_OF_SERVICE") throw new ProbeFailure("The service reports that it is unavailable.", false);
            }
            throw new ProbeFailure("The hosting service has not made the API available yet.", true);
          }
          throw new ProbeFailure(`Health check returned HTTP ${response.status}. Check the deployment or access configuration.`, false);
        }
        if (!response.headers.get("content-type")?.includes("application/json")) throw new ProbeFailure("The health URL did not return a health response.", target === "backend");
        const body = await response.json();
        if (body?.status !== "UP" || (target === "frontend" && body?.service !== "stockpilot-web")) {
          throw new ProbeFailure("The service did not report a healthy response.", false);
        }
      })()]);
    } finally {
      clearTimeout(timer);
      signal.removeEventListener("abort", abortListener);
      attempt.abort();
    }
  }
  async function delay(ms: number, signal: AbortSignal) {
    await new Promise<void>(resolve => {
      const done = () => { clearTimeout(timer); signal.removeEventListener("abort", done); resolve(); };
      const timer = setTimeout(done, ms);
      signal.addEventListener("abort", done, { once: true });
      if (signal.aborted) done();
    });
  }
  async function probe(target: Target, run: number, signal: AbortSignal) {
    const started = now();
    const deadline = started + (target === "frontend" ? frontendTimeout : backendTimeout);
    const waitingMessage = "The API is taking longer to respond. It may be starting after inactivity or temporarily slow.";
    const waitingTimer = target === "backend" ? setTimeout(() => update(target, { status: "waiting", message: waitingMessage }, run), options.waitingAfterMs ?? 3_000) : undefined;
    try {
      while (!signal.aborted) {
        if (!online()) { setOffline(); return; }
        const remaining = deadline - now();
        try {
          if (remaining <= 0) throw new ProbeFailure("The service did not respond in time.", false);
          await requestOnce(target, Math.min(remaining, target === "backend" ? attemptTimeout : frontendTimeout), signal);
          if (!signal.aborted) update(target, { status: "online", checkedAt: now(), responseTimeMs: Math.max(0, now() - started), message: target === "frontend" ? "The website server responded successfully." : "The API process responded. Database and workspace access are checked separately." }, run);
          return;
        } catch (error) {
          if (signal.aborted || run !== generation) return;
          const retryable = !(error instanceof ProbeFailure) || error.retryable;
          if (target === "backend" && retryable && now() < deadline) {
            update(target, { status: "waiting", message: waitingMessage }, run);
            await delay(Math.min(retryDelay, deadline - now()), signal);
          } else {
            update(target, { status: "unavailable", checkedAt: now(), responseTimeMs: null,
              message: error instanceof ProbeFailure && !retryable ? error.message : "Could not reach the service. It may be starting, unavailable, or blocked by your connection. Check again." }, run);
            return;
          }
        }
      }
    } finally { clearTimeout(waitingTimer); }
  }
  function check(): Promise<void> {
    if (inFlight) return inFlight;
    hasStarted = true;
    if (!online()) { setOffline(); return Promise.resolve(); }
    clearTimeout(staleTimer);
    const run = ++generation;
    const current = new AbortController(); controller = current;
    // Reserve the shared promise before subscribers can respond to a state change.
    inFlight = Promise.resolve().then(() => Promise.all([probe("frontend", run, current.signal), ...(options.demo ? [] : [probe("backend", run, current.signal)])])).then(() => {}).finally(() => {
      if (run !== generation) return;
      controller = null; inFlight = null;
      scheduleExpiry(run);
      publish({ checking: false });
    });
    const pending = inFlight;
    publish({ checking: true,
      frontend: { ...snapshot.frontend, status: "checking", responseTimeMs: null, message: "Checking the website server…" },
      backend: options.demo ? { status: "demo", checkedAt: null, responseTimeMs: null, message: "Demo mode: no backend connection is used." } : { ...snapshot.backend, status: "checking", responseTimeMs: null, message: "Connecting to the API…" }
    });
    return pending;
  }
  return {
    getSnapshot: () => snapshot,
    subscribe(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener); }; },
    check, setOffline, pause,
    start() { if (!hasStarted) void check(); },
    refreshIfStale() { if (!hasStarted || (!snapshot.checking && [snapshot.frontend, snapshot.backend].some(item => item.status !== "demo" && (item.status === "unknown" || item.status === "offline" || item.checkedAt == null || now() - item.checkedAt >= 60_000)))) void check(); },
    dispose() { pause(); listeners.clear(); }
  };
}
