"""Verify source drift rejection without installing upstream dependencies."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import generate_text_references as generator


class TextGenerationGuardTests(unittest.TestCase):
    def test_selected_text_sources_must_match_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            parser = source / "text.py"
            parser.write_bytes(b"pinned text implementation")
            expected = {parser.name: hashlib.sha256(parser.read_bytes()).hexdigest()}
            with patch.object(generator, "TEXT_SOURCE_HASHES", expected):
                self.assertEqual(expected, generator.verify_text_source(source))
                parser.write_bytes(b"changed text implementation")
                with self.assertRaisesRegex(ValueError, "source hash mismatch"):
                    generator.verify_text_source(source)


if __name__ == "__main__":
    unittest.main()
