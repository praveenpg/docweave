"""Exercise source verification without external dependencies or parser mocks in generation."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import generate_markup_references as generator


class MarkupGenerationGuardTests(unittest.TestCase):
    def test_selected_markup_sources_must_match_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            parser = source / "parser.py"
            parser.write_bytes(b"pinned markup implementation")
            expected = {parser.name: hashlib.sha256(parser.read_bytes()).hexdigest()}
            with patch.object(generator, "MARKUP_SOURCE_HASHES", expected):
                self.assertEqual(expected, generator.verify_markup_source(source))
                parser.write_bytes(b"changed markup implementation")
                with self.assertRaisesRegex(ValueError, "source hash mismatch"):
                    generator.verify_markup_source(source)


if __name__ == "__main__":
    unittest.main()
