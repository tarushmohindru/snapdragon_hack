from typing import cast

from fastapi import Request

from formfusion.services.connections import ConnectionManager
from formfusion.services.ml_client import MlClient
from formfusion.services.runtime import RuntimeRegistry
from formfusion.services.sessions import SessionService


def sessions(request: Request) -> SessionService:
    return cast(SessionService, request.app.state.sessions)


def runtimes(request: Request) -> RuntimeRegistry:
    return cast(RuntimeRegistry, request.app.state.runtimes)


def connections(request: Request) -> ConnectionManager:
    return cast(ConnectionManager, request.app.state.connections)


def ml_client(request: Request) -> MlClient:
    return cast(MlClient, request.app.state.ml)
