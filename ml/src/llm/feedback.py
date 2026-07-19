import requests
from src import config

def get_realtime_status(elbow_angle,rep_count,state):

    config.require("GOOGLE_API_KEY",config.GOOGLE_API_KEY)

    prompt = (
        f"You are a live fitness coach. Current data: "
        f"elbow angle = {elbow_angle:.1f} degrees, rep count = {rep_count}, "
        f"current phase = '{state}'. "
        f"Give ONE short coaching cue (max 10 words), encouraging and specific. "
        f"Do not repeat the raw numbers back, just give actionable feedback."
    )

    response = requests.post(
        "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions",
        headers={
            "Authorization": f"Bearer {config.GOOGLE_API_KEY}",
            "Content-Type": "application/json",
        },
        json={
            "model": config.GOOGLE_API_KEY,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 30,
            "temperature": 0.7,
        },
        timeout=5,
    )
    response.raise_for_status()
    return response.json()["choices"][0]["message"]["content"].strip()

def get_session_summary(session_data, language ="English"):

    config.require("SARVAM_API_KEY",config.SARVAM_API_KEY)

    prompt = (
        f"You are a fitness coach reviewing a completed workout set. "
        f"Exercise: {session_data.get('exercise')}. "
        f"Total reps: {session_data.get('total_reps')}. "
        f"Elbow angle range: {session_data.get('avg_elbow_angle_range')}. "
        f"Duration: {session_data.get('duration_seconds')} seconds. "
        f"Form notes: {', '.join(session_data.get('form_notes', []))}. "
        f"Write a short (3-4 sentence) summary of the set in {language}, "
        f"including one specific tip for improvement. Be encouraging."
    )

    response = requests.post(
        "https://api.sarvam.ai/v1/chat/completions",
        headers= {
            "Authorization": f"Bearer {config.SARVAM_API_KEY}",
            "Content-Type": "application/json",
        },
        json= {
            "model": config.SARVAM_MODEL,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 200,
            "temperature": 0.5,
        },
        timeout=15, 
    )
    if response.status_code != 200:
        print("Google API error response:", response.text)
    response.raise_for_status()
    return response.json()["choices"][0]["message"]["content"].strip()