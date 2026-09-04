from pathlib import Path
from PIL import Image, ImageDraw

root = Path(r"D:\毕设制作\qa\论文综合优化版-render-3")
files = sorted(root.glob("page-*.png"), key=lambda p: int(p.stem.split("-")[1]))
out = root / "contact"
out.mkdir(exist_ok=True)
tile_w, tile_h = 360, 510
cols, rows = 4, 4
for offset in range(0, len(files), cols * rows):
    sheet = Image.new("RGB", (cols * tile_w, rows * tile_h), "white")
    for index, path in enumerate(files[offset:offset + cols * rows]):
        with Image.open(path) as source:
            image = source.convert("RGB")
            image.thumbnail((tile_w, tile_h))
            x = (index % cols) * tile_w
            y = (index // cols) * tile_h
            sheet.paste(image, (x, y))
            ImageDraw.Draw(sheet).text((x + 8, y + 8), path.stem, fill="red")
    sheet.save(out / f"contact-{offset + 1:03d}.jpg", quality=90)
print(f"created {len(list(out.glob('contact-*.jpg')))} contact sheets")
