import { queryOptions } from "@tanstack/react-query";

import type {
  AiResponse,
  ConnectionConfig,
  SessionStatus,
  SessionSummary,
  SessionResults,
} from "./contracts";

function sessionUrl(config: ConnectionConfig, suffix = "") {
  return `${config.backendUrl.replace(/\/$/, "")}/api/v1/sessions/${encodeURIComponent(config.sessionId)}${suffix}`;
}

export async function resolveSessionConfig(
  config: ConnectionConfig,
): Promise<ConnectionConfig> {
  const identifier = config.sessionId.trim();
  if (identifier.includes("-")) return { ...config, sessionId: identifier };
  const response = await fetch(
    `${config.backendUrl.replace(/\/$/, "")}/api/v1/sessions/resolve/${encodeURIComponent(identifier.toUpperCase())}`,
  );
  if (!response.ok) throw new Error("The session code is invalid or expired.");
  const payload = (await response.json()) as { session_id: string };
  return { ...config, sessionId: payload.session_id };
}

async function fetchSession(config: ConnectionConfig): Promise<SessionStatus> {
  const response = await fetch(
    sessionUrl(config),
  );
  if (!response.ok) throw new Error("The session could not be loaded from the backend.");
  return response.json() as Promise<SessionStatus>;
}

async function postLanguage<T>(
  config: ConnectionConfig,
  suffix: string,
  language = "English",
): Promise<T> {
  const response = await fetch(sessionUrl(config, suffix), {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ language }),
  });
  if (!response.ok) {
    const payload = (await response.json().catch(() => null)) as
      | { error?: { message?: string }; detail?: string }
      | null;
    throw new Error(
      payload?.error?.message ?? payload?.detail ?? "The backend could not complete the request.",
    );
  }
  return response.json() as Promise<T>;
}

export function requestFeedback(config: ConnectionConfig) {
  return postLanguage<AiResponse>(config, "/feedback");
}

export function requestSummary(config: ConnectionConfig) {
  return postLanguage<SessionSummary>(config, "/summary");
}

export function sessionQueryOptions(config: ConnectionConfig) {
  return queryOptions({
    queryKey: ["session", config.backendUrl, config.sessionId],
    queryFn: () => fetchSession(config),
    refetchInterval: 5_000,
  });
}

export function sessionResultsQueryOptions(config: ConnectionConfig) {
  return queryOptions({
    queryKey: ["session-results", config.backendUrl, config.sessionId],
    queryFn: async () => {
      const response = await fetch(sessionUrl(config, "/results?limit=1000"));
      if (!response.ok) throw new Error("Session history could not be loaded.");
      return response.json() as Promise<SessionResults>;
    },
    staleTime: Number.POSITIVE_INFINITY,
  });
}
