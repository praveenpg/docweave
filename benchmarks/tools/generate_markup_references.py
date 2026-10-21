"""Generate unmocked native HTML/Markdown references; Java consumes saved output offline."""
import argparse
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import sys

from generate_chunking_goldens import COMMIT, verify_runtime
from generate_docx_references import MODEL_VERSION, MODEL_SHA256, verify_model

MARKUP_SOURCE_HASHES = json.loads(Path(__file__).with_name("markup-reference-source-hashes.json").read_text())
INPUTS = ("structured.html", "structured.md", "mixed-html.md")


def verify_markup_source(source):
    """Reject selected pinned-source drift before importing parser/classifier implementations."""
    actual = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in MARKUP_SOURCE_HASHES}
    if actual != MARKUP_SOURCE_HASHES:
        raise ValueError("Pinned HTML/Markdown source hash mismatch")
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", required=True, type=Path)
    parser.add_argument("--model-wheel", required=True, type=Path)
    parser.add_argument("--fixtures", type=Path, default=Path("benchmarks/fixtures"))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    verify_runtime()
    hashes = verify_markup_source(args.upstream)
    verify_model(args.model_wheel)
    os.environ.update(DO_NOT_TRACK="1", UNSTRUCTURED_DISABLE_NOTICE="1")
    sys.path.insert(0, str(args.upstream.resolve()))
    from unstructured.partition.html import partition_html
    from unstructured.partition.md import partition_md

    cases = []
    for filename in INPUTS:
        data = (args.fixtures / filename).read_bytes()
        markdown = filename.endswith(".md")
        options = {"metadata_filename": filename, "languages": ["eng"], "html_parser_version": "v1"}
        if markdown:
            options["extensions"] = ["tables", "fenced_code"]
        else:
            options["skip_headers_and_footers"] = False
        function = partition_md if markdown else partition_html
        elements = function(text=data.decode("utf-8"), **options)
        cases.append({"input": filename, "input_sha256": hashlib.sha256(data).hexdigest(),
                      "entrypoint": "public partition_md" if markdown else "public partition_html",
                      "upstream_options": options, "upstream_output": [e.to_dict() for e in elements]})
    output = {"reference": {"upstream_commit": COMMIT, "python": platform.python_version(),
              "scope": "Three owned synthetic inputs; HTML v1, Python-Markdown tables/fenced_code; no full parser parity claim",
              "source_verification": "Selected pinned files verified before import; not the complete Git tree",
              "source_sha256": hashes, "model": {"name": "en_core_web_sm", "version": MODEL_VERSION,
                    "wheel_sha256": MODEL_SHA256, "installed_package_verified": True},
              "dependencies": {dist.metadata["Name"]: dist.version for dist in importlib.metadata.distributions()}},
              "cases": cases}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(cases)} native HTML/Markdown references in {args.output}")


if __name__ == "__main__":
    main()
