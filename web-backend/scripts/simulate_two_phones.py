"""Small manual load/demo client. Requires the backend dev dependencies."""

import argparse
import asyncio
import json
import time

import httpx
import websockets


async def main(base_url: str) -> None:
    async with httpx.AsyncClient(base_url=base_url) as client:
        session = (await client.post("/api/v1/sessions", json={})).raise_for_status().json()
        for device_id in ("phone-a", "phone-b"):
            response = await client.post(
                f"/api/v1/sessions/{session['session_id']}/join",
                json={"join_code": session["join_code"], "device_id": device_id},
            )
            response.raise_for_status()

        await client.put(
            f"/api/v1/sessions/{session['session_id']}/calibration",
            json={
                "calibration_id": "simulated-calibration",
                "device_a": "phone-a",
                "device_b": "phone-b",
                "camera_matrix_a": [[500.0, 0.0, 320.0], [0.0, 500.0, 240.0], [0.0, 0.0, 1.0]],
                "distortion_a": [0.0, 0.0, 0.0, 0.0, 0.0],
                "camera_matrix_b": [[500.0, 0.0, 320.0], [0.0, 500.0, 240.0], [0.0, 0.0, 1.0]],
                "distortion_b": [0.0, 0.0, 0.0, 0.0, 0.0],
                "rotation_a_to_b": [[1.0, 0.0, 0.0], [0.0, 1.0, 0.0], [0.0, 0.0, 1.0]],
                "translation_a_to_b": [20.0, 0.0, 0.0],
                "world_transform": [
                    [1.0, 0.0, 0.0, 0.0],
                    [0.0, 1.0, 0.0, 0.0],
                    [0.0, 0.0, 1.0, 0.0],
                    [0.0, 0.0, 0.0, 1.0],
                ],
                "units": "centimeters",
                "reprojection_error": 0.1,
            },
        )

    websocket_base = base_url.replace("http://", "ws://").replace("https://", "wss://")
    session_id = session["session_id"]

    async def phone(device_id: str, offset: float) -> None:
        uri = f"{websocket_base}/api/v1/ws/sessions/{session_id}"
        async with websockets.connect(uri) as socket:
            await socket.send(
                json.dumps(
                    {
                        "schema_version": 1,
                        "type": "device.hello",
                        "session_id": session_id,
                        "device_id": device_id,
                        "role": "device",
                    }
                )
            )
            print(device_id, await socket.recv())
            for frame_id in range(30):
                timestamp = int(time.time() * 1_000)
                payload = {
                    "schema_version": 1,
                    "type": "pose.frame",
                    "session_id": session_id,
                    "device_id": device_id,
                    "frame_id": frame_id,
                    "captured_at_ms": timestamp,
                    "image": {
                        "width": 640,
                        "height": 480,
                        "rotation_degrees": 0,
                        "mirrored": False,
                    },
                    "person": {
                        "track_id": 1,
                        "keypoints": [
                            {"id": 5, "x": 300.0 + offset, "y": 180.0, "confidence": 0.95},
                            {"id": 7, "x": 320.0 + offset, "y": 240.0, "confidence": 0.95},
                            {"id": 9, "x": 340.0 + offset, "y": 300.0, "confidence": 0.95},
                        ],
                    },
                }
                await socket.send(json.dumps(payload))
                print(device_id, await socket.recv())
                await asyncio.sleep(1 / 15)

    await asyncio.gather(phone("phone-a", 0.0), phone("phone-b", -24.0))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8000")
    args = parser.parse_args()
    asyncio.run(main(args.base_url))
