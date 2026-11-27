#!/usr/bin/env python3
"""Import brush bank star SVGs and stamp textures into Android resources/assets.

Usage:
    python3 tools/import_brush_assets.py [--source design/brush-bank]
"""

from __future__ import annotations

import argparse
import re
import shutil
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import escape

DEFAULT_SOURCE = Path("design/brush-bank")
DRAWABLE_DIR = Path("app/src/main/res/drawable")
ASSETS_BRUSHES_DIR = Path("assets/brushes")


def strip_ns(tag: str) -> str:
    return tag.split("}", 1)[1] if "}" in tag else tag


def parse_viewbox(root: ET.Element) -> tuple[float, float, float, float]:
    vb = root.attrib.get("viewBox")
    if vb:
        parts = [float(x) for x in re.split(r"[\s,]+", vb.strip()) if x]
        if len(parts) == 4:
            return parts[0], parts[1], parts[2], parts[3]
    w = float(re.sub(r"[a-zA-Z%]+$", "", root.attrib.get("width", "24")))
    h = float(re.sub(r"[a-zA-Z%]+$", "", root.attrib.get("height", "24")))
    return 0.0, 0.0, w, h


def shape_to_path_data(elem: ET.Element) -> str | None:
    tag = strip_ns(elem.tag)
    if tag == "path":
        return elem.attrib.get("d", "").strip() or None
    if tag == "circle":
        cx = float(elem.attrib.get("cx", "0"))
        cy = float(elem.attrib.get("cy", "0"))
        r = float(elem.attrib.get("r", "0"))
        if r <= 0:
            return None
        return f"M{cx - r:g},{cy:g}a{r:g},{r:g} 0 1,0 {2 * r:g},0a{r:g},{r:g} 0 1,0 {-2 * r:g},0Z"
    if tag == "ellipse":
        cx = float(elem.attrib.get("cx", "0"))
        cy = float(elem.attrib.get("cy", "0"))
        rx = float(elem.attrib.get("rx", "0"))
        ry = float(elem.attrib.get("ry", "0"))
        if rx <= 0 or ry <= 0:
            return None
        return f"M{cx - rx:g},{cy:g}a{rx:g},{ry:g} 0 1,0 {2 * rx:g},0a{rx:g},{ry:g} 0 1,0 {-2 * rx:g},0Z"
    if tag == "rect":
        x = float(elem.attrib.get("x", "0"))
        y = float(elem.attrib.get("y", "0"))
        w = float(elem.attrib.get("width", "0"))
        h = float(elem.attrib.get("height", "0"))
        if w <= 0 or h <= 0:
            return None
        return f"M{x:g},{y:g}h{w:g}v{h:g}h{-w:g}Z"
    if tag == "line":
        x1 = float(elem.attrib.get("x1", "0"))
        y1 = float(elem.attrib.get("y1", "0"))
        x2 = float(elem.attrib.get("x2", "0"))
        y2 = float(elem.attrib.get("y2", "0"))
        return f"M{x1:g},{y1:g}L{x2:g},{y2:g}"
    if tag in ("polygon", "polyline"):
        pts = [float(v) for v in re.split(r"[\s,]+", elem.attrib.get("points", "").strip()) if v]
        if len(pts) < 4:
            return None
        coords = [f"{pts[i]:g},{pts[i + 1]:g}" for i in range(0, len(pts) - 1, 2)]
        cmd = "M" + "L".join(coords)
        return cmd + ("Z" if tag == "polygon" else "")
    return None


def normalize_color(value: str | None, default: str | None = "#FF000000") -> str | None:
    if value is None:
        return default
    v = value.strip()
    if v.lower() == "none":
        return None
    if v.startswith("#"):
        hex_part = v[1:]
        if len(hex_part) == 3:
            hex_part = "".join(c * 2 for c in hex_part)
        if len(hex_part) == 6:
            return "#FF" + hex_part.upper()
        if len(hex_part) == 8:
            return "#" + hex_part.upper()
    return default


