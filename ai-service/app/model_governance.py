"""Fail-closed runtime model-release metadata.

The AI service cannot attest to a weight digest by inspecting an Ollama tag
alone.  Hospital deployments therefore inject the signed release manifest and
the service exposes its validation state through readiness.  Development keeps
an explicit unregistered marker; clinical mode never does.
"""
from __future__ import annotations

import os
import re
from dataclasses import asdict, dataclass
from collections.abc import Mapping

_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")


@dataclass(frozen=True)
class ModelReleaseManifest:
    release_id: str
    model_name: str
    model_version: str
    weight_sha256: str
    artifact_signature: str
    signature_algorithm: str
    prompt_version: str
    embedding_version: str
    knowledge_index_version: str
    scope: str
    status: str

    def missing_fields(self) -> tuple[str, ...]:
        required = (
            "release_id", "model_name", "model_version", "weight_sha256",
            "artifact_signature", "signature_algorithm", "prompt_version",
            "embedding_version", "knowledge_index_version", "scope",
        )
        return tuple(name for name in required if not getattr(self, name).strip())

    def valid_digest(self) -> bool:
        return bool(_SHA256.fullmatch(self.weight_sha256.strip()))

    def is_promotable(self) -> bool:
        return not self.missing_fields() and self.valid_digest() and self.status in {"APPROVED", "FROZEN"}

    def as_dict(self) -> dict[str, object]:
        return asdict(self)


def _value(values: Mapping[str, str], key: str, default: str = "") -> str:
    return str(values.get(key, default) or "").strip()


def load_model_manifest(values: Mapping[str, str] | None = None) -> ModelReleaseManifest:
    env = os.environ if values is None else values
    return ModelReleaseManifest(
        release_id=_value(env, "MEDPILOT_MODEL_RELEASE_ID", "unregistered-dev"),
        model_name=_value(env, "CHAT_MODEL", "qwen2.5:7b"),
        model_version=_value(env, "MEDPILOT_MODEL_VERSION", "dev"),
        weight_sha256=_value(env, "MEDPILOT_MODEL_WEIGHT_SHA256", ""),
        artifact_signature=_value(env, "MEDPILOT_MODEL_ARTIFACT_SIGNATURE", ""),
        signature_algorithm=_value(env, "MEDPILOT_MODEL_SIGNATURE_ALGORITHM", ""),
        prompt_version=_value(env, "MEDPILOT_PROMPT_VERSION", "dev"),
        embedding_version=_value(env, "MEDPILOT_EMBEDDING_VERSION", _value(env, "EMBED_MODEL", "bge-m3")),
        knowledge_index_version=_value(env, "MEDPILOT_KNOWLEDGE_INDEX_VERSION", "unregistered-dev"),
        scope=_value(env, "MEDPILOT_MODEL_SCOPE", "development only"),
        status=_value(env, "MEDPILOT_MODEL_RELEASE_STATUS", "DRAFT").upper(),
    )


def model_governance_health(values: Mapping[str, str] | None = None) -> dict[str, object]:
    env = os.environ if values is None else values
    manifest = load_model_manifest(env)
    clinical_mode = _value(env, "MEDPILOT_RUNTIME_MODE", "development").lower() in {"clinical", "production"}
    missing = manifest.missing_fields()
    digest_ok = manifest.valid_digest()
    promotable = manifest.is_promotable()
    ready = promotable if clinical_mode else True
    return {
        "ok": ready,
        "status": "ready" if ready else "unregistered_or_unapproved",
        "clinical_mode": clinical_mode,
        "promotable": promotable,
        "missing_fields": list(missing),
        "weight_digest_valid": digest_ok,
        "manifest": manifest.as_dict(),
    }
