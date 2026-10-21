"""Guard tests use synthetic files; native reference generation itself is unmocked."""
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

import generate_docx_references as generator


class DocxGenerationGuardTests(unittest.TestCase):
    def test_external_manifest_inputs_are_pinned_before_upstream_imports(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "owned.docx"
            source.write_bytes(b"owned reference-generator guard")
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"documents": [{"filename": source.name,
                "sha256": hashlib.sha256(source.read_bytes()).hexdigest()}]}))
            self.assertEqual([(source.name, source.read_bytes())], generator.load_inputs(root, manifest))
            source.write_bytes(b"changed input")
            with self.assertRaisesRegex(ValueError, "input hash mismatch"):
                generator.load_inputs(root, manifest)

    def test_manifest_cannot_escape_the_reference_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"documents": [{"filename": "../outside.docx", "sha256": "unused"}]}))
            with self.assertRaisesRegex(ValueError, "basenames"):
                generator.load_inputs(root, manifest)

    def test_selected_docx_sources_are_checked_before_import(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            implementation = source / "docx.py"
            implementation.write_bytes(b"pinned")
            expected = {"docx.py": hashlib.sha256(b"pinned").hexdigest()}
            with patch.object(generator, "DOCX_SOURCE_HASHES", expected):
                self.assertEqual(expected, generator.verify_docx_source(source))
                implementation.write_bytes(b"drift")
                with self.assertRaisesRegex(ValueError, "source hash mismatch"):
                    generator.verify_docx_source(source)

    def test_wrong_model_wheel_is_rejected_before_package_lookup(self):
        with tempfile.TemporaryDirectory() as directory:
            wheel = Path(directory) / "model.whl"
            wheel.write_bytes(b"untrusted bytes")
            with self.assertRaisesRegex(ValueError, "wheel hash mismatch"):
                generator.verify_model(wheel)

    def test_installed_model_must_match_verified_wheel_and_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            wheel = root / "model.whl"
            package = root / "en_core_web_sm"
            package.mkdir()
            installed = package / "weights"
            installed.write_bytes(b"pinned weights")
            with zipfile.ZipFile(wheel, "w") as archive:
                archive.writestr("en_core_web_sm/weights", b"pinned weights")
            digest = hashlib.sha256(wheel.read_bytes()).hexdigest()
            distribution = SimpleNamespace(version=generator.MODEL_VERSION, locate_file=lambda name: root / name)
            with patch.object(generator, "MODEL_SHA256", digest), patch.object(
                    generator.importlib.metadata, "distribution", return_value=distribution):
                generator.verify_model(wheel)
                installed.write_bytes(b"changed weights")
                with self.assertRaisesRegex(ValueError, "differs from pinned wheel"):
                    generator.verify_model(wheel)
                distribution.version = "0.0.0"
                with self.assertRaisesRegex(ValueError, "version mismatch"):
                    generator.verify_model(wheel)


if __name__ == "__main__":
    unittest.main()
