import os
from dotenv import load_dotenv

load_dotenv()

GOOGLE_API_KEY = os.getenv("GOOGLE_API_KEY")
GOOGLE_MODEL = os.getenv("GOOGLE_MODEL","gemma-4-12b-it")

SARVAM_API_KEY = os.getenv("SARVAM_API_KEY")
SARVAM_MODEL = os.getenv("SARVAM_MODEL","sarvam-30b")

def require(name, value):
    if not value:
        raise RuntimeError(
            f"Missing required config value: {name}. "
            f"Set it in your .env file (e.g. {name}=your_key_here)."
        )
    return value
