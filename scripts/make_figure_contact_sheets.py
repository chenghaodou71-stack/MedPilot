from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parents[1] / "outputs" / "thesis-figures"
files = sorted((root / "drawio").glob("*.png")) + sorted((root / "results").glob("*.png"))
font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 18)
tw, th, cols = 820, 470, 2
for page, start in enumerate(range(0, len(files), 8), 1):
    chunk = files[start : start + 8]
    rows = (len(chunk) + cols - 1) // cols
    out = Image.new("RGB", (cols * tw, rows * th), (245, 247, 250))
    draw = ImageDraw.Draw(out)
    for i, path in enumerate(chunk):
        im = Image.open(path).convert("RGB")
        im.thumbnail((tw - 30, th - 60))
        x, y = (i % cols) * tw, (i // cols) * th
        out.paste(im, (x + (tw - im.width) // 2, y + 5))
        draw.text((x + 12, y + th - 42), path.stem, fill=(25, 35, 50), font=font)
    out.save(root / f"contact_{page}.jpg", quality=92)
print(f"wrote {((len(files) + 7) // 8)} contact sheets")
