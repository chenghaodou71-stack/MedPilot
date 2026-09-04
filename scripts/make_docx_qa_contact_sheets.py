from __future__ import annotations

import argparse
from pathlib import Path

from PIL import Image, ImageDraw


def natural_page_key(path: Path) -> int:
    return int(path.stem.split("-")[-1])


def build(input_dir: Path, output_dir: Path, batch_size: int = 12) -> None:
    pages = sorted(input_dir.glob("page-*.png"), key=natural_page_key)
    if not pages:
        raise RuntimeError(f"no page PNGs found in {input_dir}")
    output_dir.mkdir(parents=True, exist_ok=True)
    thumb_w, thumb_h = 330, 467
    cell_w, cell_h = 350, 505
    columns = 3
    for batch_start in range(0, len(pages), batch_size):
        batch = pages[batch_start : batch_start + batch_size]
        rows = (len(batch) + columns - 1) // columns
        sheet = Image.new("RGB", (cell_w * columns, cell_h * rows), "#E8ECEF")
        draw = ImageDraw.Draw(sheet)
        for index, page in enumerate(batch):
            image = Image.open(page).convert("RGB")
            image.thumbnail((thumb_w, thumb_h))
            x = (index % columns) * cell_w + (cell_w - image.width) // 2
            y = (index // columns) * cell_h + 28
            sheet.paste(image, (x, y))
            draw.text(((index % columns) * cell_w + 10, (index // columns) * cell_h + 7), page.stem, fill="black")
        first = natural_page_key(batch[0])
        last = natural_page_key(batch[-1])
        sheet.save(output_dir / f"pages-{first:02d}-{last:02d}.png", optimize=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input_dir", type=Path)
    parser.add_argument("output_dir", type=Path)
    parser.add_argument("--batch-size", type=int, default=12)
    args = parser.parse_args()
    build(args.input_dir, args.output_dir, args.batch_size)


if __name__ == "__main__":
    main()
