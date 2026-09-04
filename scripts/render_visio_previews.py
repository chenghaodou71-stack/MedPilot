"""Render the generated Visio pages to thesis-friendly PNG previews.

Microsoft Visio is not installed in the local environment, so this renderer
reads the standard page XML in each VSDX and draws the same boxes, connectors,
colors, and labels with Pillow.  The VSDX files remain the editable source.
"""
from __future__ import annotations

import math
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
SOURCE_DIR = ROOT / "outputs" / "visio-diagrams"
OUTPUT_DIR = ROOT / "outputs" / "thesis-images" / "visio-previews"
PAGE_W = 11.6929133858
PAGE_H = 8.2677165354
SCALE = 150
WIDTH = round(PAGE_W * SCALE)
HEIGHT = round(PAGE_H * SCALE)
NS = {"v": "http://schemas.microsoft.com/office/visio/2012/main"}


def _font(size: int, *, bold: bool = False) -> ImageFont.FreeTypeFont:
    candidates = (
        [r"C:\Windows\Fonts\msyhbd.ttc", r"C:\Windows\Fonts\simhei.ttf"]
        if bold
        else [r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\simsun.ttc"]
    )
    candidates += [r"C:\Windows\Fonts\arial.ttf"]
    for candidate in candidates:
        if Path(candidate).exists():
            return ImageFont.truetype(candidate, size=size)
    return ImageFont.load_default()


def _cell_map(shape: ET.Element) -> dict[str, str]:
    return {
        cell.attrib.get("N", ""): cell.attrib.get("V", "")
        for cell in shape.findall("v:Cell", NS)
    }


def _text(shape: ET.Element) -> str:
    node = shape.find("v:Text", NS)
    if node is None:
        return ""
    return "".join(node.itertext()).strip()


def _xy(x: float, y: float) -> tuple[int, int]:
    return round(x * SCALE), round((PAGE_H - y) * SCALE)


def _arrow(draw: ImageDraw.ImageDraw, start: tuple[int, int], end: tuple[int, int], color: str, width: int) -> None:
    dx, dy = end[0] - start[0], end[1] - start[1]
    length = math.hypot(dx, dy)
    if length < 1:
        return
    ux, uy = dx / length, dy / length
    px, py = -uy, ux
    tip = end
    back = (end[0] - ux * 16, end[1] - uy * 16)
    left = (round(back[0] + px * 6), round(back[1] + py * 6))
    right = (round(back[0] - px * 6), round(back[1] - py * 6))
    draw.polygon([tip, left, right], fill=color)


def _draw_label(draw: ImageDraw.ImageDraw, text: str, box: tuple[int, int, int, int], *, align: str) -> None:
    if not text:
        return
    x0, y0, x1, y1 = box
    lines = [line.strip() for line in text.splitlines() if line.strip()]
    if not lines:
        return
    max_chars = max(len(line) for line in lines)
    width, height = x1 - x0, y1 - y0
    size = min(24, max(12, int(min(width / max(1, max_chars * 0.95), height / max(1, len(lines) * 1.55)))))
    font = _font(size)
    spacing = max(3, round(size * 0.22))
    bbox = draw.multiline_textbbox((0, 0), "\n".join(lines), font=font, spacing=spacing, align="center")
    text_h = bbox[3] - bbox[1]
    top = y0 + max(0, (height - text_h) // 2)
    if align == "0":
        left = x0 + 12
        draw.multiline_text((left, top), "\n".join(lines), font=font, fill="#1F1F1F", spacing=spacing, align="left")
    else:
        draw.multiline_text(((x0 + x1) // 2, top), "\n".join(lines), font=font, fill="#1F1F1F", spacing=spacing, align="center", anchor="ma")


def render(path: Path) -> Image.Image:
    with zipfile.ZipFile(path) as archive:
        root = ET.fromstring(archive.read("visio/pages/page1.xml"))
    shapes = root.findall(".//v:Shape", NS)
    canvas = Image.new("RGB", (WIDTH, HEIGHT), "white")
    draw = ImageDraw.Draw(canvas)
    connectors: list[tuple[ET.Element, dict[str, str]]] = []
    regular: list[tuple[ET.Element, dict[str, str], str]] = []
    title: tuple[ET.Element, dict[str, str], str] | None = None
    for shape in shapes:
        cells = _cell_map(shape)
        text = _text(shape)
        if shape.attrib.get("NameU") == "Dynamic connector" or "BeginX" in cells:
            connectors.append((shape, cells))
        elif text and cells.get("NoFill") == "1" and cells.get("NoLine") == "1":
            title = (shape, cells, text)
        else:
            regular.append((shape, cells, text))

    for shape, cells in connectors:
        try:
            start = _xy(float(cells["BeginX"]), float(cells["BeginY"]))
            end = _xy(float(cells["EndX"]), float(cells["EndY"]))
        except (KeyError, ValueError):
            continue
        color = cells.get("LineColor", "#404040")
        width = max(2, round(float(cells.get("LineWeight", "0.016")) * SCALE))
        draw.line([start, end], fill=color, width=width)
        if cells.get("EndArrow"):
            _arrow(draw, start, end, color, width)

    for shape, cells, text in regular:
        try:
            cx, cy = float(cells["PinX"]), float(cells["PinY"])
            w, h = abs(float(cells["Width"])), abs(float(cells["Height"]))
        except (KeyError, ValueError):
            continue
        if w == 0 or h == 0:
            continue
        x0, y1 = _xy(cx - w / 2, cy - h / 2)
        x1, y0 = _xy(cx + w / 2, cy + h / 2)
        fill = cells.get("FillForegnd", "#FFFFFF")
        line = cells.get("LineColor", "#1F4E79")
        no_fill = cells.get("NoFill") == "1"
        no_line = cells.get("NoLine") == "1"
        if not no_fill:
            draw.rounded_rectangle((x0, y0, x1, y1), radius=10, fill=fill, outline=None if no_line else line, width=3)
        elif not no_line:
            draw.rectangle((x0, y0, x1, y1), outline=line, width=3)
        align = "0" if "HorzAlign" in cells and cells["HorzAlign"] == "0" else "1"
        _draw_label(draw, text, (x0, y0, x1, y1), align=align)

    if title is not None:
        shape, cells, text = title
        try:
            cx, cy = float(cells["PinX"]), float(cells["PinY"])
            w, h = abs(float(cells["Width"])), abs(float(cells["Height"]))
            x0, y1 = _xy(cx - w / 2, cy - h / 2)
            x1, y0 = _xy(cx + w / 2, cy + h / 2)
            title_font = _font(28, bold=True)
            draw.text(((x0 + x1) // 2, (y0 + y1) // 2), text, font=title_font, fill="#1F4E79", anchor="mm")
        except (KeyError, ValueError):
            pass
    return canvas


def main() -> None:
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    rendered: list[tuple[Path, Image.Image]] = []
    for source in sorted(SOURCE_DIR.glob("*.vsdx")):
        image = render(source)
        target = OUTPUT_DIR / f"{source.stem}.png"
        image.save(target, dpi=(180, 180), optimize=True)
        rendered.append((target, image))

    thumb_w, thumb_h = 760, 537
    sheet = Image.new("RGB", (thumb_w * 2, thumb_h * 5), "#F7F9FB")
    for index, (target, image) in enumerate(rendered):
        thumb = image.copy()
        thumb.thumbnail((thumb_w - 20, thumb_h - 20), Image.Resampling.LANCZOS)
        x = (index % 2) * thumb_w + (thumb_w - thumb.width) // 2
        y = (index // 2) * thumb_h + (thumb_h - thumb.height) // 2
        sheet.paste(thumb, (x, y))
    sheet.save(OUTPUT_DIR / "00_十张论文图件总览.png", dpi=(150, 150), optimize=True)
    print(f"Rendered {len(rendered)} PNG previews in {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
