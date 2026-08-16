"""Validated runtime configuration for the AI service."""
from __future__ import annotations

import os
import hmac
from collections.abc import Mapping
from pathlib import Path
from typing import Annotated

from fastapi import Header, HTTPException


def require_service_token(env: Mapping[str, str] | None = None) -> str:
    values = os.environ if env is None else env
    token = values.get("MEDPILOT_AI_SERVICE_TOKEN", "").strip()
    if not token:
        raise RuntimeError("MEDPILOT_AI_SERVICE_TOKEN must be configured")
    return token


def parse_cors_origins(value: str | None) -> tuple[str, ...]:
    if not value or not value.strip():
        return ()
    origins = tuple(origin.strip() for origin in value.split(",") if origin.strip())
    if "*" in origins:
        raise RuntimeError("CORS wildcard origins are not allowed")
    return origins


def validate_index_dir(path: Path, *, os_name: str = os.name) -> Path:
    """Retained compatibility hook; byte serialization supports Unicode paths."""
    return path


def verify_service_token(
    token: Annotated[str | None, Header(alias="X-MedPilot-Service-Token")] = None,
) -> None:
    expected = require_service_token()
    if token is None or not hmac.compare_digest(token, expected):
        raise HTTPException(status_code=401, detail="invalid internal service token")
