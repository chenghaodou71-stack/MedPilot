from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
ASSET_ROOT = ROOT / "outputs" / "chapter3-reference-assets"
OUTPUT_ROOT = ROOT / "qa" / "chapter3-reference-assets"
FILES = sorted((ASSET_ROOT / "diagrams").glob("*.png")) + sorted(
    (ASSET_ROOT / "entity-classes").glob("*.png")
)

OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 22)
tile_width, tile_height, columns = 1100, 680, 2

for page, start in enumerate(range(0, len(FILES), 6), 1):
    chunk = FILES[start : start + 6]
    rows = (len(chunk) + columns - 1) // columns
    sheet = Image.new("RGB", (columns * tile_width, rows * tile_height), "white")
    draw = ImageDraw.Draw(sheet)
    for index, path in enumerate(chunk):
        image = Image.open(path).convert("RGB")
        image.thumbnail((tile_width - 50, tile_height - 85))
        x = (index % columns) * tile_width
        y = (index // columns) * tile_height
        sheet.paste(image, (x + (tile_width - image.width) // 2, y + 8))
        draw.text((x + 16, y + tile_height - 58), path.stem, fill="black", font=font)
        draw.rectangle((x, y, x + tile_width - 1, y + tile_height - 1), outline="#b8b8b8", width=2)
    sheet.save(OUTPUT_ROOT / f"contact-{page}.png")

print(f"files={len(FILES)} sheets={(len(FILES) + 5) // 6} output={OUTPUT_ROOT}")
