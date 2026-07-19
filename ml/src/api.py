from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import anyio
import structlog
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile, status

from src.contracts import (
    AiResponse,
    CalibrationStatus,
    FinalizeCalibrationRequest,
    ProjectionCalibration,
    RealtimeFeedbackRequest,
    ReconstructionRequest,
    ReconstructionResult,
    SessionSummaryRequest,
)
from src.llm.feedback import AiNotConfigured, get_realtime_status, get_session_summary
from src.pipeline.calibration_store import CalibrationStore
from src.pipeline.registry import PipelineRegistry
from src.settings import Settings, get_settings

log = structlog.get_logger()


def require_service_key(
    x_ml_service_key: str | None = Header(default=None),
    settings: Settings = Depends(get_settings),
) -> None:
    if x_ml_service_key != settings.service_key:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED, detail="invalid ML service key"
        )


@asynccontextmanager
async def lifespan(application: FastAPI) -> AsyncIterator[None]:
    settings = get_settings()
    application.state.settings = settings
    application.state.calibrations = CalibrationStore(settings.data_root / "calibration")
    application.state.pipelines = PipelineRegistry(settings.min_keypoint_confidence)
    log.info("ml_service_started", environment=settings.environment)
    yield
    log.info("ml_service_stopped")


app = FastAPI(
    title="FormFusion ML Service",
    version="1.0.0",
    lifespan=lifespan,
)


@app.get("/health/live", tags=["operations"])
async def live() -> dict[str, str]:
    return {"status": "alive"}


@app.get("/health/ready", tags=["operations"])
async def ready() -> dict[str, str]:
    return {"status": "ready"}


@app.post(
    "/v1/sessions/{session_id}/calibration/captures",
    dependencies=[Depends(require_service_key)],
    tags=["calibration"],
)
async def upload_capture(
    session_id: str,
    device_id: str = Form(),
    pair_id: str = Form(),
    image: UploadFile = File(),
) -> CalibrationStatus:
    settings: Settings = app.state.settings
    if image.content_type not in {"image/jpeg", "image/png", "image/webp"}:
        raise HTTPException(status_code=415, detail="capture must be JPEG, PNG, or WebP")
    content = await image.read(settings.max_capture_bytes + 1)
    await image.close()
    if len(content) > settings.max_capture_bytes:
        raise HTTPException(status_code=413, detail="capture exceeds configured size limit")
    captures, complete = await anyio.to_thread.run_sync(
        app.state.calibrations.save_capture,
        session_id,
        device_id,
        pair_id,
        content,
    )
    counts, _, calibration = app.state.calibrations.status(session_id)
    counts[device_id] = captures
    return CalibrationStatus(
        session_id=session_id,
        captures_by_device=counts,
        complete_pairs=complete,
        calibrated=calibration is not None,
        calibration_id=calibration.calibration_id if calibration else None,
        reprojection_error=calibration.reprojection_error if calibration else None,
    )


@app.post(
    "/v1/sessions/{session_id}/calibration/finalize",
    dependencies=[Depends(require_service_key)],
    tags=["calibration"],
)
async def finalize_calibration(
    session_id: str,
    request: FinalizeCalibrationRequest,
) -> CalibrationStatus:
    try:
        calibration = await anyio.to_thread.run_sync(
            app.state.calibrations.finalize,
            session_id,
            request.device_a,
            request.device_b,
            (request.checkerboard_columns, request.checkerboard_rows),
            request.square_size,
            request.minimum_pairs,
        )
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error
    await app.state.pipelines.delete(session_id)
    counts, complete, _ = app.state.calibrations.status(session_id)
    return CalibrationStatus(
        session_id=session_id,
        captures_by_device=counts,
        complete_pairs=complete,
        calibrated=True,
        calibration_id=calibration.calibration_id,
        reprojection_error=calibration.reprojection_error,
    )


@app.put(
    "/v1/sessions/{session_id}/calibration",
    dependencies=[Depends(require_service_key)],
    tags=["calibration"],
)
async def import_calibration(
    session_id: str,
    calibration: ProjectionCalibration,
) -> CalibrationStatus:
    await anyio.to_thread.run_sync(app.state.calibrations.save, session_id, calibration)
    await app.state.pipelines.delete(session_id)
    counts, complete, _ = app.state.calibrations.status(session_id)
    return CalibrationStatus(
        session_id=session_id,
        captures_by_device=counts,
        complete_pairs=complete,
        calibrated=True,
        calibration_id=calibration.calibration_id,
        reprojection_error=calibration.reprojection_error,
    )


@app.get(
    "/v1/sessions/{session_id}/calibration",
    dependencies=[Depends(require_service_key)],
    tags=["calibration"],
)
async def calibration_status(session_id: str) -> CalibrationStatus:
    counts, complete, calibration = app.state.calibrations.status(session_id)
    return CalibrationStatus(
        session_id=session_id,
        captures_by_device=counts,
        complete_pairs=complete,
        calibrated=calibration is not None,
        calibration_id=calibration.calibration_id if calibration else None,
        reprojection_error=calibration.reprojection_error if calibration else None,
    )


@app.delete(
    "/v1/sessions/{session_id}",
    dependencies=[Depends(require_service_key)],
    tags=["operations"],
    status_code=204,
)
async def delete_session(session_id: str) -> None:
    await app.state.pipelines.delete(session_id)
    await anyio.to_thread.run_sync(app.state.calibrations.delete, session_id)


@app.post(
    "/v1/reconstruct",
    response_model=ReconstructionResult,
    dependencies=[Depends(require_service_key)],
    tags=["reconstruction"],
)
async def reconstruct(request: ReconstructionRequest) -> ReconstructionResult:
    calibration = app.state.calibrations.load(request.session_id)
    if calibration is None:
        raise HTTPException(status_code=409, detail="session calibration is required")
    pipeline = await app.state.pipelines.get(
        request.session_id,
        request.exercise,
        calibration,
    )
    try:
        return await anyio.to_thread.run_sync(pipeline.process, request)
    except ValueError as error:
        raise HTTPException(status_code=422, detail=str(error)) from error


@app.post(
    "/v1/ai/realtime",
    response_model=AiResponse,
    dependencies=[Depends(require_service_key)],
    tags=["ai"],
)
async def realtime_feedback(request: RealtimeFeedbackRequest) -> AiResponse:
    try:
        text, provider, model = await anyio.to_thread.run_sync(
            get_realtime_status,
            request,
            app.state.settings,
        )
        return AiResponse(text=text, provider=provider, model=model)
    except AiNotConfigured as error:
        raise HTTPException(status_code=503, detail=str(error)) from error


@app.post(
    "/v1/ai/summary",
    response_model=AiResponse,
    dependencies=[Depends(require_service_key)],
    tags=["ai"],
)
async def session_summary(request: SessionSummaryRequest) -> AiResponse:
    try:
        text, provider, model = await anyio.to_thread.run_sync(
            get_session_summary,
            request,
            app.state.settings,
        )
        return AiResponse(text=text, provider=provider, model=model)
    except AiNotConfigured as error:
        raise HTTPException(status_code=503, detail=str(error)) from error
