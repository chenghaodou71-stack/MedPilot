"""Bounded text extraction for TXT, Markdown and text-based PDF files."""
from __future__ import annotations

from dataclasses import dataclass
from io import BytesIO
from pathlib import Path

try:
    from pypdf import PdfReader
except ImportError:  # pragma: no cover - production dependency is pinned.
    PdfReader = None  # type: ignore[assignment]

_MAX_FILE_BYTES = 1024 * 1024
_MAX_TEXT_CHARS = 200_000
_SOURCE_TYPES = {".txt": "txt", ".md": "md", ".pdf": "pdf"}


class KnowledgeParseError(ValueError):
    pass


@dataclass(frozen=True)
class ParsedKnowledge:
    source_type: str
    text: str
    parsing_status: str = "completed"
    failure_summary: str = ""


def normalize_knowledge_text(text: str) -> str:
    normalized = text.replace("\r\n", "\n").replace("\r", "\n").strip()
    normalized = "\n".join(line.rstrip() for line in normalized.split("\n"))
    if not normalized:
        raise KnowledgeParseError("knowledge file contains no text")
    if "\x00" in normalized:
        raise KnowledgeParseError("knowledge text contains binary data")
    if len(normalized) > _MAX_TEXT_CHARS:
        raise KnowledgeParseError("knowledge text is too large")
    return normalized


def source_type_for_filename(filename: str) -> str:
    return _SOURCE_TYPES.get(Path(filename).suffix.lower(), "unknown")


def parse_knowledge_file(content: bytes, filename: str) -> ParsedKnowledge:
    source_type = source_type_for_filename(filename)
    if source_type == "unknown":
        raise KnowledgeParseError("unsupported knowledge file type")
    if not content:
        raise KnowledgeParseError("knowledge file contains no text")
    if len(content) > _MAX_FILE_BYTES:
        raise KnowledgeParseError("knowledge file is too large")

    if source_type in {"txt", "md"}:
        try:
            text = content.decode("utf-8-sig")
        except UnicodeDecodeError as exc:
            raise KnowledgeParseError("knowledge text must be UTF-8") from exc
    else:
        text = _extract_pdf_text(content)
    return ParsedKnowledge(source_type=source_type, text=normalize_knowledge_text(text))


def _extract_pdf_text(content: bytes) -> str:
    if PdfReader is None:
        raise KnowledgeParseError("PDF parser is unavailable")
    try:
        reader = PdfReader(BytesIO(content))
        if reader.is_encrypted:
            raise KnowledgeParseError("encrypted PDF is not supported")
        return "\n\n".join(
            text.strip()
            for page in reader.pages
            if (text := page.extract_text()) and text.strip()
        )
    except KnowledgeParseError:
        raise
    except Exception as exc:
        raise KnowledgeParseError("PDF text extraction failed") from exc
