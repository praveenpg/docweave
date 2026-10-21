"""Generate real native XML observations, including explicitly unsupported Java option modes."""
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

XML_SOURCE_HASHES = json.loads(Path(__file__).with_name("xml-reference-source-hashes.json").read_text())


def verify_xml_source(source):
    actual = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in XML_SOURCE_HASHES}
    if actual != XML_SOURCE_HASHES:
        raise ValueError("Pinned XML source hash mismatch")
    return actual


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", required=True, type=Path)
    parser.add_argument("--model-wheel", required=True, type=Path)
    parser.add_argument("--fixtures", type=Path, default=Path("benchmarks/fixtures"))
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    verify_runtime()
    hashes = verify_xml_source(args.upstream)
    verify_model(args.model_wheel)
    os.environ.update(DO_NOT_TRACK="1", UNSTRUCTURED_DISABLE_NOTICE="1")
    sys.path.insert(0, str(args.upstream.resolve()))
    from unstructured.partition.xml import partition_xml
    specifications = [("structured-default", "structured.xml", {}),
                      ("structured-keep-tags", "structured.xml", {"xml_keep_tags": True}),
                      ("structured-xpath", "structured.xml", {"xml_path": '//*[local-name()="item"]'}),
                      ("mixed-order", "mixed-order.xml", {}),
                      ("internal-entity-policy", "entity-policy.xml", {})]
    cases = []
    for name, filename, policy in specifications:
        data = (args.fixtures / filename).read_bytes()
        options = {"metadata_filename": filename, "languages": ["eng"], **policy}
        elements = partition_xml(file=io.BytesIO(data), **options)
        cases.append({"name": name, "input": filename, "input_sha256": hashlib.sha256(data).hexdigest(),
                      "entrypoint": "public partition_xml", "upstream_options": options,
                      "upstream_output": [e.to_dict() for e in elements]})
    output = {"reference": {"upstream_commit": COMMIT, "python": platform.python_version(),
              "scope": "Five owned synthetic XML runs; no external entity target or parser-parity claim",
              "source_verification": "Selected pinned files verified before import, not the entire Git tree",
              "source_sha256": hashes, "model": {"name": "en_core_web_sm", "version": MODEL_VERSION,
                  "wheel_sha256": MODEL_SHA256, "installed_package_verified": True},
              "dependencies": {dist.metadata["Name"]: dist.version for dist in importlib.metadata.distributions()}},
              "cases": cases}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(cases)} native XML references in {args.output}")


if __name__ == "__main__":
    main()
