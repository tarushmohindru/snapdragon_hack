def test_create_join_status_close_and_delete(client) -> None:
    created = client.post("/api/v1/sessions", json={"exercise": "bicep_curls"})
    assert created.status_code == 201
    session = created.json()
    assert "host_token" not in session

    joined = client.post(
        f"/api/v1/sessions/{session['session_id']}/join",
        json={"join_code": session["join_code"], "device_id": "phone-a"},
    )
    assert joined.status_code == 200
    assert joined.json()["device_id"] == "phone-a"
    assert "device_token" not in joined.json()

    status = client.get(f"/api/v1/sessions/{session['session_id']}")
    assert status.status_code == 200
    assert status.json()["device_ids"] == ["phone-a"]

    closed = client.post(f"/api/v1/sessions/{session['session_id']}/close")
    assert closed.status_code == 200

    deleted = client.delete(f"/api/v1/sessions/{session['session_id']}")
    assert deleted.status_code == 204


def test_invalid_join_code_is_structured(client) -> None:
    session = client.post("/api/v1/sessions", json={}).json()
    response = client.post(
        f"/api/v1/sessions/{session['session_id']}/join",
        json={"join_code": "WRONG1", "device_id": "phone-a"},
    )
    assert response.status_code == 403
    assert response.json()["error"]["code"] == "invalid_join_code"


def test_device_can_stream_calibration_capture_to_ml(client) -> None:
    session = client.post("/api/v1/sessions", json={}).json()
    client.post(
        f"/api/v1/sessions/{session['session_id']}/join",
        json={"join_code": session["join_code"], "device_id": "phone-a"},
    )
    response = client.post(
        f"/api/v1/sessions/{session['session_id']}/calibration/images",
        data={"device_id": "phone-a", "pair_id": "capture-001"},
        files={"image": ("capture.png", b"\x89PNG\r\n\x1a\nplaceholder", "image/png")},
    )
    assert response.status_code == 201
    assert response.json()["complete_pairs"] == 1
