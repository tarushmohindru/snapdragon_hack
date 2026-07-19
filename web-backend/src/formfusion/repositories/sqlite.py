import json
import sqlite3
from datetime import datetime
from pathlib import Path

import anyio

from formfusion.domain.errors import SessionNotFound
from formfusion.domain.models import SessionRecord


class SqliteRepository:
    """Durable repository. Connections are short-lived so worker threads never share handles."""

    def __init__(self, path: Path) -> None:
        self._path = path

    async def initialize(self) -> None:
        self._path.parent.mkdir(parents=True, exist_ok=True)
        await anyio.to_thread.run_sync(self._initialize_sync)

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self._path, timeout=10)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA journal_mode=WAL")
        connection.execute("PRAGMA foreign_keys=ON")
        return connection

    def _initialize_sync(self) -> None:
        with self._connect() as db:
            db.executescript(
                """
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id TEXT PRIMARY KEY,
                    join_code_digest TEXT NOT NULL,
                    exercise TEXT NOT NULL,
                    created_at TEXT NOT NULL,
                    expires_at TEXT NOT NULL,
                    calibrated INTEGER NOT NULL DEFAULT 0,
                    calibration_reprojection_error REAL,
                    latest_result_at TEXT,
                    ended_at TEXT
                );
                CREATE TABLE IF NOT EXISTS devices (
                    session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
                    device_id TEXT NOT NULL,
                    device_name TEXT NOT NULL,
                    joined_at TEXT NOT NULL,
                    PRIMARY KEY (session_id, device_id)
                );
                CREATE TABLE IF NOT EXISTS pose_results (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL REFERENCES sessions(session_id) ON DELETE CASCADE,
                    captured_at_ms INTEGER NOT NULL,
                    payload_json TEXT NOT NULL,
                    created_at TEXT NOT NULL
                );
                CREATE INDEX IF NOT EXISTS idx_pose_results_session_time
                    ON pose_results(session_id, captured_at_ms DESC);
                CREATE TABLE IF NOT EXISTS session_summaries (
                    session_id TEXT PRIMARY KEY REFERENCES sessions(session_id) ON DELETE CASCADE,
                    payload_json TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );
                """
            )

    @staticmethod
    def _record(row: sqlite3.Row, devices: set[str]) -> SessionRecord:
        return SessionRecord(
            session_id=row["session_id"],
            join_code_digest=row["join_code_digest"],
            exercise=row["exercise"],
            created_at=datetime.fromisoformat(row["created_at"]),
            expires_at=datetime.fromisoformat(row["expires_at"]),
            devices=devices,
            calibrated=bool(row["calibrated"]),
            calibration_reprojection_error=row["calibration_reprojection_error"],
            latest_result_at=(
                datetime.fromisoformat(row["latest_result_at"])
                if row["latest_result_at"]
                else None
            ),
            ended_at=datetime.fromisoformat(row["ended_at"]) if row["ended_at"] else None,
        )

    async def create(self, session: SessionRecord) -> None:
        await anyio.to_thread.run_sync(self._create_sync, session)

    def _create_sync(self, session: SessionRecord) -> None:
        with self._connect() as db:
            db.execute(
                """INSERT INTO sessions(
                    session_id, join_code_digest, exercise, created_at, expires_at
                ) VALUES (?, ?, ?, ?, ?)""",
                (
                    session.session_id,
                    session.join_code_digest,
                    session.exercise,
                    session.created_at.isoformat(),
                    session.expires_at.isoformat(),
                ),
            )

    async def get(self, session_id: str) -> SessionRecord:
        record = await anyio.to_thread.run_sync(self._get_sync, session_id)
        if record is None:
            raise SessionNotFound(f"session {session_id} was not found")
        return record

    def _get_sync(self, session_id: str) -> SessionRecord | None:
        with self._connect() as db:
            row = db.execute(
                "SELECT * FROM sessions WHERE session_id = ?", (session_id,)
            ).fetchone()
            if row is None:
                return None
            devices = {
                item["device_id"]
                for item in db.execute(
                    "SELECT device_id FROM devices WHERE session_id = ?", (session_id,)
                )
            }
            return self._record(row, devices)

    async def list_sessions(self, limit: int = 100) -> list[SessionRecord]:
        return await anyio.to_thread.run_sync(self._list_sync, limit)

    def _list_sync(self, limit: int) -> list[SessionRecord]:
        with self._connect() as db:
            rows = db.execute(
                "SELECT * FROM sessions ORDER BY created_at DESC LIMIT ?", (limit,)
            ).fetchall()
            return [
                self._record(
                    row,
                    {
                        item["device_id"]
                        for item in db.execute(
                            "SELECT device_id FROM devices WHERE session_id = ?",
                            (row["session_id"],),
                        )
                    },
                )
                for row in rows
            ]

    async def add_device(self, session_id: str, device_id: str, device_name: str) -> None:
        await anyio.to_thread.run_sync(self._add_device_sync, session_id, device_id, device_name)

    def _add_device_sync(self, session_id: str, device_id: str, device_name: str) -> None:
        with self._connect() as db:
            db.execute(
                """INSERT INTO devices(
                    session_id, device_id, device_name, joined_at
                ) VALUES (?, ?, ?, datetime('now'))
                ON CONFLICT(session_id, device_id)
                DO UPDATE SET device_name=excluded.device_name""",
                (session_id, device_id, device_name),
            )

    async def set_calibration(
        self, session_id: str, calibrated: bool, reprojection_error: float | None
    ) -> None:
        await anyio.to_thread.run_sync(
            self._execute,
            """UPDATE sessions
            SET calibrated = ?, calibration_reprojection_error = ?
            WHERE session_id = ?""",
            (int(calibrated), reprojection_error, session_id),
        )

    async def save_result(self, session_id: str, payload: dict[str, object]) -> None:
        await anyio.to_thread.run_sync(self._save_result_sync, session_id, payload)

    def _save_result_sync(self, session_id: str, payload: dict[str, object]) -> None:
        captured_at_ms = int(payload["captured_at_ms"])
        now = datetime.now().astimezone().isoformat()
        with self._connect() as db:
            db.execute(
                """INSERT INTO pose_results(
                    session_id, captured_at_ms, payload_json, created_at
                ) VALUES (?, ?, ?, ?)""",
                (session_id, captured_at_ms, json.dumps(payload, separators=(",", ":")), now),
            )
            db.execute(
                "UPDATE sessions SET latest_result_at = ? WHERE session_id = ?",
                (now, session_id),
            )

    async def results(self, session_id: str, limit: int = 1000) -> list[dict[str, object]]:
        return await anyio.to_thread.run_sync(self._results_sync, session_id, limit)

    def _results_sync(self, session_id: str, limit: int) -> list[dict[str, object]]:
        with self._connect() as db:
            rows = db.execute(
                """SELECT payload_json FROM pose_results
                WHERE session_id = ?
                ORDER BY captured_at_ms DESC LIMIT ?""",
                (session_id, limit),
            ).fetchall()
            return [json.loads(row["payload_json"]) for row in reversed(rows)]

    async def close(self, session_id: str, ended_at: datetime) -> None:
        await anyio.to_thread.run_sync(
            self._execute,
            "UPDATE sessions SET ended_at = ? WHERE session_id = ?",
            (ended_at.isoformat(), session_id),
        )

    async def save_summary(self, session_id: str, payload: dict[str, object]) -> None:
        await anyio.to_thread.run_sync(self._save_summary_sync, session_id, payload)

    def _save_summary_sync(self, session_id: str, payload: dict[str, object]) -> None:
        with self._connect() as db:
            db.execute(
                """INSERT INTO session_summaries(
                    session_id, payload_json, updated_at
                ) VALUES (?, ?, datetime('now'))
                ON CONFLICT(session_id) DO UPDATE SET
                    payload_json=excluded.payload_json,
                    updated_at=excluded.updated_at""",
                (session_id, json.dumps(payload, separators=(",", ":"))),
            )

    async def summary(self, session_id: str) -> dict[str, object] | None:
        return await anyio.to_thread.run_sync(self._summary_sync, session_id)

    def _summary_sync(self, session_id: str) -> dict[str, object] | None:
        with self._connect() as db:
            row = db.execute(
                "SELECT payload_json FROM session_summaries WHERE session_id = ?", (session_id,)
            ).fetchone()
            return json.loads(row["payload_json"]) if row else None

    async def delete(self, session_id: str) -> None:
        changed = await anyio.to_thread.run_sync(
            self._execute, "DELETE FROM sessions WHERE session_id = ?", (session_id,)
        )
        if changed == 0:
            raise SessionNotFound(f"session {session_id} was not found")

    def _execute(self, query: str, params: tuple[object, ...]) -> int:
        with self._connect() as db:
            cursor = db.execute(query, params)
            return cursor.rowcount
