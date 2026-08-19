/**
 * Shared transient-retry policy for the run loop and the embed endpoint:
 * exponential backoff (`100ms << attempt`, capped at 6.4s) plus a
 * signal-aware sleep so a client disconnect aborts the wait.
 */

/** Exponential backoff: `100ms << attempt`, capped at 6.4s. */
export function backoffDelayMs(attempt: number): number {
  return Math.min(100 * 2 ** (attempt - 1), 6400);
}

/** Sleeps until the delay elapses (false) or the signal aborts (true). */
export function sleepOrAbort(ms: number, signal: AbortSignal): Promise<boolean> {
  if (signal.aborted) {
    return Promise.resolve(true);
  }
  return new Promise((resolve) => {
    const cleanup = () => {
      clearTimeout(timer);
      signal.removeEventListener("abort", onAbort);
    };
    const timer = setTimeout(() => {
      cleanup();
      resolve(false);
    }, ms);
    const onAbort = () => {
      cleanup();
      resolve(true);
    };
    signal.addEventListener("abort", onAbort);
  });
}
