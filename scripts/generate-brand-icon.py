#!/usr/bin/env python3
"""Build launcher and settings icons from the project owner's square illustration."""

from pathlib import Path

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "docs/brand/han1me-client-icon.jpg"
OUTPUT = ROOT / "app/src/main/res/drawable-nodpi"
SIZE = 1024


def main() -> None:
    source = Image.open(SOURCE).convert("RGB")
    if source.size != (SIZE, SIZE):
        raise ValueError("Brand illustration must be 1024 × 1024 pixels")

    # Adaptive launchers may show only the center two thirds of the foreground.
    # Leave space above the character so her hair and face survive round masks.
    foreground = Image.new("RGB", (SIZE, SIZE), (13, 20, 35))
    scaled = source.resize((922, 922), Image.Resampling.LANCZOS)
    foreground.paste(scaled, (51, 72))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    foreground.save(OUTPUT / "ic_han1me_client_foreground.webp", "WEBP", quality=94, method=6)

    # The launcher picker uses an ordinary image, so render its visible shape.
    preview = foreground.crop((171, 171, 853, 853)).resize((256, 256), Image.Resampling.LANCZOS)
    mask = Image.new("L", (1024, 1024), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, 1023, 1023), radius=180, fill=255)
    preview = preview.convert("RGBA")
    preview.putalpha(mask.resize((256, 256), Image.Resampling.LANCZOS))
    preview.save(OUTPUT / "ic_han1me_client_preview.webp", "WEBP", lossless=True, method=6)


if __name__ == "__main__":
    main()
