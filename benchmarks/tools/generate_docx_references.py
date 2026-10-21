"""Run real pinned DOCX extraction on owned fixtures; Java regression tests need no Python."""
import argparse
import hashlib
import importlib.metadata
import io
import json
import os
from pathlib import Path
import platform
import resource
import sys
import time
import zipfile

from generate_chunking_goldens import COMMIT, verify_runtime

MODEL_VERSION = "3.8.0"
MODEL_SHA256 = "1932429db727d4bff3deed6b34cfc05df17794f4a52eeb26cf8928f7c1a0fb85"
FIXTURES = ("structured.docx", "docx-content.docx")
DOCX_SOURCE_HASHES = json.loads(Path(__file__).with_name("docx-reference-source-hashes.json").read_text())


def verify_docx_source(source):
    """Check selected parser/classifier/metadata sources before importing upstream."""
    expected = DOCX_SOURCE_HASHES
    actual = {name: hashlib.sha256((source / name).read_bytes()).hexdigest() for name in expected}
    if actual != expected:
        raise ValueError("Pinned DOCX source hash mismatch")
    return actual


def verify_model(wheel):
    """Verify pinned wheel and installed package bytes, preventing implicit model downloads."""
    if hashlib.sha256(wheel.read_bytes()).hexdigest() != MODEL_SHA256:
        raise ValueError("Pinned NLP model wheel hash mismatch")
    distribution = importlib.metadata.distribution("en-core-web-sm")
    if distribution.version != MODEL_VERSION:
        raise ValueError("Pinned NLP model version mismatch")
    with zipfile.ZipFile(wheel) as archive:
        for name in archive.namelist():
            if name.startswith("en_core_web_sm/") and not name.endswith("/"):
                if distribution.locate_file(name).read_bytes() != archive.read(name):
                    raise ValueError(f"Installed NLP model differs from pinned wheel: {name}")


def load_inputs(fixtures, manifest=None):
    """Load basename-only inputs and require external corpus hashes before parser imports."""
    entries = json.loads(manifest.read_text())["documents"] if manifest else [
        {"filename": filename} for filename in FIXTURES]
    inputs = []
    for entry in entries:
        filename = entry["filename"]
        if Path(filename).name != filename or not filename.endswith(".docx"):
            raise ValueError("Corpus filenames must be plain .docx basenames")
        data = (fixtures / filename).read_bytes()
        if manifest and hashlib.sha256(data).hexdigest() != entry["sha256"]:
            raise ValueError(f"Corpus input hash mismatch: {filename}")
        inputs.append((filename, data))
    return inputs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--upstream", required=True, type=Path)
    parser.add_argument("--model-wheel", required=True, type=Path)
    parser.add_argument("--fixtures", type=Path, default=Path("benchmarks/fixtures"))
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--manifest", type=Path, help="Optional hash-pinned external corpus manifest")
    args = parser.parse_args()
    verify_runtime()
    source = args.upstream.resolve()
    hashes = verify_docx_source(source)
    verify_model(args.model_wheel)
    inputs = load_inputs(args.fixtures, args.manifest)
    os.environ["DO_NOT_TRACK"] = "1"
    os.environ["UNSTRUCTURED_DISABLE_NOTICE"] = "1"
    sys.path.insert(0, str(source))
    from unstructured.partition.docx import partition_docx

    cases = []
    for filename, data in inputs:
        options = {"metadata_filename": filename, "languages": ["eng"],
                   "include_page_breaks": False, "infer_table_structure": True}
        started = time.perf_counter()
        # BytesIO avoids filesystem modification times/absolute paths in reference metadata.
        elements = partition_docx(file=io.BytesIO(data), **options)
        cases.append({"name": filename.removesuffix(".docx"), "input": filename,
                      "input_sha256": hashlib.sha256(data).hexdigest(), "upstream_options": options,
                      "java_options": "PartitionOptions.defaults()", "elapsed_seconds": time.perf_counter() - started,
                      "peak_process_rss_kib": resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
                      "upstream_output": [element.to_dict() for element in elements]})
    output = {"reference": {"upstream_commit": COMMIT, "python": platform.python_version(),
              "platform": platform.platform(), "scope": "Hash-pinned external DOCX corpus; no full parser/corpus parity claim" if args.manifest
                       else "Two owned synthetic DOCX files; no full parser/corpus parity claim",
              "entrypoint": "public unstructured.partition.docx.partition_docx; no patches or mocks",
              "source_sha256": hashes, "source_verification": "Selected implementation files only, not the entire Git tree",
              "model": {"name": "en_core_web_sm", "version": MODEL_VERSION, "wheel_sha256": MODEL_SHA256,
                        "installed_package_verified": True},
              "memory_measurement": "Linux process high-water RSS in KiB, cumulative across cases",
              "dependencies": {dist.metadata["Name"]: dist.version for dist in importlib.metadata.distributions()}},
              "cases": cases}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Generated {len(cases)} native DOCX references in {args.output}")


if __name__ == "__main__":
    main()
