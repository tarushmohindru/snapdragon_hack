"use client";

import { skipToken, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useRef } from "react";
import useWebSocket, { ReadyState } from "react-use-websocket";

import type {
  ConnectionConfig,
  SocketMessage,
} from "@/lib/contracts";
import { sessionQueryOptions } from "@/lib/session-query";
import { useLiveSessionStore } from "@/store/live-session";

function socketUrl(config: ConnectionConfig): string {
  const backend = config.backendUrl.replace(/^http/, "ws").replace(/\/$/, "");
  return `${backend}/api/v1/ws/sessions/${encodeURIComponent(config.sessionId)}`;
}

export function LiveSessionBridge({ config }: { config: ConnectionConfig | null }) {
  const queryClient = useQueryClient();
  const applyResult = useLiveSessionStore((state) => state.applyResult);
  const setError = useLiveSessionStore((state) => state.setError);
  const setPhase = useLiveSessionStore((state) => state.setPhase);
  const setSocketStatus = useLiveSessionStore((state) => state.setSocketStatus);

  const helloSent = useRef(false);

  useQuery(
    config
      ? sessionQueryOptions(config)
      : { queryKey: ["session", "inactive"], queryFn: skipToken },
  );

  const { lastJsonMessage, readyState, sendJsonMessage } = useWebSocket<SocketMessage>(
    config ? socketUrl(config) : null,
    {
      share: true,
      retryOnError: true,
      reconnectAttempts: 20,
      reconnectInterval: (attempt) => Math.min(1_000 * 2 ** attempt, 15_000),
      shouldReconnect: (event) => event.code !== 1000 && event.code !== 1008,
      heartbeat: {
        message: JSON.stringify({ schema_version: 1, type: "ping" }),
        returnMessage: JSON.stringify({ schema_version: 1, type: "pong" }),
        interval: 20_000,
        timeout: 8_000,
      },
      onOpen: () => {
        helloSent.current = false;
        setPhase("connected");
        setError(null);
      },
      onError: () => setError("The live connection failed. Reconnecting automatically."),
      onReconnectStop: () => {
        setPhase("error");
        setError("Reconnect limit reached. Verify the backend URL and session ID.");
      },
    },
    config !== null,
  );

  useEffect(() => {
    if (!config) {
      setPhase("idle");
      return;
    }
    if (readyState === ReadyState.CONNECTING) setPhase("connecting");
    if (readyState === ReadyState.CLOSED) setPhase("reconnecting");
  }, [config, readyState, setPhase]);

  useEffect(() => {
    if (!config || readyState !== ReadyState.OPEN || helloSent.current) return;
    sendJsonMessage({
      schema_version: 1,
      type: "device.hello",
      session_id: config.sessionId,
      device_id: null,
      role: "dashboard",
    });
    helloSent.current = true;
  }, [config, readyState, sendJsonMessage]);

  useEffect(() => {
    if (!lastJsonMessage) return;
    if (lastJsonMessage.type === "pose.result") applyResult(lastJsonMessage);
    if (lastJsonMessage.type === "session.status") {
      setSocketStatus(lastJsonMessage);
      void queryClient.invalidateQueries({ queryKey: ["session"] });
    }
    if (lastJsonMessage.type === "error") setError(lastJsonMessage.message);
  }, [applyResult, lastJsonMessage, queryClient, setError, setSocketStatus]);

  return null;
}
