export type ConnectionConfig = {
  backendUrl: string;
  sessionId: string;
};

export type ConnectionPhase =
  | "idle"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "closed"
  | "error";

export type PoseResult = {
  schema_version: 1;
  type: "pose.result";
  session_id: string;
  captured_at_ms: number;
  joints_3d: Record<string, Joint3D>;
  angles: AngleMetric[];
  primary_angle_degrees: number | null;
  rep_count: number;
  movement_state: string;
  form_quality: "good" | "check" | "unknown";
  form_feedback?: string | null;
  metadata: ResultMetadata;
};

export type Joint3D = {
  x: number;
  y: number;
  z: number;
  confidence: number;
  observations: number;
};

export type AngleMetric = {
  name: string;
  degrees: number;
  joint_ids: [number, number, number];
};

export type ResultMetadata = {
  coordinate_system: string;
  units: string;
  calibration_id: string;
  reprojection_error: number | null;
  source_frame_ids: Record<string, number>;
  source_timestamps_ms: Record<string, number>;
  pairing_delta_ms: number;
  processing_time_ms: number;
};

export type SessionStatus = {
  session_id: string;
  exercise: string;
  device_ids: string[];
  calibrated: boolean;
  calibration_reprojection_error: number | null;
  latest_result_at: string | null;
  started_at: string;
  ended_at: string | null;
  expires_at: string;
};

export type AiResponse = { text: string; provider: string; model: string };

export type SessionSummary = {
  session_id: string;
  exercise: string;
  duration_seconds: number;
  total_reps: number;
  angle_min: number | null;
  angle_max: number | null;
  ai_summary: string | null;
  started_at: string;
  ended_at: string | null;
};

export type SessionResults = {
  session_id: string;
  results: PoseResult[];
};

export type SocketError = {
  schema_version: 1;
  type: "error";
  code: string;
  message: string;
  request_id: string | null;
};

export type SessionSocketStatus = {
  schema_version: 1;
  type: "session.status";
  session_id: string;
  status: string;
  connected_devices: number;
  calibrated: boolean;
};

export type PosePreview = {
  schema_version: 1;
  type: "pose.preview";
  session_id: string;
  device_id: string;
  frame_id: number;
  captured_at_ms: number;
  image: { width: number; height: number; rotation_degrees: number; mirrored: boolean };
  keypoints: Array<{ id: number; x: number; y: number; confidence: number }>;
};

export type SocketMessage =
  | PoseResult
  | SocketError
  | SessionSocketStatus
  | PosePreview
  | { schema_version: 1; type: "pong" };

export type AngleSample = {
  capturedAt: number;
  elapsedSeconds: number;
  angle: number;
  rep: number;
  quality: PoseResult["form_quality"];
};
