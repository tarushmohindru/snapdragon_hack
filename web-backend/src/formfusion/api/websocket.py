import time
import uuid

import structlog
from fastapi import APIRouter, WebSocket, WebSocketDisconnect, status
from pydantic import ValidationError

from formfusion.contracts.common import ClientRole
from formfusion.contracts.websocket import (
    ClientPing,
    DeviceHello,
    ErrorMessage,
    FrameAck,
    PoseFrame,
    StatusMessage,
)
from formfusion.domain.errors import DomainError, Unauthorized
from formfusion.metrics import (
    ACTIVE_WEBSOCKETS,
    FRAMES_DROPPED,
    FRAMES_PAIRED,
    FRAMES_RECEIVED,
    PAIRING_DELTA_MS,
    PIPELINE_SECONDS,
)

router = APIRouter()
log = structlog.get_logger()


async def _send_error(websocket: WebSocket, code: str, message: str) -> None:
    await websocket.send_json(ErrorMessage(code=code, message=message).model_dump(mode="json"))


@router.websocket("/api/v1/ws/sessions/{session_id}")
async def session_websocket(websocket: WebSocket, session_id: str) -> None:
    sessions = websocket.app.state.sessions
    registry = websocket.app.state.runtimes
    manager = websocket.app.state.connections
    ml = websocket.app.state.ml
    connection_id = str(uuid.uuid4())

    try:
        record = await sessions.get_record(session_id, allow_closed=False)
    except DomainError:
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
        return

    await websocket.accept()
    await manager.register(session_id, websocket)
    ACTIVE_WEBSOCKETS.inc()

    try:
        hello = DeviceHello.model_validate(await websocket.receive_json())
        if hello.session_id != session_id:
            raise Unauthorized("hello session does not match URL")
        if hello.role is ClientRole.DEVICE:
            if hello.device_id is None or hello.device_id not in record.devices:
                raise Unauthorized("device must join the session before streaming")
        elif hello.device_id is not None:
            raise Unauthorized("dashboard and host connections do not send a device_id")

        log.info(
            "websocket_connected",
            connection_id=connection_id,
            session_id=session_id,
            role=hello.role,
            device_id=hello.device_id,
        )
        await websocket.send_json(
            StatusMessage(
                session_id=session_id,
                status="connected",
                connected_devices=len(record.devices),
                calibrated=record.calibrated,
            ).model_dump(mode="json")
        )

        while True:
            raw = await websocket.receive_json()
            message_type = raw.get("type") if isinstance(raw, dict) else None
            if message_type == "ping":
                ClientPing.model_validate(raw)
                await websocket.send_json({"schema_version": 1, "type": "pong"})
                continue
            if message_type != "pose.frame":
                await _send_error(
                    websocket, "unsupported_message", "unsupported WebSocket message type"
                )
                continue
            if hello.role is not ClientRole.DEVICE or hello.device_id is None:
                await _send_error(
                    websocket, "forbidden", "only joined devices may send pose frames"
                )
                continue

            frame = PoseFrame.model_validate(raw)
            if frame.session_id != session_id or frame.device_id != hello.device_id:
                raise Unauthorized("frame identity does not match the connection hello")

            FRAMES_RECEIVED.inc()
            await manager.broadcast(
                session_id,
                {
                    "schema_version": 1,
                    "type": "pose.preview",
                    "session_id": session_id,
                    "device_id": frame.device_id,
                    "frame_id": frame.frame_id,
                    "captured_at_ms": frame.captured_at_ms,
                    "image": frame.image.model_dump(mode="json"),
                    "keypoints": [
                        point.model_dump(mode="json") for point in frame.person.keypoints
                    ],
                },
            )
            runtime = await registry.get(session_id)
            outcome = runtime.synchronizer.push(frame)
            if outcome.dropped_frame_ids:
                FRAMES_DROPPED.inc(len(outcome.dropped_frame_ids))
            if outcome.pair is None:
                await websocket.send_json(
                    FrameAck(frame_id=frame.frame_id, status="queued").model_dump(mode="json")
                )
                continue

            FRAMES_PAIRED.inc()
            PAIRING_DELTA_MS.observe(outcome.pair.delta_ms)
            await websocket.send_json(
                FrameAck(frame_id=frame.frame_id, status="paired").model_dump(mode="json")
            )
            started = time.perf_counter()
            try:
                fresh_record = await sessions.get_record(session_id, allow_closed=False)
                result = await ml.reconstruct(session_id, fresh_record.exercise, outcome.pair)
            finally:
                PIPELINE_SECONDS.observe(time.perf_counter() - started)
            payload = result.model_dump(mode="json")
            await sessions.repository.save_result(session_id, payload)
            await manager.broadcast(session_id, payload)

    except ValidationError as exc:
        await _send_error(websocket, "validation_error", str(exc))
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
    except DomainError as exc:
        await _send_error(websocket, exc.code, str(exc))
        await websocket.close(code=status.WS_1008_POLICY_VIOLATION)
    except WebSocketDisconnect:
        pass
    except Exception:
        log.exception("websocket_failure", connection_id=connection_id, session_id=session_id)
        try:
            await websocket.close(code=status.WS_1011_INTERNAL_ERROR)
        except RuntimeError:
            pass
    finally:
        await manager.unregister(session_id, websocket)
        ACTIVE_WEBSOCKETS.dec()
        log.info("websocket_disconnected", connection_id=connection_id, session_id=session_id)
