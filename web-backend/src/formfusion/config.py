from functools import lru_cache
from pathlib import Path
from typing import Literal

from pydantic import Field, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(
        env_file=".env",
        env_prefix="FORMFUSION_",
        case_sensitive=False,
        extra="ignore",
    )

    app_name: str = "FormFusion Backend"
    environment: Literal["development", "test", "production"] = "development"
    jwt_secret: str = "local-development-secret-change-before-deploying"
    jwt_algorithm: str = "HS256"
    token_ttl_seconds: int = Field(default=7_200, ge=60, le=86_400)
    session_ttl_seconds: int = Field(default=7_200, ge=300, le=86_400)
    allowed_origins: list[str] = ["http://localhost:3000"]
    max_devices_per_session: int = Field(default=2, ge=2, le=8)
    frame_queue_capacity: int = Field(default=12, ge=2, le=128)
    frame_sync_tolerance_ms: int = Field(default=60, ge=1, le=1_000)
    min_keypoint_confidence: float = Field(default=0.5, ge=0.0, le=1.0)
    max_calibration_image_bytes: int = Field(default=10 * 1024 * 1024, ge=1024, le=50 * 1024 * 1024)
    calibration_root: Path = Path(".data/calibration")

    @model_validator(mode="after")
    def validate_production_secrets(self) -> "Settings":
        if self.environment == "production" and (
            len(self.jwt_secret) < 32 or "change-before" in self.jwt_secret
        ):
            raise ValueError("production requires a strong FORMFUSION_JWT_SECRET")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
