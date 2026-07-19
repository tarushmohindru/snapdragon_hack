from src.contracts import RealtimeFeedbackRequest, SessionSummaryRequest
from src.llm.feedback import get_realtime_status, get_session_summary
from src.settings import get_settings

settings = get_settings()

print("=== Testing real-time status (Gemini/Gemma) ===")
status = get_realtime_status(
    RealtimeFeedbackRequest(
        exercise="left_bicep_curl",
        primary_angle_degrees=95.0,
        rep_count=4,
        movement_state="up",
        form_quality="good",
    ),
    settings,
)
print("Status:", status)

print("\n=== Testing session summary (Sarvam) ===")
summary = get_session_summary(
    SessionSummaryRequest(
        exercise="left_bicep_curl",
        total_reps=12,
        angle_min=42,
        angle_max=168,
        duration_seconds=95,
        form_notes=["elbow flared on reps 4 and 7", "good depth throughout"],
    ),
    settings,
)

print("Summary:", summary)
