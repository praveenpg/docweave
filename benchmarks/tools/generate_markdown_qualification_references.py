"""Run the pinned Unstructured release on the owned Markdown corpus; Java consumes saved output offline."""
import argparse
import base64
import csv
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import platform
import sys

from generate_chunking_goldens import verify_runtime
from generate_docx_references import MODEL_VERSION, MODEL_SHA256, verify_model

RELEASE = "0.27.37"
# PyPI wheel observed when the reference was generated; the installed RECORD hash below is what is verified.
PYPI_WHEEL_SHA256 = "97945cebd168957f96d4d42976cdd62f1d204d3bd2d13afcb16e5fd6c8921393"
# RECORD lists the hash of every installed upstream file (Python 3.13 install of the wheel above); each listed
# unstructured/ file is re-hashed against it before import.
INSTALLED_RECORD_SHA256 = "18d3ee366925bbe0cd363eb8308d375cbe8e2a7e2bfbd98786e9d652be3f2996"
BASE_OPTIONS = {"languages": ["eng"], "html_parser_version": "v1"}
CONFIGURATIONS = {
    "default": {"extensions": ["tables", "fenced_code"]},
    "python-markdown-extensions": {"extensions": ["tables", "fenced_code", "footnotes", "def_list", "meta"]},
}
EXTENSION_INPUTS = ("extensions.md", "front-matter.md")


def verify_installed_files(distribution, record):
    """Re-hash every installed unstructured/ file that RECORD lists with a hash; refuse missing or changed files."""
    checked = 0
    for row in csv.reader(record.splitlines()):
        if len(row) < 2 or not row[0].startswith("unstructured/") or not row[1]:
            continue
        algorithm, _, expected = row[1].partition("=")
        if algorithm != "sha256":
            raise ValueError(f"Unsupported RECORD hash for {row[0]}: {algorithm}")
        path = Path(distribution.locate_file(row[0]))
        if not path.is_file():
            raise ValueError(f"Installed unstructured file missing: {row[0]}")
        actual = base64.urlsafe_b64encode(hashlib.sha256(path.read_bytes()).digest()).rstrip(b"=").decode("ascii")
        if actual != expected:
            raise ValueError(f"Installed unstructured file differs from RECORD: {row[0]}")
        checked += 1
    if not any(row and row[0] == "unstructured/partition/md.py" for row in csv.reader(record.splitlines())):
        raise ValueError("RECORD does not list unstructured/partition/md.py")
    return checked


def verify_release(distribution=None):
    """Require the pinned release, pinned RECORD and matching installed file bytes before importing upstream."""
    distribution = distribution or importlib.metadata.distribution("unstructured")
    if distribution.version != RELEASE:
        raise ValueError(f"Expected unstructured {RELEASE}, found {distribution.version}")
    record = distribution.read_text("RECORD") or ""
    digest = hashlib.sha256(record.encode("utf-8")).hexdigest()
    if digest != INSTALLED_RECORD_SHA256:
        raise ValueError("Installed unstructured RECORD hash mismatch")
    verify_installed_files(distribution, record)
    return digest


def load_inputs(corpus):
    """Load manifest-listed basenames and reject any input whose bytes differ from the manifest."""
    manifest = json.loads((corpus / "manifest.json").read_text(encoding="utf-8"))
    inputs = []
    for entry in manifest["documents"]:
        filename = entry["filename"]
        if Path(filename).name != filename or not filename.endswith(".md"):
            raise ValueError("Corpus filenames must be plain .md basenames")
        data = (corpus / filename).read_bytes()
        if hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError(f"Corpus input hash mismatch: {filename}")
        inputs.append((filename, data))
    return inputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-wheel", required=True, type=Path)
    parser.add_argument("--corpus", type=Path, default=Path("benchmarks/fixtures/markdown"))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    verify_runtime()
    record_sha256 = verify_release()
    verify_model(args.model_wheel)
    inputs = load_inputs(args.corpus)
    os.environ.update(DO_NOT_TRACK="1", UNSTRUCTURED_DISABLE_NOTICE="1")
    from unstructured.partition.md import partition_md

    cases = []
    for filename, data in inputs:
        names = ["default"] + (["python-markdown-extensions"] if filename in EXTENSION_INPUTS else [])
        for name in names:
            options = {"metadata_filename": filename, **BASE_OPTIONS, **CONFIGURATIONS[name]}
            elements = partition_md(text=data.decode("utf-8"), **options)
            cases.append({"input": filename, "configuration": name,
                          "input_sha256": hashlib.sha256(data).hexdigest(), "entrypoint": "public partition_md",
                          "upstream_options": options, "upstream_output": [e.to_dict() for e in elements]})
    output = {"reference": {"upstream_release": RELEASE, "pypi_wheel_sha256": PYPI_WHEEL_SHA256,
              "installed_record_sha256": record_sha256, "runtime": "python:3.13-slim container, pip install unstructured[md]", "python": platform.python_version(),
              "scope": "Owned Markdown corpus; HTML v1; separate from the historical 347fbbfd markup references",
              "configurations": CONFIGURATIONS,
              "model": {"name": "en_core_web_sm", "version": MODEL_VERSION, "wheel_sha256": MODEL_SHA256,
                        "installed_package_verified": True},
              "dependencies": {dist.metadata["Name"]: dist.version
                               for dist in sorted(importlib.metadata.distributions(),
                                                  key=lambda dist: dist.metadata["Name"].lower())}},
              "cases": cases}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(cases)} Markdown qualification references in {args.output}")


if __name__ == "__main__":
    main()
