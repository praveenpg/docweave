"""Run real pinned TXT/CSV/TSV entrypoints; retain exact options and dependency provenance."""
import argparse
import hashlib
import importlib.metadata
import io
import json
import os
from pathlib import Path
import platform
import sys

from generate_chunking_goldens import COMMIT, verify_runtime
from generate_docx_references import MODEL_VERSION, MODEL_SHA256, verify_model

TEXT_SOURCE_HASHES = json.loads(Path(__file__).with_name("text-reference-source-hashes.json").read_text())


def verify_text_source(source):
    actual = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in TEXT_SOURCE_HASHES}
    if actual != TEXT_SOURCE_HASHES:
        raise ValueError("Pinned TXT/CSV/TSV source hash mismatch")
    return actual


def specifications():
    return [("plain-default", "plain.txt", {}),
            ("text-auto", "text-policies.txt", {}),
            ("text-no-grouper", "text-policies.txt", {"paragraph_grouper": False}),
            ("text-size-keywords", "plain.txt", {"min_partition": 10, "max_partition": 30})] + [
            (f"{kind}-header-{str(header).lower()}", f"quoted.{kind}", {"include_header": header})
            for kind in ("csv", "tsv") for header in (False, True)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", required=True, type=Path)
    parser.add_argument("--model-wheel", required=True, type=Path)
    parser.add_argument("--fixtures", type=Path, default=Path("benchmarks/fixtures"))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    verify_runtime()
    hashes = verify_text_source(args.upstream)
    verify_model(args.model_wheel)
    os.environ.update(DO_NOT_TRACK="1", UNSTRUCTURED_DISABLE_NOTICE="1")
    sys.path.insert(0, str(args.upstream.resolve()))
    from unstructured.partition.text import partition_text
    from unstructured.partition.csv import partition_csv
    from unstructured.partition.tsv import partition_tsv
    cases = []
    for name, filename, policy in specifications():
        data = (args.fixtures / filename).read_bytes()
        options = {"metadata_filename": filename, "languages": ["eng"], **policy}
        kind = filename.rsplit(".", 1)[-1]
        function = {"txt": partition_text, "csv": partition_csv, "tsv": partition_tsv}[kind]
        elements = function(file=io.BytesIO(data), **options)
        cases.append({"name": name, "input": filename, "input_sha256": hashlib.sha256(data).hexdigest(),
                      "entrypoint": f"public {function.__name__}", "upstream_options": options,
                      "upstream_output": [element.to_dict() for element in elements]})
    output = {"reference": {"upstream_commit": COMMIT, "python": platform.python_version(),
              "scope": "Eight synthetic TXT/CSV/TSV runs; no full option/parser parity claim",
              "source_verification": "Selected pinned files verified before import, not the entire Git tree",
              "source_sha256": hashes, "model": {"name": "en_core_web_sm", "version": MODEL_VERSION,
                    "wheel_sha256": MODEL_SHA256, "installed_package_verified": True},
              "dependencies": {dist.metadata["Name"]: dist.version for dist in importlib.metadata.distributions()}},
              "cases": cases}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(cases)} native TXT/CSV/TSV references in {args.output}")


if __name__ == "__main__":
    main()
