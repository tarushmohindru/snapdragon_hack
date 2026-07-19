from fastapi import APIRouter, Depends, File, Form, Header, Response, UploadFile, status

from formfusion.api.dependencies import calibration, runtimes, sessions
from formfusion.contracts.common import ClientRole
from formfusion.contracts.http import (
    CalibrationCaptureResponse,
    CalibrationResponse,
    CreateSessionRequest,
    CreateSessionResponse,
    FinalizeCalibrationRequest,
    JoinSessionRequest,
    JoinSessionResponse,
    ProjectionCalibrationRequest,
    SessionStatusResponse,
)
from formfusion.services.calibration import CalibrationService
from formfusion.services.runtime import RuntimeRegistry
from formfusion.services.sessions import SessionService

router = APIRouter(prefix="/api/v1/sessions", tags=["sessions"])


@router.post("", response_model=CreateSessionResponse, status_code=status.HTTP_201_CREATED)
async def create_session(
    payload: CreateSessionRequest,
    service: SessionService = Depends(sessions),
) -> CreateSessionResponse:
    return await service.create(payload.exercise)


@router.post("/{session_id}/join", response_model=JoinSessionResponse)
async def join_session(
    session_id: str,
    payload: JoinSessionRequest,
    service: SessionService = Depends(sessions),
) -> JoinSessionResponse:
    return await service.join(session_id, payload.join_code, payload.device_id)


@router.get("/{session_id}", response_model=SessionStatusResponse)
async def session_status(
    session_id: str,
    service: SessionService = Depends(sessions),
) -> SessionStatusResponse:
    return await service.status(session_id)


@router.put("/{session_id}/calibration/projections", response_model=CalibrationResponse)
async def configure_projection_calibration(
    session_id: str,
    payload: ProjectionCalibrationRequest,
    authorization: str | None = Header(default=None),
    service: SessionService = Depends(sessions),
    registry: RuntimeRegistry = Depends(runtimes),
) -> CalibrationResponse:
    await service.authorize_host(session_id, authorization)
    session = await service.get_record(session_id)
    if not {payload.device_a, payload.device_b}.issubset(session.devices):
        from fastapi import HTTPException

        raise HTTPException(
            status_code=409, detail="calibration devices must first join the session"
        )
    await registry.configure_calibration(session_id, payload)
    session.calibrated = True
    quality = "unknown"
    if payload.reprojection_error is not None:
        quality = "good" if payload.reprojection_error <= 1.0 else "poor"
    return CalibrationResponse(
        session_id=session_id,
        calibrated=True,
        quality=quality,
        reprojection_error=payload.reprojection_error,
    )


@router.post(
    "/{session_id}/calibration/images",
    response_model=CalibrationCaptureResponse,
    status_code=status.HTTP_201_CREATED,
)
async def upload_calibration_capture(
    session_id: str,
    device_id: str = Form(),
    pair_id: str = Form(),
    image: UploadFile = File(),
    authorization: str | None = Header(default=None),
    service: SessionService = Depends(sessions),
    calibration_service: CalibrationService = Depends(calibration),
) -> CalibrationCaptureResponse:
    claims = await service.authorize_client(session_id, authorization)
    session = await service.get_record(session_id)
    if device_id not in session.devices:
        from fastapi import HTTPException

        raise HTTPException(status_code=409, detail="device must first join the session")
    if claims.role is ClientRole.DEVICE and claims.device_id != device_id:
        from formfusion.domain.errors import Unauthorized

        raise Unauthorized("device token cannot upload another device's capture")
    captures, complete_pairs = await calibration_service.save_capture(
        session_id,
        device_id,
        pair_id,
        image,
    )
    return CalibrationCaptureResponse(
        session_id=session_id,
        device_id=device_id,
        pair_id=pair_id,
        captures_for_device=captures,
        complete_pairs=complete_pairs,
    )


@router.post("/{session_id}/calibration/finalize", response_model=CalibrationResponse)
async def finalize_calibration(
    session_id: str,
    payload: FinalizeCalibrationRequest,
    authorization: str | None = Header(default=None),
    service: SessionService = Depends(sessions),
    registry: RuntimeRegistry = Depends(runtimes),
    calibration_service: CalibrationService = Depends(calibration),
) -> CalibrationResponse:
    await service.authorize_host(session_id, authorization)
    session = await service.get_record(session_id)
    if not {payload.device_a, payload.device_b}.issubset(session.devices):
        from fastapi import HTTPException

        raise HTTPException(status_code=409, detail="calibration devices must first join")
    result = await calibration_service.finalize(session_id, payload)
    projection_request = ProjectionCalibrationRequest(
        device_a=payload.device_a,
        device_b=payload.device_b,
        projection_a=result.projection_a,
        projection_b=result.projection_b,
        reprojection_error=result.reprojection_error,
    )
    await registry.configure_calibration(session_id, projection_request)
    session.calibrated = True
    return CalibrationResponse(
        session_id=session_id,
        calibrated=True,
        quality="good" if result.reprojection_error <= 1.0 else "poor",
        reprojection_error=result.reprojection_error,
    )


@router.delete("/{session_id}", status_code=status.HTTP_204_NO_CONTENT)
async def delete_session(
    session_id: str,
    authorization: str | None = Header(default=None),
    service: SessionService = Depends(sessions),
    registry: RuntimeRegistry = Depends(runtimes),
    calibration_service: CalibrationService = Depends(calibration),
) -> Response:
    await service.authorize_host(session_id, authorization)
    await service.delete(session_id)
    await registry.delete(session_id)
    await calibration_service.delete_session(session_id)
    return Response(status_code=status.HTTP_204_NO_CONTENT)