def svg_to_vector_xml(svg_path: Path) -> str:
    tree = ET.parse(svg_path)
    root = tree.getroot()
    min_x, min_y, vw, vh = parse_viewbox(root)

    path_elements: list[str] = []
    for elem in root.iter():
        d = shape_to_path_data(elem)
        if not d:
            continue
        fill = normalize_color(elem.attrib.get("fill"), "#FF000000")
        stroke = normalize_color(elem.attrib.get("stroke"), None) if "stroke" in elem.attrib else None
        stroke_width = elem.attrib.get("stroke-width")
        attrs = [f'        android:pathData="{escape(d)}"']
        if fill:
            attrs.append(f'        android:fillColor="{fill}"')
        if stroke:
            attrs.append(f'        android:strokeColor="{stroke}"')
            attrs.append(f'        android:strokeWidth="{stroke_width or "2"}"')
        if "stroke-linecap" in elem.attrib:
            attrs.append(f'        android:strokeLineCap="{escape(elem.attrib["stroke-linecap"])}"')
        if "stroke-linejoin" in elem.attrib:
            attrs.append(f'        android:strokeLineJoin="{escape(elem.attrib["stroke-linejoin"])}"')
        path_elements.append("    <path\n" + "\n".join(attrs) + " />")

    group_open = ""
    group_close = ""
    if min_x != 0.0 or min_y != 0.0:
        group_open = f'    <group android:translateX="{-min_x:g}" android:translateY="{-min_y:g}">\n'
        group_close = "\n    </group>"
        path_elements = ["    " + line.replace("\n", "\n    ") for line in path_elements]

    body = "\n".join(path_elements)
    return (
        '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
        '    android:width="24dp"\n'
        '    android:height="24dp"\n'
        f'    android:viewportWidth="{vw:g}"\n'
        f'    android:viewportHeight="{vh:g}">\n'
        f"{group_open}{body}{group_close}\n"
        "</vector>\n"
    )


def import_assets(source_dir: Path, drawable_dir: Path, assets_dir: Path) -> None:
    if not source_dir.is_dir():
        raise SystemExit(f"Source directory not found: {source_dir}")
    drawable_dir.mkdir(parents=True, exist_ok=True)
    assets_dir.mkdir(parents=True, exist_ok=True)

    for svg_name, drawable_name in (
        ("star-on.svg", "ic_star_on.xml"),
        ("star-off.svg", "ic_star_off.xml"),
    ):
        svg_path = source_dir / svg_name
        if svg_path.is_file():
            out_xml = drawable_dir / drawable_name
            out_xml.write_text(svg_to_vector_xml(svg_path), encoding="utf-8")
            print(f"Converted {svg_path} -> {out_xml}")

    for entry in sorted(source_dir.iterdir()):
        if not entry.is_dir():
            continue
        brush_id = entry.name
        dest_webp = assets_dir / brush_id / "stamp.webp"
        src_webp = entry / "stamp.webp"
        src_png = entry / "stamp.png"
        if src_webp.is_file():
            dest_webp.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src_webp, dest_webp)
            print(f"Copied {src_webp} -> {dest_webp}")
        elif src_png.is_file():
            convert_stamp_png_to_webp(src_png, dest_webp)
            print(f"Converted {src_png} -> {dest_webp}")



def convert_stamp_png_to_webp(src_png: Path, dest_webp: Path) -> None:
    """Write a lossless WebP with alpha from a source stamp PNG."""
    from PIL import Image

    with Image.open(src_png) as image:
        image = image.convert("RGBA")
        dest_webp.parent.mkdir(parents=True, exist_ok=True)
        image.save(dest_webp, "WEBP", lossless=True, quality=100, method=6)


def main() -> None:
    parser = argparse.ArgumentParser(description="Import brush bank star SVGs and stamp textures as lossless WebP.")
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE, help="Source directory (default: design/brush-bank)")
    args = parser.parse_args()
    import_assets(args.source, DRAWABLE_DIR, ASSETS_BRUSHES_DIR)


if __name__ == "__main__":
    main()
