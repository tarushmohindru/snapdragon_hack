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
    session_ttl_seconds: int = Field(default=7_200, ge=300, le=86_400)
    allowed_origins: list[str] = ["http://localhost:3000"]
    max_devices_per_session: int = Field(default=2, ge=2, le=8)
    frame_queue_capacity: int = Field(default=12, ge=2, le=128)
    frame_sync_tolerance_ms: int = Field(default=60, ge=1, le=1_000)
    max_calibration_image_bytes: int = Field(default=10 * 1024 * 1024, ge=1024, le=50 * 1024 * 1024)
    ml_service_url: str = "http://127.0.0.1:8100"
    ml_service_key: str = "local-ml-service-key-change-before-production"
    join_code_secret: str = "local-join-code-secret-change-before-production"
    database_path: Path = Path(".data/formfusion.sqlite3")
    ml_request_timeout_seconds: float = Field(default=30.0, ge=1.0, le=300.0)

    @model_validator(mode="after")
    def validate_production_secrets(self) -> "Settings":
        if self.environment == "production":
            if len(self.ml_service_key) < 32:
                raise ValueError("production requires a strong FORMFUSION_ML_SERVICE_KEY")
            if len(self.join_code_secret) < 32:
                raise ValueError("production requires a strong FORMFUSION_JOIN_CODE_SECRET")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
