import asyncio
import json
from collections.abc import AsyncIterator

from fastapi import APIRouter, Depends, File, Form, Query, Response, UploadFile, status
from fastapi.responses import StreamingResponse

from formfusion.api.dependencies import connections, ml_client, runtimes, sessions
from formfusion.contracts.http import (
    AiResponse,
    CalibrationResponse,
    CloseSessionResponse,
    CreateSessionRequest,
    CreateSessionResponse,
    FeedbackRequest,
    FinalizeCalibrationRequest,
    JoinSessionRequest,
    JoinSessionResponse,
    ProjectionCalibrationRequest,
    ResolveSessionResponse,
    SessionResultsResponse,
    SessionStatusResponse,
    SessionSummaryResponse,
)
from formfusion.services.connections import ConnectionManager
from formfusion.services.ml_client import MlClient
from formfusion.services.runtime import RuntimeRegistry
from formfusion.services.sessions import SessionService

router = APIRouter(prefix="/api/v1/sessions", tags=["sessions"])


@router.post("", response_model=CreateSessionResponse, status_code=status.HTTP_201_CREATED)
async def create_session(
    payload: CreateSessionRequest, service: SessionService = Depends(sessions)
) -> CreateSessionResponse:
    return await service.create(payload.exercise)


@router.get("", response_model=list[SessionStatusResponse])
async def list_sessions(
    limit: int = Query(default=100, ge=1, le=500),
    service: SessionService = Depends(sessions),
) -> list[SessionStatusResponse]:
    return await service.list(limit)


@router.get("/resolve/{join_code}", response_model=ResolveSessionResponse)
async def resolve_session(
    join_code: str,
    service: SessionService = Depends(sessions),
) -> ResolveSessionResponse:
    return ResolveSessionResponse(session_id=await service.resolve_join_code(join_code))


@router.post("/{session_id}/join", response_model=JoinSessionResponse)
async def join_session(
    session_id: str,
    payload: JoinSessionRequest,
    service: SessionService = Depends(sessions),
) -> JoinSessionResponse:
    return await service.join(
        session_id, payload.join_code, payload.device_id, payload.device_name
    )


@router.get("/{session_id}", response_model=SessionStatusResponse)
async def session_status(
    session_id: str, service: SessionService = Depends(sessions)
) -> SessionStatusResponse:
    return await service.status(session_id)


@router.get("/{session_id}/results", response_model=SessionResultsResponse)
async def session_results(
    session_id: str,
    limit: int = Query(default=1000, ge=1, le=100_000),
    service: SessionService = Depends(sessions),
) -> SessionResultsResponse:
    await service.get_record(session_id)
    return SessionResultsResponse(
        session_id=session_id,
        results=await service.repository.results(session_id, limit),
    )


@router.get("/{session_id}/events")
async def session_events(
    session_id: str,
    service: SessionService = Depends(sessions),
    manager: ConnectionManager = Depends(connections),
) -> StreamingResponse:
    await service.get_record(session_id)

    async def events() -> AsyncIterator[str]:
        async with manager.subscribe(session_id) as queue:
            while True:
                try:
                    payload = await asyncio.wait_for(queue.get(), timeout=15)
                    yield f"event: pose.result\ndata: {json.dumps(payload)}\n\n"
                except TimeoutError:
                    yield ": keepalive\n\n"

    return StreamingResponse(events(), media_type="text/event-stream")


@router.post(
    "/{session_id}/calibration/images",
    response_model=CalibrationResponse,
    status_code=status.HTTP_201_CREATED,
)
async def upload_calibration_capture(
    session_id: str,
    device_id: str = Form(),
    pair_id: str = Form(),
    image: UploadFile = File(),
    service: SessionService = Depends(sessions),
    ml: MlClient = Depends(ml_client),
) -> CalibrationResponse:
    record = await service.get_record(session_id, allow_closed=False)
    if device_id not in record.devices:
        from fastapi import HTTPException

        raise HTTPException(status_code=409, detail="device must first join the session")
    result = await ml.upload_capture(session_id, device_id, pair_id, image)
    await service.repository.set_calibration(
        session_id, result.calibrated, result.reprojection_error
    )
    return result


