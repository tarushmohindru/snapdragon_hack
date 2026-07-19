from fastapi import APIRouter, Request
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest
from starlette.responses import Response

router = APIRouter(tags=["operations"])


@router.get("/health/live")
async def liveness() -> dict[str, str]:
    return {"status": "alive"}


@router.get("/health/ready")
async def readiness(request: Request) -> dict[str, str]:
    required = ("sessions", "runtimes", "connections", "ml")
    ready = all(hasattr(request.app.state, name) for name in required)
    ml_ready = await request.app.state.ml.health() if ready else False
    return {"status": "ready" if ready and ml_ready else "not_ready"}


@router.get("/metrics", include_in_schema=False)
async def metrics() -> Response:
    return Response(generate_latest(), media_type=CONTENT_TYPE_LATEST)
