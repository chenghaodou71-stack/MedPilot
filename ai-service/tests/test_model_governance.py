from app.model_governance import load_model_manifest, model_governance_health


SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"


def _values(**overrides):
    values = {
        "CHAT_MODEL": "qwen2.5:7b",
        "EMBED_MODEL": "bge-m3",
        "MEDPILOT_MODEL_RELEASE_ID": "rel-1",
        "MEDPILOT_MODEL_VERSION": "7b-20260821",
        "MEDPILOT_MODEL_WEIGHT_SHA256": SHA,
        "MEDPILOT_MODEL_ARTIFACT_SIGNATURE": "ed25519-signature",
        "MEDPILOT_MODEL_SIGNATURE_ALGORITHM": "ed25519",
        "MEDPILOT_PROMPT_VERSION": "prompt-v1",
        "MEDPILOT_EMBEDDING_VERSION": "bge-m3-v1",
        "MEDPILOT_KNOWLEDGE_INDEX_VERSION": "index-v1",
        "MEDPILOT_MODEL_SCOPE": "controlled-pilot",
        "MEDPILOT_MODEL_RELEASE_STATUS": "FROZEN",
        "MEDPILOT_RUNTIME_MODE": "clinical",
    }
    values.update(overrides)
    return values


def test_clinical_manifest_requires_digest_and_approval():
    health = model_governance_health(_values())
    assert health["ok"] is True
    assert health["promotable"] is True

    missing = model_governance_health(_values(MEDPILOT_MODEL_WEIGHT_SHA256=""))
    assert missing["ok"] is False
    assert "weight_sha256" in missing["missing_fields"]


def test_development_mode_exposes_unregistered_marker_without_claiming_readiness():
    manifest = load_model_manifest({"CHAT_MODEL": "qwen2.5:7b"})
    assert manifest.release_id == "unregistered-dev"
    health = model_governance_health({"MEDPILOT_RUNTIME_MODE": "development"})
    assert health["ok"] is True
    assert health["promotable"] is False