@router.get("/{session_id}/calibration", response_model=CalibrationResponse)
async def calibration_status(
    session_id: str,
    service: SessionService = Depends(sessions),
    ml: MlClient = Depends(ml_client),
) -> CalibrationResponse:
    await service.get_record(session_id)
    result = await ml.calibration_status(session_id)
    await service.repository.set_calibration(
        session_id, result.calibrated, result.reprojection_error
    )
    return result


@router.put("/{session_id}/calibration", response_model=CalibrationResponse)
async def import_calibration(
    session_id: str,
    payload: ProjectionCalibrationRequest,
    service: SessionService = Depends(sessions),
    ml: MlClient = Depends(ml_client),
) -> CalibrationResponse:
    await service.get_record(session_id, allow_closed=False)
    result = await ml.import_calibration(session_id, payload)
    await service.repository.set_calibration(session_id, True, result.reprojection_error)
    return result


@router.post("/{session_id}/calibration/finalize", response_model=CalibrationResponse)
async def finalize_calibration(
    session_id: str,
    payload: FinalizeCalibrationRequest,
    service: SessionService = Depends(sessions),
    ml: MlClient = Depends(ml_client),
) -> CalibrationResponse:
    record = await service.get_record(session_id, allow_closed=False)
    if not {payload.device_a, payload.device_b}.issubset(record.devices):
        from fastapi import HTTPException

        raise HTTPException(status_code=409, detail="calibration devices must first join")
    result = await ml.finalize_calibration(session_id, payload)
    await service.repository.set_calibration(session_id, True, result.reprojection_error)
    return result


@router.post("/{session_id}/feedback", response_model=AiResponse)
async def realtime_feedback(
    session_id: str,
    payload: FeedbackRequest,
    service: SessionService = Depends(sessions),
    ml: MlClient = Depends(ml_client),
) -> AiResponse:
    record = await service.get_record(session_id)
    results = await service.repository.results(session_id, 1)
    if not results:
        from fastapi import HTTPException

        raise HTTPException(status_code=409, detail="a pose result is required before feedback")
    latest = results[-1]
    return await ml.realtime_feedback(
        {
            "exercise": record.exercise,
            "primary_angle_degrees": latest.get("primary_angle_degrees"),
            "rep_count": latest.get("rep_count", 0),
            "movement_state": latest.get("movement_state", "unknown"),
            "form_quality": latest.get("form_quality", "unknown"),
            "language": payload.language,
        }
    )


@router.get("/{session_id}/summary", response_model=SessionSummaryResponse)
async def get_summary(
    session_id: str, service: SessionService = Depends(sessions)
) -> SessionSummaryResponse:
    return await service.build_summary(session_id)


@router.post("/{session_id}/summary", response_model=SessionSummaryResponse)
async def generate_summary(
    session_id: str,
    payload: FeedbackRequest,
    service: SessionService = Depends(sessions),
    ml: MlClient = Depends(ml_client),
) -> SessionSummaryResponse:
    summary = await service.build_summary(session_id)
    results = await service.repository.results(session_id, 100_000)
    notes = sorted(
        {
            str(result.get("form_quality"))
            for result in results
            if result.get("form_quality") not in {None, "good", "unknown"}
        }
    )
    ai = await ml.summary(
        {
            "exercise": summary.exercise,
            "total_reps": summary.total_reps,
            "duration_seconds": summary.duration_seconds,
            "angle_min": summary.angle_min,
            "angle_max": summary.angle_max,
            "form_notes": notes,
            "language": payload.language,
        }
    )
    await service.repository.save_summary(session_id, ai.model_dump(mode="json"))
    return summary.model_copy(update={"ai_summary": ai.text})


@router.post("/{session_id}/close", response_model=CloseSessionResponse)
async def close_session(
    session_id: str,
    service: SessionService = Depends(sessions),
    registry: RuntimeRegistry = Depends(runtimes),
) -> CloseSessionResponse:
    result = await service.close(session_id)
    await registry.delete(session_id)
    return result


@router.delete("/{session_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_session(
    session_id: str,
    service: SessionService = Depends(sessions),
    registry: RuntimeRegistry = Depends(runtimes),
    ml: MlClient = Depends(ml_client),
) -> Response:
    await service.delete(session_id)
    await registry.delete(session_id)
    await ml.delete_session(session_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
