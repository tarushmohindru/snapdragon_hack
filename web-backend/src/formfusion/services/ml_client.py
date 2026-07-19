from typing import Any

import httpx
from fastapi import UploadFile

from formfusion.config import Settings
from formfusion.contracts.http import (
    AiResponse,
    CalibrationResponse,
    FinalizeCalibrationRequest,
    ProjectionCalibrationRequest,
)
from formfusion.contracts.websocket import PoseResult
from formfusion.domain.errors import MlServiceRejected, MlServiceUnavailable
from formfusion.services.synchronizer import SyncedPair


class MlClient:
    def __init__(self, settings: Settings) -> None:
        self._client = httpx.AsyncClient(
            base_url=settings.ml_service_url.rstrip("/"),
            headers={"X-ML-Service-Key": settings.ml_service_key},
            timeout=settings.ml_request_timeout_seconds,
        )

    async def close(self) -> None:
        await self._client.aclose()

    async def _request(self, method: str, path: str, **kwargs: Any) -> dict[str, Any]:
        try:
            response = await self._client.request(method, path, **kwargs)
        except httpx.HTTPError as error:
            raise MlServiceUnavailable(f"ML service is unavailable: {error}") from error
        if response.is_error:
            detail = response.text
            try:
                detail = response.json().get("detail", detail)
            except ValueError:
                pass
            error = MlServiceRejected(f"ML service rejected the request: {detail}")
            error.status_code = response.status_code if response.status_code < 500 else 503
            raise error
        return response.json()

    async def health(self) -> bool:
        try:
            response = await self._client.get("/health/ready")
            return response.status_code == 200
        except httpx.HTTPError:
            return False

    async def reconstruct(self, session_id: str, exercise: str, pair: SyncedPair) -> PoseResult:
        def observation(frame: Any) -> dict[str, Any]:
            return {
                "device_id": frame.device_id,
                "frame_id": frame.frame_id,
                "captured_at_ms": frame.captured_at_ms,
                "image_width": frame.image.width,
                "image_height": frame.image.height,
                "rotation_degrees": frame.image.rotation_degrees,
                "mirrored": frame.image.mirrored,
                "track_id": frame.person.track_id,
                "landmarks": [point.model_dump() for point in frame.person.keypoints],
            }

        payload = {
            "schema_version": 1,
            "session_id": session_id,
            "exercise": exercise,
            "observations": [observation(pair.first), observation(pair.second)],
        }
        response = await self._request("POST", "/v1/reconstruct", json=payload)
        return PoseResult.model_validate(response)

    async def upload_capture(
        self, session_id: str, device_id: str, pair_id: str, image: UploadFile
    ) -> CalibrationResponse:
        content = await image.read()
        await image.close()
        payload = await self._request(
            "POST",
            f"/v1/sessions/{session_id}/calibration/captures",
            data={"device_id": device_id, "pair_id": pair_id},
            files={"image": (image.filename or "capture.jpg", content, image.content_type)},
        )
        return CalibrationResponse(
            session_id=session_id,
            calibrated=payload["calibrated"],
            complete_pairs=payload["complete_pairs"],
            calibration_id=payload.get("calibration_id"),
            reprojection_error=payload.get("reprojection_error"),
        )

    async def calibration_status(self, session_id: str) -> CalibrationResponse:
        payload = await self._request("GET", f"/v1/sessions/{session_id}/calibration")
        return CalibrationResponse(
            session_id=session_id,
            calibrated=payload["calibrated"],
            complete_pairs=payload["complete_pairs"],
            calibration_id=payload.get("calibration_id"),
            reprojection_error=payload.get("reprojection_error"),
        )

    async def finalize_calibration(
        self, session_id: str, request: FinalizeCalibrationRequest
    ) -> CalibrationResponse:
        payload = await self._request(
            "POST",
            f"/v1/sessions/{session_id}/calibration/finalize",
            json=request.model_dump(mode="json"),
        )
        return CalibrationResponse(
            session_id=session_id,
            calibrated=payload["calibrated"],
            complete_pairs=payload["complete_pairs"],
            calibration_id=payload.get("calibration_id"),
            reprojection_error=payload.get("reprojection_error"),
        )

    async def import_calibration(
        self, session_id: str, request: ProjectionCalibrationRequest
    ) -> CalibrationResponse:
        payload = await self._request(
            "PUT",
            f"/v1/sessions/{session_id}/calibration",
            json=request.model_dump(mode="json"),
        )
        return CalibrationResponse(
            session_id=session_id,
            calibrated=payload["calibrated"],
            complete_pairs=payload["complete_pairs"],
            calibration_id=payload.get("calibration_id"),
            reprojection_error=payload.get("reprojection_error"),
        )

    async def realtime_feedback(self, payload: dict[str, object]) -> AiResponse:
        response = await self._request("POST", "/v1/ai/realtime", json=payload)
        return AiResponse.model_validate(response)

    async def summary(self, payload: dict[str, object]) -> AiResponse:
        response = await self._request("POST", "/v1/ai/summary", json=payload)
        return AiResponse.model_validate(response)

    async def delete_session(self, session_id: str) -> None:
        try:
            response = await self._client.delete(f"/v1/sessions/{session_id}")
        except httpx.HTTPError as error:
            raise MlServiceUnavailable(f"ML service is unavailable: {error}") from error
        if response.is_error:
            raise MlServiceRejected("ML service could not delete session state")
