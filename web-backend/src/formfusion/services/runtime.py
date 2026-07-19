import asyncio
from dataclasses import dataclass

from formfusion.config import Settings
from formfusion.services.synchronizer import FrameSynchronizer


@dataclass(slots=True)
class SessionRuntime:
    synchronizer: FrameSynchronizer


class RuntimeRegistry:
    """Holds only ephemeral frame-pairing state; all numerical work lives in ML."""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._runtimes: dict[str, SessionRuntime] = {}
        self._lock = asyncio.Lock()

    async def get(self, session_id: str) -> SessionRuntime:
        async with self._lock:
            runtime = self._runtimes.get(session_id)
            if runtime is None:
                runtime = SessionRuntime(
                    synchronizer=FrameSynchronizer(
                        self._settings.frame_queue_capacity,
                        self._settings.frame_sync_tolerance_ms,
                    )
                )
                self._runtimes[session_id] = runtime
            return runtime

    async def delete(self, session_id: str) -> None:
        async with self._lock:
            self._runtimes.pop(session_id, None)
