from unittest.mock import patch

from fastapi.testclient import TestClient

from main import app

client = TestClient(
    app,
    headers={"X-MedPilot-Service-Token": "test-service-token"},
)


def test_list_versions_marks_current_version():
    with patch("app.api.knowledge_routes.list_versions", return_value=[
        {"version": "v2", "created_at": "2026-07-30", "document_count": 2, "chunk_count": 4},
        {"version": "v1", "created_at": "2026-07-29", "document_count": 1, "chunk_count": 2},
    ]), patch("app.api.knowledge_routes.current_version", return_value="v1"):
        response = client.get("/knowledge/versions")

    assert response.status_code == 200
    assert response.json()["current"] == "v1"
    assert response.json()["versions"][1]["active"] is True


def test_activate_version_returns_404_when_missing():
    with patch("app.api.knowledge_routes.activate_version", side_effect=FileNotFoundError("missing")):
        response = client.post("/knowledge/versions/missing/activate")

    assert response.status_code == 404


def test_activate_version_returns_manifest():
    manifest = {"version": "v2", "document_count": 2, "chunk_count": 4}
    with patch("app.api.knowledge_routes.activate_version", return_value=manifest):
        response = client.post("/knowledge/versions/v2/activate")

    assert response.status_code == 200
    assert response.json()["active"] == "v2"
