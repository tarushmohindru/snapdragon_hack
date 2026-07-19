import { create } from "zustand";

import type {
  AngleSample,
  ConnectionPhase,
  PoseResult,
  SessionSocketStatus,
} from "@/lib/contracts";

const MAX_HISTORY_SAMPLES = 600;

type LiveSessionState = {
  phase: ConnectionPhase;
  latest: PoseResult | null;
  socketStatus: SessionSocketStatus | null;
  history: AngleSample[];
  connectedAt: number | null;
  lastMessageAt: number | null;
  error: string | null;
  setPhase: (phase: ConnectionPhase) => void;
  setError: (error: string | null) => void;
  setSocketStatus: (status: SessionSocketStatus) => void;
  applyResult: (result: PoseResult) => void;
  hydrateResults: (results: PoseResult[]) => void;
  reset: () => void;
  clearHistory: () => void;
};

const initialState = {
  phase: "idle" as ConnectionPhase,
  latest: null,
  socketStatus: null,
  history: [] as AngleSample[],
  connectedAt: null as number | null,
  lastMessageAt: null as number | null,
  error: null as string | null,
};

export const useLiveSessionStore = create<LiveSessionState>((set) => ({
  ...initialState,
  setPhase: (phase) =>
    set((state) => ({
      phase,
      connectedAt:
        phase === "connected" && state.connectedAt === null ? Date.now() : state.connectedAt,
    })),
  setError: (error) => set({ error }),
  setSocketStatus: (socketStatus) => set({ socketStatus, lastMessageAt: Date.now() }),
  applyResult: (latest) =>
    set((state) => {
      const connectedAt = state.connectedAt ?? Date.now();
      const sample: AngleSample | null =
        latest.primary_angle_degrees === null
          ? null
          : {
              capturedAt: latest.captured_at_ms,
              elapsedSeconds: Math.max(0, (latest.captured_at_ms - connectedAt) / 1000),
              angle: latest.primary_angle_degrees,
              rep: latest.rep_count,
              quality: latest.form_quality,
            };
      return {
        latest,
        phase: "connected",
        lastMessageAt: Date.now(),
        error: null,
        history: sample
          ? [...state.history, sample].slice(-MAX_HISTORY_SAMPLES)
          : state.history,
      };
    }),
  hydrateResults: (results) =>
    set((state) => {
      if (!results.length || state.latest) return state;
      const firstCapturedAt = results[0].captured_at_ms;
      const history = results.flatMap((result) =>
        result.primary_angle_degrees === null
          ? []
          : [{
              capturedAt: result.captured_at_ms,
              elapsedSeconds: Math.max(0, (result.captured_at_ms - firstCapturedAt) / 1000),
              angle: result.primary_angle_degrees,
              rep: result.rep_count,
              quality: result.form_quality,
            }],
      );
      return {
        ...state,
        latest: results.at(-1) ?? null,
        history: history.slice(-MAX_HISTORY_SAMPLES),
      };
    }),
  reset: () => set(initialState),
  clearHistory: () => set({ history: [] }),
}));
