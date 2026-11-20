#!/usr/bin/env python3
"""Regenerate Android vector foreground and preview from the brand SVG paths."""

from pathlib import Path
from xml.etree import ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
SVG = ROOT / "docs/brand/han1me-client-icon.svg"
DRAWABLE = ROOT / "app/src/main/res/drawable"
ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)


def vector(include_background: bool) -> ET.Element:
    result = ET.Element("vector", {
        f"{{{ANDROID}}}width": "108dp",
        f"{{{ANDROID}}}height": "108dp",
        f"{{{ANDROID}}}viewportWidth": "108",
        f"{{{ANDROID}}}viewportHeight": "108",
    })
    target = result
    if include_background:
        target = ET.SubElement(result, "group")
        ET.SubElement(target, "clip-path", {
            f"{{{ANDROID}}}pathData": (
                "M24 0H84A24 24 0 0 1 108 24V84A24 24 0 0 1 84 108"
                "H24A24 24 0 0 1 0 84V24A24 24 0 0 1 24 0Z"
            ),
        })
    source = ET.parse(SVG).getroot()
    for path in source.findall("{http://www.w3.org/2000/svg}path"):
        if path.get("id") == "background" and not include_background:
            continue
        attributes = {
            f"{{{ANDROID}}}fillColor": path.attrib["fill"],
            f"{{{ANDROID}}}pathData": path.attrib["d"],
        }
        if alpha := path.get("fill-opacity"):
            attributes[f"{{{ANDROID}}}fillAlpha"] = alpha
        ET.SubElement(target, "path", attributes)
    return result


def main() -> None:
    for filename, background in (
        ("ic_han1me_character_foreground.xml", False),
        ("ic_han1me_character_preview.xml", True),
    ):
        tree = ET.ElementTree(vector(background))
        ET.indent(tree, space="    ")
        tree.write(DRAWABLE / filename, encoding="utf-8", xml_declaration=True)


if __name__ == "__main__":
    main()
