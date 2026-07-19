from typing import cast

from fastapi import Request

from formfusion.services.auth import TokenService
from formfusion.services.calibration import CalibrationService
from formfusion.services.connections import ConnectionManager
from formfusion.services.runtime import RuntimeRegistry
from formfusion.services.sessions import SessionService


def sessions(request: Request) -> SessionService:
    return cast(SessionService, request.app.state.sessions)


def tokens(request: Request) -> TokenService:
    return cast(TokenService, request.app.state.tokens)


def runtimes(request: Request) -> RuntimeRegistry:
    return cast(RuntimeRegistry, request.app.state.runtimes)


def connections(request: Request) -> ConnectionManager:
    return cast(ConnectionManager, request.app.state.connections)


def calibration(request: Request) -> CalibrationService:
    return cast(CalibrationService, request.app.state.calibration)
