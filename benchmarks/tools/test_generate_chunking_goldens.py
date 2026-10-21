"""Validate reference-generation guards without installing or importing upstream dependencies."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import generate_chunking_goldens as generator


class GenerationGuardTests(unittest.TestCase):
    def test_source_verification_accepts_selected_pin_and_rejects_drift(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            path = source / "implementation.py"
            path.write_bytes(b"pinned implementation\n")
            expected = {"implementation.py": hashlib.sha256(path.read_bytes()).hexdigest()}
            with patch.object(generator, "PINNED_SOURCE_HASHES", expected):
                self.assertEqual(expected, generator.verify_source(source))
                path.write_bytes(b"changed implementation\n")
                with self.assertRaisesRegex(ValueError, "implementation.py"):
                    generator.verify_source(source)

    def test_missing_source_fails_instead_of_claiming_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(FileNotFoundError):
                generator.verify_source(Path(directory))

    def test_supported_runtime_range_and_rejection(self):
        for minor in (11, 12, 13):
            with patch.object(generator.sys, "version_info", (3, minor, 0)):
                generator.verify_runtime()
        for version in ((3, 10, 0), (3, 14, 0), (4, 0, 0)):
            with patch.object(generator.sys, "version_info", version):
                with self.assertRaises(RuntimeError):
                    generator.verify_runtime()

    def test_intentional_differences_have_named_reviewed_expectations(self):
        cases = {case[0] for case in generator.option_cases()}
        self.assertEqual(16, len(cases))
        for name, (comparison, java_texts) in generator.OPTION_DIFFERENCES.items():
            self.assertIn(name, cases)
            self.assertTrue(comparison)
            self.assertTrue(java_texts)
            self.assertTrue(all(java_texts))


if __name__ == "__main__":
    unittest.main()
