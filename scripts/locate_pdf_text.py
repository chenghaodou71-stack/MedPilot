from __future__ import annotations

import argparse
from pathlib import Path

from pypdf import PdfReader


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("pdf", type=Path)
    parser.add_argument("patterns", nargs="+")
    args = parser.parse_args()
    reader = PdfReader(str(args.pdf))
    for page_number, page in enumerate(reader.pages, start=1):
        text = page.extract_text() or ""
        hits = [pattern for pattern in args.patterns if pattern in text]
        if hits:
            print(f"page={page_number}: " + ", ".join(hits))


if __name__ == "__main__":
    main()
