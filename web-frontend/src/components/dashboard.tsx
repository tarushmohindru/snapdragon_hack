"use client";

import { skipToken, useMutation, useQuery } from "@tanstack/react-query";
import {
  Activity,
  BrainCircuit,
  Camera,
  Check,
  ChevronRight,
  CircleGauge,
  Clock3,
  Focus,
  Orbit,
  Radio,
  RefreshCcw,
  ScanLine,
  ShieldCheck,
  Sparkles,
  TriangleAlert,
  Waves,
} from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import dynamic from "next/dynamic";
import { useEffect, useMemo, useState } from "react";
import { useInterval } from "usehooks-ts";

import type { ConnectionConfig } from "@/lib/contracts";
import {
  requestFeedback,
  requestSummary,
  sessionResultsQueryOptions,
  sessionQueryOptions,
} from "@/lib/session-query";
import { useLiveSessionStore } from "@/store/live-session";
import { AngleHistoryChart } from "./angle-history-chart";
import { ConnectionPanel } from "./connection-panel";
import styles from "./dashboard.module.css";
import { LiveSessionBridge } from "./live-session-bridge";

const SkeletonScene = dynamic(() => import("./skeleton-scene"), {
  ssr: false,
  loading: () => <StageMessage title="Initializing spatial engine" detail="Loading the 3D renderer" />,
});

const reveal = {
  hidden: { opacity: 0, y: 18 },
  visible: { opacity: 1, y: 0 },
};

function StageMessage({ title, detail }: { title: string; detail: string }) {
  return (
    <div className={styles.stageMessage}>
      <div className={styles.stageSignal}>
        <span />
        <span />
        <span />
      </div>
      <p>Spatial feed</p>
      <strong>{title}</strong>
      <small>{detail}</small>
    </div>
  );
}

function formatExercise(value?: string) {
  if (!value) return "Exercise not selected";
  return value.replaceAll("_", " ").replace(/\b\w/g, (letter) => letter.toUpperCase());
}

