import os

from fastapi import Depends, FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from app.api.knowledge_routes import router as knowledge_router
from app.api.monitor_routes import (
    configure_shared_state as configure_monitor_shared_state,
    router as monitor_router,
)
from app.api.routes import (
    configure_shared_state as configure_routes_shared_state,
    router,
)
from app.config import parse_cors_origins, require_service_token, verify_service_token
from app.shared_state import RedisSharedState

_MAX_REQUEST_BYTES = 1024 * 1024
require_service_token()
shared_state = RedisSharedState.from_env()
configure_routes_shared_state(shared_state)
configure_monitor_shared_state(shared_state)


class RequestBodyLimitMiddleware:
    def __init__(self, app: ASGIApp, *, max_bytes: int) -> None:
        self.app = app
        self.max_bytes = max_bytes

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        headers = dict(scope.get("headers", []))
        content_length = headers.get(b"content-length")
        if content_length is not None:
            try:
                declared_size = int(content_length.decode("ascii"))
            except (UnicodeDecodeError, ValueError):
                await self._reject(scope, receive, send, 400, "invalid content-length")
                return
            if declared_size > self.max_bytes:
                await self._reject(scope, receive, send, 413, "request body too large")
                return

        messages: list[dict] = []
        actual_size = 0
        while True:
            message = await receive()
            messages.append(message)
            if message["type"] == "http.disconnect":
                break
            if message["type"] != "http.request":
                continue
            actual_size += len(message.get("body", b""))
            if actual_size > self.max_bytes:
                await self._reject(scope, receive, send, 413, "request body too large")
                return
            if not message.get("more_body", False):
                break

        position = 0

        async def replay() -> dict:
            nonlocal position
            if position < len(messages):
                message = messages[position]
                position += 1
                return message
            return await receive()

        await self.app(scope, replay, send)

    @staticmethod
    async def _reject(
        scope: Scope,
        receive: Receive,
        send: Send,
        status_code: int,
        detail: str,
    ) -> None:
        response = JSONResponse(status_code=status_code, content={"detail": detail})
        await response(scope, receive, send)

app = FastAPI(title="MedPilot AI Service", version="0.1.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=list(parse_cors_origins(os.getenv("MEDPILOT_CORS_ORIGINS"))),
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=[
        "Content-Type",
        "X-MedPilot-Service-Token",
        "X-MedPilot-Reviewer",
        "X-MedPilot-Filename",
    ],
)
app.add_middleware(RequestBodyLimitMiddleware, max_bytes=_MAX_REQUEST_BYTES)


internal_only = [Depends(verify_service_token)]
app.include_router(router, dependencies=internal_only)
app.include_router(knowledge_router, dependencies=internal_only)
app.include_router(monitor_router, dependencies=internal_only)


@app.on_event("shutdown")
async def close_shared_state() -> None:
    await shared_state.close()


@app.get("/")
async def root():
    return {"service": "medpilot-ai", "status": "running"}
