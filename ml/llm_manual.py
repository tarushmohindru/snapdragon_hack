from src.llm.feedback import get_realtime_status, get_session_summary

print("=== Testing real-time status (Gemini/Gemma) ===")
status = get_realtime_status(elbow_angle=95.0, rep_count=4, state="up")
print("Status:", status)

print("\n=== Testing session summary (Sarvam) ===")
summary = get_session_summary({
    "exercise": "bicep curl",
    "total_reps": 12,
    "avg_elbow_angle_range": [42, 168],
    "duration_seconds": 95,
    "form_notes": ["elbow flared on reps 4, 7", "good depth throughout"]
})

print("Summary:", summary)