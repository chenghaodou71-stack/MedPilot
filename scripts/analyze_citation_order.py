"""Audit in-text citation first-appearance order for the MedPilot thesis.

The audit intentionally ignores bibliography entries and date-like brackets
such as ``[2026-08-26]``. It writes a JSON report containing the old-to-new
mapping that can be consumed by the document rewrite script.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

from docx import Document
from docx.document import Document as DocxDocument
from docx.table import Table
from docx.text.paragraph import Paragraph


CITATION = re.compile(r"\[(\d{1,2})\]")
DATE_BRACKET = re.compile(r"\[\d{4}-\d{2}-\d{2}\]")


def iter_blocks(doc: DocxDocument):
    """Yield top-level paragraphs/tables in document order."""
    body = doc.element.body
    for child in body.iterchildren():
        if child.tag.endswith("}p"):
            yield Paragraph(child, doc)
        elif child.tag.endswith("}tbl"):
            yield Table(child, doc)


def table_text(table: Table) -> str:
    return "\n".join(
        "\n".join(p.text for p in cell.paragraphs)
        for row in table.rows
        for cell in row.cells
    )


def audit(path: Path) -> dict:
    doc = Document(path)
    in_refs = False
    first: dict[int, dict] = {}
    occurrences: list[dict] = []
    block_index = 0
    for block in iter_blocks(doc):
        # Citations in this thesis are prose-level bracket labels. Code
        # listings are stored in tables and contain ordinary list indexes
        # such as ``[0]``/``[2]``; skip tables to avoid mistaking those for
        # literature references.
        if not isinstance(block, Paragraph):
            block_index += 1
            continue
        text = block.text
        stripped = text.strip()
        if isinstance(block, Paragraph) and stripped == "参考文献":
            in_refs = True
        if not in_refs:
            for match in CITATION.finditer(text):
                # A numeric citation must be a one/two digit label. The
                # explicit date check documents why date brackets are safe;
                # four-digit dates never match CITATION in the first place.
                old = int(match.group(1))
                if old < 1 or old > 46:
                    continue
                occurrence = {
                    "old": old,
                    "block": block_index,
                    "context": text[max(0, match.start() - 80) : match.end() + 80],
                }
                occurrences.append(occurrence)
                first.setdefault(old, occurrence)
        block_index += 1

    first_order = sorted(first, key=lambda old: first[old]["block"])
    mapping: dict[int, int] = {
        old: new for new, old in enumerate(first_order, start=1)
    }
    # Keep uncited bibliography entries, if any, in their existing order so
    # every source remains represented by a contiguous [1]-[46] label.
    all_old = list(range(1, 47))
    next_number = len(mapping) + 1
    for old in all_old:
        if old not in mapping:
            mapping[old] = next_number
            next_number += 1

    return {
        "path": str(path),
        "occurrence_count": len(occurrences),
        "unique_used": len(first_order),
        "first_order_old": first_order,
        "old_to_new": {str(old): new for old, new in mapping.items()},
        "new_to_old": {str(new): old for old, new in mapping.items()},
        "uncited_old": [old for old in all_old if old not in first],
        "occurrences": occurrences,
    }


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: analyze_citation_order.py INPUT.docx [REPORT.json]")
        return 2
    source = Path(sys.argv[1])
    report = audit(source)
    destination = Path(sys.argv[2]) if len(sys.argv) > 2 else source.with_suffix(".citation-order.json")
    destination.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("first_order_old", "old_to_new", "uncited_old")}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
