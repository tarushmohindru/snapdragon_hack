import asyncio
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import WebSocket


class ConnectionManager:
    def __init__(self) -> None:
        self._connections: dict[str, set[WebSocket]] = {}
        self._subscribers: dict[str, set[asyncio.Queue[dict[str, object]]]] = {}
        self._lock = asyncio.Lock()

    async def register(self, session_id: str, websocket: WebSocket) -> None:
        async with self._lock:
            self._connections.setdefault(session_id, set()).add(websocket)

    async def unregister(self, session_id: str, websocket: WebSocket) -> None:
        async with self._lock:
            connections = self._connections.get(session_id)
            if connections is None:
                return
            connections.discard(websocket)
            if not connections:
                self._connections.pop(session_id, None)

    async def broadcast(self, session_id: str, payload: dict[str, object]) -> None:
        async with self._lock:
            connections = list(self._connections.get(session_id, set()))
            subscribers = list(self._subscribers.get(session_id, set()))
        for queue in subscribers:
            if queue.full():
                try:
                    queue.get_nowait()
                except asyncio.QueueEmpty:
                    pass
            queue.put_nowait(payload)
        failed: list[WebSocket] = []
        for connection in connections:
            try:
                await connection.send_json(payload)
            except Exception:
                failed.append(connection)
        for connection in failed:
            await self.unregister(session_id, connection)

    async def count(self, session_id: str) -> int:
        async with self._lock:
            return len(self._connections.get(session_id, set()))

    @asynccontextmanager
    async def subscribe(self, session_id: str) -> AsyncIterator[asyncio.Queue[dict[str, object]]]:
        queue: asyncio.Queue[dict[str, object]] = asyncio.Queue(maxsize=8)
        async with self._lock:
            self._subscribers.setdefault(session_id, set()).add(queue)
        try:
            yield queue
        finally:
            async with self._lock:
                subscribers = self._subscribers.get(session_id)
                if subscribers is not None:
                    subscribers.discard(queue)
                    if not subscribers:
                        self._subscribers.pop(session_id, None)
