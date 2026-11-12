"""#76: runs pinned Unstructured partition_image on DocWeave's owned image fixtures and records observable behavior.

Usage: python compare_image_upstream.py FIXTURE_DIR OUTPUT_JSON. Strategy ocr_only with languages ['eng'] is the
configuration comparable to DocWeave's OCR-only image path; hi_res needs a downloaded layout model and is not run.
"""
import json
import sys
import tempfile
import time
from importlib.metadata import version
from pathlib import Path

from unstructured.partition.image import partition_image


def describe(elements):
    rows = []
    for element in elements:
        meta = element.metadata.to_dict()
        coordinates = meta.get("coordinates") or {}
        rows.append({
            "type": type(element).__name__,
            "text": element.text,
            "page_number": meta.get("page_number"),
            "filetype": meta.get("filetype"),
            "coordinates_system": coordinates.get("system"),
            "layout": [coordinates.get("layout_width"), coordinates.get("layout_height")],
            "points": coordinates.get("points"),
        })
    return rows


def run(path, **kwargs):
    started = time.monotonic()
    try:
        elements = partition_image(filename=str(path), strategy="ocr_only", languages=["eng"], **kwargs)
        return {"ok": True, "elements": describe(elements), "seconds": round(time.monotonic() - started, 2)}
    except Exception as error:  # the observed failure is the comparison result
        return {"ok": False, "error": type(error).__name__, "message": str(error)[:300]}


def main():
    fixtures = Path(sys.argv[1])
    report = {"unstructured": version("unstructured"), "unstructured_inference": version("unstructured-inference"),
              "pytesseract": version("unstructured-pytesseract"), "strategy": "ocr_only", "languages": ["eng"],
              "results": {}}
    for name in ["text.png", "text.jpg", "text-exif6.jpg", "pages-g4.tif", "blank.png"]:
        report["results"][name] = run(fixtures / name)
    with tempfile.TemporaryDirectory() as scratch:
        png = (fixtures / "text.png").read_bytes()
        jpeg = (fixtures / "text.jpg").read_bytes()
        cases = {"truncated.png": png[: len(png) // 2], "truncated.jpg": jpeg[: len(jpeg) // 2],
                 "not-an-image.png": b"plain text", "big.tif": bytes([0x49, 0x49, 0x2b, 0, 8, 0, 0, 0, 16] + [0] * 7)}
        for name, data in cases.items():
            path = Path(scratch) / name
            path.write_bytes(data)
            report["results"][name] = run(path)
    Path(sys.argv[2]).write_text(json.dumps(report, indent=1))


if __name__ == "__main__":
    main()
