import httpx

from src.contracts import RealtimeFeedbackRequest, SessionSummaryRequest
from src.settings import Settings


class AiNotConfigured(RuntimeError):
    pass


def get_realtime_status(
    request: RealtimeFeedbackRequest,
    settings: Settings,
) -> tuple[str, str, str]:
    if not settings.google_api_key:
        raise AiNotConfigured("Google AI coaching is not configured")
    prompt = (
        "You are a concise exercise coach. Give one actionable cue in no more than 10 words. "
        f"Exercise: {request.exercise}. Angle: {request.primary_angle_degrees}. "
        f"Reps: {request.rep_count}. Phase: {request.movement_state}. "
        f"Current form assessment: {request.form_quality}. Language: {request.language}. "
        "Do not repeat measurements and do not make medical claims."
    )
    response = httpx.post(
        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        headers={"Authorization": f"Bearer {settings.google_api_key}"},
        json={
            "model": settings.google_model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 40,
            "temperature": 0.4,
        },
        timeout=8,
    )
    response.raise_for_status()
    text = response.json()["choices"][0]["message"]["content"].strip()
    return text, "google", settings.google_model


def get_session_summary(
    request: SessionSummaryRequest,
    settings: Settings,
) -> tuple[str, str, str]:
    if not settings.sarvam_api_key:
        raise AiNotConfigured("Sarvam session summaries are not configured")
    prompt = (
        "You are reviewing a completed exercise session. Write a supportive 3-4 sentence "
        "summary with one specific improvement tip and no medical claims. "
        f"Exercise: {request.exercise}. Reps: {request.total_reps}. "
        f"Duration: {request.duration_seconds} seconds. Angle range: "
        f"{request.angle_min} to {request.angle_max}. Notes: {', '.join(request.form_notes)}. "
        f"Language: {request.language}."
    )
    response = httpx.post(
        "https://api.sarvam.ai/v1/chat/completions",
        headers={"Authorization": f"Bearer {settings.sarvam_api_key}"},
        json={
            "model": settings.sarvam_model,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 240,
            "temperature": 0.45,
        },
        timeout=20,
    )
    response.raise_for_status()
    text = response.json()["choices"][0]["message"]["content"].strip()
    return text, "sarvam", settings.sarvam_model
