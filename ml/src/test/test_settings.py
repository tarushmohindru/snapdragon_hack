from src.settings import Settings


def test_accepts_gemini_api_key(monkeypatch) -> None:
    monkeypatch.delenv("FORMFUSION_ML_GOOGLE_API_KEY", raising=False)
    monkeypatch.delenv("GOOGLE_API_KEY", raising=False)
    monkeypatch.setenv("GEMINI_API_KEY", "gemini-test-key")

    assert Settings(_env_file=None).google_api_key == "gemini-test-key"


def test_prefers_formfusion_google_api_key(monkeypatch) -> None:
    monkeypatch.setenv("FORMFUSION_ML_GOOGLE_API_KEY", "formfusion-test-key")
    monkeypatch.setenv("GEMINI_API_KEY", "gemini-test-key")

    assert Settings(_env_file=None).google_api_key == "formfusion-test-key"
