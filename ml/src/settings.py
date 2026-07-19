from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="FORMFUSION_ML_",
        case_sensitive=False,
        extra="ignore",
    )

    environment: Literal["development", "test", "production"] = "development"
    service_key: str = "local-ml-service-key-change-before-production"
    data_root: Path = Path(".data")
    min_keypoint_confidence: float = Field(default=0.001, ge=0.0, le=1.0)
    max_capture_bytes: int = Field(default=12 * 1024 * 1024, ge=1024)
    allow_approximate_calibration: bool = False
    approximate_camera_baseline_cm: float = Field(default=30.0, gt=1.0, le=500.0)
    google_api_key: str | None = None
    google_model: str = "gemma-3-27b-it"
    sarvam_api_key: str | None = None
    sarvam_model: str = "sarvam-m"

    @model_validator(mode="after")
    def production_secret(self) -> "Settings":
        if self.environment == "production" and len(self.service_key) < 32:
            raise ValueError("production requires a strong FORMFUSION_ML_SERVICE_KEY")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
