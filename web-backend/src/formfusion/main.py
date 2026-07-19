from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import structlog
from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from formfusion.api.routes.health import router as health_router
from formfusion.api.routes.sessions import router as sessions_router
from formfusion.api.websocket import router as websocket_router
from formfusion.config import Settings, get_settings
from formfusion.domain.errors import DomainError
from formfusion.logging import configure_logging
from formfusion.repositories.sqlite import SqliteRepository
from formfusion.services.connections import ConnectionManager
from formfusion.services.ml_client import MlClient
from formfusion.services.runtime import RuntimeRegistry
from formfusion.services.sessions import SessionService


def create_app(settings: Settings | None = None) -> FastAPI:
    resolved = settings or get_settings()
    configure_logging()
    log = structlog.get_logger()

    @asynccontextmanager
    async def lifespan(app: FastAPI) -> AsyncIterator[None]:
        repository = SqliteRepository(resolved.database_path)
        await repository.initialize()
        app.state.settings = resolved
        app.state.sessions = SessionService(repository, resolved)
        app.state.runtimes = RuntimeRegistry(resolved)
        app.state.connections = ConnectionManager()
        app.state.ml = MlClient(resolved)
        log.info("application_started", environment=resolved.environment)
        yield
        await app.state.ml.close()
        log.info("application_stopped")

    application = FastAPI(title=resolved.app_name, version="1.0.0", lifespan=lifespan)
    application.add_middleware(
        CORSMiddleware,
        allow_origins=resolved.allowed_origins,
        allow_credentials=True,
        allow_methods=["GET", "POST", "PUT", "DELETE"],
        allow_headers=["Content-Type"],
    )

    @application.exception_handler(DomainError)
    async def domain_error_handler(_request: Request, exc: DomainError) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content={"error": {"code": exc.code, "message": str(exc)}},
        )

    application.include_router(health_router)
    application.include_router(sessions_router)
    application.include_router(websocket_router)
    return application


app = create_app()
