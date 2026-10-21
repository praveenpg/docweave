"""Guard selected XML source hashes without an upstream install or network access."""
import hashlib
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import generate_xml_references as generator


class XmlGenerationGuardTests(unittest.TestCase):
    def test_selected_xml_sources_must_match_pin(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory)
            parser = source / "xml.py"
            parser.write_bytes(b"pinned xml implementation")
            expected = {parser.name: hashlib.sha256(parser.read_bytes()).hexdigest()}
            with patch.object(generator, "XML_SOURCE_HASHES", expected):
                self.assertEqual(expected, generator.verify_xml_source(source))
                parser.write_bytes(b"changed xml implementation")
                with self.assertRaisesRegex(ValueError, "source hash mismatch"):
                    generator.verify_xml_source(source)


if __name__ == "__main__":
    unittest.main()
