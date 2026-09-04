from pathlib import Path
from PIL import Image, ImageDraw

pages = sorted(Path("outputs/thesis-qa-new-2/pages").glob("page-*.png"))
thumbs = []
for page in pages:
    image = Image.open(page).convert("RGB")
    image.thumbnail((260, 370))
    canvas = Image.new("RGB", (280, 410), "white")
    canvas.paste(image, ((280 - image.width) // 2, 25))
    ImageDraw.Draw(canvas).text((10, 5), page.stem, fill="black")
    thumbs.append(canvas)
sheet = Image.new("RGB", (280 * 4, 410 * ((len(thumbs) + 3) // 4)), "#EAEFF4")
for index, image in enumerate(thumbs):
    sheet.paste(image, ((index % 4) * 280, (index // 4) * 410))
sheet.save("outputs/thesis-qa-new-2/contact-sheet.png")