export function Dashboard() {
  const [config, setConfig] = useState<ConnectionConfig | null>(null);
  const [now, setNow] = useState(0);
  const phase = useLiveSessionStore((state) => state.phase);
  const latest = useLiveSessionStore((state) => state.latest);
  const history = useLiveSessionStore((state) => state.history);
  const error = useLiveSessionStore((state) => state.error);
  const lastMessageAt = useLiveSessionStore((state) => state.lastMessageAt);
  const reset = useLiveSessionStore((state) => state.reset);
  const clearHistory = useLiveSessionStore((state) => state.clearHistory);
  const hydrateResults = useLiveSessionStore((state) => state.hydrateResults);

  useInterval(() => setNow(Date.now()), 1_000);

  const sessionQuery = useQuery(
    config
      ? sessionQueryOptions(config)
      : { queryKey: ["session", "inactive"], queryFn: skipToken },
  );
  const session = sessionQuery.data;
  const resultsQuery = useQuery(
    config
      ? sessionResultsQueryOptions(config)
      : { queryKey: ["session-results", "inactive"], queryFn: skipToken },
  );
  useEffect(() => {
    if (resultsQuery.data) hydrateResults(resultsQuery.data.results);
  }, [hydrateResults, resultsQuery.data]);
  const feedbackMutation = useMutation({
    mutationFn: () => {
      if (!config) throw new Error("Connect a session first.");
      return requestFeedback(config);
    },
  });
  const summaryMutation = useMutation({
    mutationFn: () => {
      if (!config) throw new Error("Connect a session first.");
      return requestSummary(config);
    },
  });
  const dataAge = lastMessageAt ? now - lastMessageAt : null;
  const stale = dataAge !== null && dataAge > 3_000;
  const devices = session?.device_ids.length ?? 0;
  const jointCount = latest ? Object.keys(latest.joints_3d).length : 0;

  const stage = useMemo(() => {
    if (!config) {
      return <StageMessage title="No session linked" detail="Open the connection deck to enter session credentials" />;
    }
    if (sessionQuery.isLoading) {
      return <StageMessage title="Reading session state" detail="Checking cameras and calibration" />;
    }
    if (sessionQuery.isError) {
      return <StageMessage title="Session unavailable" detail="Verify the backend URL and session ID" />;
    }
    if (phase === "connecting" || phase === "reconnecting") {
      return <StageMessage title="Reacquiring signal" detail="The live link will resume automatically" />;
    }
    if (devices < 2) {
      return <StageMessage title="Awaiting camera array" detail={`${devices} of 2 pose devices have joined`} />;
    }
    if (session && !session.calibrated) {
      return <StageMessage title="Spatial calibration pending" detail="Complete paired checkerboard capture to unlock 3D" />;
    }
    if (!latest) {
      return <StageMessage title="Awaiting synchronized motion" detail="Both cameras are ready; no paired frame has arrived" />;
    }
    if (!jointCount) {
      return <StageMessage title="No confident joint match" detail="The viewport resumes when both views agree" />;
    }
    if (stale) {
      return <StageMessage title="Motion stream paused" detail="The last result is stale; the session remains intact" />;
    }
    return <SkeletonScene joints={latest.joints_3d} />;
  }, [config, devices, jointCount, latest, phase, session, sessionQuery.isError, sessionQuery.isLoading, stale]);

  const quality = latest?.form_quality;
  const qualityLabel = quality === "good" ? "Good rep" : quality === "check" ? "Check form" : "Awaiting assessment";
  const qualityTone = quality === "good" ? styles.good : quality === "check" ? styles.warning : styles.neutral;
  const liveState = stale ? "signal stale" : latest ? "live capture" : "standing by";

  return (
    <motion.main
      className={styles.shell}
      initial="hidden"
      animate="visible"
      transition={{ staggerChildren: 0.08 }}
    >
      <LiveSessionBridge config={config} />

      <motion.header className={styles.header} variants={reveal} transition={{ duration: 0.65 }}>
        <div className={styles.brandBlock}>
          <span className={styles.brandGlyph}>
            <ScanLine size={22} strokeWidth={1.7} />
          </span>
          <div>
            <strong>FORMFUSION</strong>
            <small>Spatial motion intelligence</small>
          </div>
        </div>

        <div className={styles.headerCenter}>
          <span>SESSION</span>
          <strong>{config?.sessionId ?? "NOT LINKED"}</strong>
        </div>

        <div className={styles.headerTools}>
          <span className={styles.secureLabel}><ShieldCheck size={14} /> On-device inference</span>
          <span className={`${styles.liveState} ${latest && !stale ? styles.liveStateActive : ""}`}>
            <i /> {liveState}
          </span>
        </div>
      </motion.header>

      <motion.div variants={reveal} transition={{ duration: 0.65 }}>
        <ConnectionPanel
          config={config}
          phase={phase}
          onConnect={(nextConfig) => {
            reset();
            setConfig(nextConfig);
          }}
          onDisconnect={() => {
            setConfig(null);
            reset();
          }}
        />
      </motion.div>

      <AnimatePresence>
        {error && (
          <motion.div
            className={styles.errorBanner}
            role="alert"
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
          >
            <TriangleAlert size={16} />
            <span>{error}</span>
          </motion.div>
        )}
      </AnimatePresence>

      <motion.section className={styles.kinematicDeck} variants={reveal} transition={{ duration: 0.8 }}>
        <article className={styles.stagePanel}>
          <div className={styles.verticalWord} aria-hidden="true">KINEMATICS</div>
          <div className={styles.stageTopline}>
            <div>
              <span className={styles.eyebrow}>LIVE 3D RECONSTRUCTION</span>
              <h1>
                Human motion,
                <em> resolved.</em>
              </h1>
            </div>
            <div className={styles.stageIndex}>
              <span>ACTIVE PROTOCOL</span>
              <strong>{formatExercise(session?.exercise)}</strong>
            </div>
          </div>

          <div className={styles.scene}>
            <div className={styles.scanLine} />
            <div className={`${styles.corner} ${styles.cornerA}`} />
            <div className={`${styles.corner} ${styles.cornerB}`} />
            <div className={`${styles.corner} ${styles.cornerC}`} />
            <div className={`${styles.corner} ${styles.cornerD}`} />
            {stage}

            <div className={styles.angleOverlay}>
              <span>PRIMARY ANGLE</span>
              <div>
                <strong>{latest?.primary_angle_degrees?.toFixed(1) ?? "—"}</strong>
                <small>°</small>
              </div>
              <p>{latest ? latest.movement_state : "No movement state"}</p>
            </div>

            <div className={styles.sceneCompass}>
              <Orbit size={15} />
              <span>DRAG TO ORBIT</span>
            </div>
          </div>

          <div className={styles.stageFootline}>
            <span><Radio size={13} /> {latest ? `${jointCount} spatial joints` : "No pose frame"}</span>
            <span><Clock3 size={13} /> {latest ? new Date(latest.captured_at_ms).toLocaleTimeString() : "Awaiting timestamp"}</span>
            <span><Waves size={13} /> {latest ? `${latest.metadata.pairing_delta_ms} ms stereo delta` : "Pairing inactive"}</span>
          </div>
        </article>

        <aside className={styles.instrumentRail}>
          <section className={styles.railIntro}>
            <span>PERFORMANCE SIGNAL</span>
            <h2>One body.<br /><em>Two perspectives.</em></h2>
            <p>Live multi-camera reconstruction translated into coaching-ready movement data.</p>
          </section>

          <section className={styles.repInstrument}>
            <div className={styles.instrumentLabel}>
              <Activity size={15} />
              <span>REPETITION INDEX</span>
            </div>
            <strong>{latest?.rep_count ?? "—"}</strong>
            <div className={styles.repScale} aria-hidden="true">
              {Array.from({ length: 12 }, (_, index) => <i key={index} />)}
            </div>
          </section>

          <section className={styles.qualityInstrument}>
            <div className={styles.instrumentLabel}>
              <Focus size={15} />
              <span>FORM ASSESSMENT</span>
            </div>
            <div className={`${styles.qualityStatus} ${qualityTone}`}>
              <span>{quality === "good" ? <Check size={18} /> : quality === "check" ? <TriangleAlert size={18} /> : <Waves size={18} />}</span>
              <div>
                <strong>{qualityLabel}</strong>
                <small>{latest?.form_feedback ?? "Waiting for backend assessment"}</small>
              </div>
            </div>
          </section>

          <section className={styles.calibrationInstrument}>
            <div className={styles.instrumentHeader}>
              <div className={styles.instrumentLabel}>
                <CircleGauge size={15} />
                <span>SPATIAL CALIBRATION</span>
              </div>
              <strong>{session?.calibrated ? "LOCKED" : "PENDING"}</strong>
            </div>

            <div className={styles.errorReadout}>
              <span>REPROJECTION ERROR</span>
              <div>
                <strong>{latest?.metadata.reprojection_error?.toFixed(4) ?? session?.calibration_reprojection_error?.toFixed(4) ?? "—"}</strong>
                <small>PX</small>
              </div>
            </div>

            <div className={styles.signalBar}>
              <span style={{ width: latest?.metadata.reprojection_error !== null && latest?.metadata.reprojection_error !== undefined ? `${Math.max(4, Math.min(100, 100 - latest.metadata.reprojection_error * 40))}%` : "0%" }} />
            </div>

            <div className={styles.calibrationRows}>
              <div><Camera size={14} /><span>Camera array</span><strong>{devices}/2</strong></div>
              <div><Waves size={14} /><span>Stereo delta</span><strong>{latest ? `${latest.metadata.pairing_delta_ms} ms` : "—"}</strong></div>
              <div><CircleGauge size={14} /><span>Calibration</span><strong>{session?.calibrated ? "Ready" : "Not ready"}</strong></div>
            </div>
          </section>
        </aside>
      </motion.section>

      <motion.section className={styles.traceSection} variants={reveal} transition={{ duration: 0.8 }}>
        <div className={styles.traceCopy}>
          <span className={styles.eyebrow}>SESSION CHRONOLOGY</span>
          <h2>Movement leaves<br /><em>a signature.</em></h2>
          <p>The trace records every computed angle in this live browser session. Rep transitions remain visible without interrupting the 3D feed.</p>
          <button type="button" onClick={clearHistory} disabled={!history.length}>
            <RefreshCcw size={14} /> Reset trace
          </button>
        </div>
        <div className={styles.chartPanel}>
          <div className={styles.chartHeader}>
            <span>ANGLE / TIME</span>
            <div><i /> LIVE TELEMETRY <ChevronRight size={13} /></div>
          </div>
          <div className={styles.chartWrap}><AngleHistoryChart history={history} /></div>
        </div>
      </motion.section>

      <motion.section className={styles.intelligenceSection} variants={reveal}>
        <div className={styles.intelligenceHeading}>
          <span className={styles.eyebrow}>AI COACHING LAYER</span>
          <h2>Analysis with<br /><em>real session context.</em></h2>
          <p>Feedback is generated by the configured ML service from the latest reconstructed movement. Nothing is synthesized in the browser.</p>
        </div>
        <article className={styles.intelligenceCard}>
          <BrainCircuit size={18} />
          <span>LIVE COACHING CUE</span>
          <p>{feedbackMutation.data?.text ?? "Request a cue after the first synchronized 3D result arrives."}</p>
          {feedbackMutation.error && <small>{feedbackMutation.error.message}</small>}
          <button
            type="button"
            disabled={!latest || feedbackMutation.isPending}
            onClick={() => feedbackMutation.mutate()}
          >
            <Sparkles size={14} /> {feedbackMutation.isPending ? "Analyzing" : "Generate cue"}
          </button>
        </article>
        <article className={styles.intelligenceCard}>
          <Activity size={18} />
          <span>SESSION REPORT</span>
          <p>{summaryMutation.data?.ai_summary ?? "Generate a persisted report from reps, angle range, duration, and form events."}</p>
          {summaryMutation.error && <small>{summaryMutation.error.message}</small>}
          <button
            type="button"
            disabled={!latest || summaryMutation.isPending}
            onClick={() => summaryMutation.mutate()}
          >
            <Sparkles size={14} /> {summaryMutation.isPending ? "Compiling" : "Generate report"}
          </button>
        </article>
      </motion.section>

      <motion.footer className={styles.footer} variants={reveal}>
        <span>FORMFUSION © 2026</span>
        <span>QUALCOMM AI-ACCELERATED MOTION ANALYSIS</span>
        <span>SPATIAL SESSION / {config?.sessionId?.slice(0, 8) ?? "OFFLINE"}</span>
      </motion.footer>
    </motion.main>
  );
}
