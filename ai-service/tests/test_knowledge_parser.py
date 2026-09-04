"""Internal TXT/MD/PDF parsing contract used by the Spring upload gateway."""
from __future__ import annotations

from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from app.rag.knowledge_parser import KnowledgeParseError, parse_knowledge_file
from main import app


AUTH = {"X-MedPilot-Service-Token": "test-service-token"}
client = TestClient(app, headers=AUTH)


@pytest.mark.unit
@pytest.mark.parametrize("filename", ["guidance.txt", "guidance.md"])
def test_text_and_markdown_are_utf8_normalized(filename):
    parsed = parse_knowledge_file(b"\xef\xbb\xbf  line one\r\n\r\nline two  ", filename)

    assert parsed.source_type == filename.rsplit(".", 1)[1]
    assert parsed.text == "line one\n\nline two"
    assert parsed.parsing_status == "completed"
    assert parsed.failure_summary == ""


@pytest.mark.unit
def test_pdf_extracts_text_from_every_text_page():
    class Page:
        def __init__(self, text):
            self._text = text

        def extract_text(self):
            return self._text

    class Reader:
        is_encrypted = False
        pages = [Page("第一页内容"), Page(None), Page("第二页内容")]

    with patch("app.rag.knowledge_parser.PdfReader", return_value=Reader()):
        parsed = parse_knowledge_file(b"%PDF-test", "guidance.pdf")

    assert parsed.source_type == "pdf"
    assert parsed.text == "第一页内容\n\n第二页内容"


@pytest.mark.unit
@pytest.mark.parametrize(
    ("content", "filename", "message"),
    [
        (b"data", "guidance.docx", "unsupported knowledge file type"),
        (b"\xff\xfe", "guidance.txt", "knowledge text must be UTF-8"),
        (b"text\x00binary", "guidance.txt", "knowledge text contains binary data"),
        (b"   ", "guidance.md", "knowledge file contains no text"),
    ],
)
def test_parser_rejects_unsupported_binary_or_empty_content(content, filename, message):
    with pytest.raises(KnowledgeParseError, match=message):
        parse_knowledge_file(content, filename)


@pytest.mark.unit
def test_parse_endpoint_returns_queryable_failure_state():
    response = client.post(
        "/knowledge/parse",
        content=b"not supported",
        headers={**AUTH, "X-MedPilot-Filename": "guidance.docx"},
    )

    assert response.status_code == 422
    assert response.json()["detail"] == {
        "source_type": "unknown",
        "parsing_status": "failed",
        "vector_status": "pending",
        "failure_summary": "unsupported knowledge file type",
    }


@pytest.mark.unit
def test_parse_endpoint_returns_text_contract_for_spring():
    response = client.post(
        "/knowledge/parse",
        content="咳嗽需要记录持续时间。".encode("utf-8"),
        headers={**AUTH, "X-MedPilot-Filename": "guidance.txt"},
    )

    assert response.status_code == 200
    assert response.json() == {
        "source_type": "txt",
        "parsing_status": "completed",
        "vector_status": "pending",
        "failure_summary": "",
        "text": "咳嗽需要记录持续时间。",
        "char_count": 11,
    }
