"""Exercise release and corpus guards without installing upstream."""
import base64
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import generate_markdown_qualification_references as generator


class Distribution:
    def __init__(self, version, record, root="."):
        self.version = version
        self.record = record
        self.root = Path(root)

    def read_text(self, name):
        return self.record if name == "RECORD" else None

    def locate_file(self, name):
        return self.root / name


def installed(root, data):
    """Write a fake installed parser and return the RECORD describing it."""
    parser = Path(root) / "unstructured/partition/md.py"
    parser.parent.mkdir(parents=True)
    parser.write_bytes(data)
    digest = base64.urlsafe_b64encode(hashlib.sha256(data).digest()).rstrip(b"=").decode()
    return f"unstructured/partition/md.py,sha256={digest},{len(data)}\nunstructured-x.dist-info/RECORD,,\n"


class MarkdownQualificationGuardTests(unittest.TestCase):
    def test_release_version_and_installed_record_must_match_pin(self):
        with tempfile.TemporaryDirectory() as root:
            record = installed(root, b"pinned parser")
            digest = hashlib.sha256(record.encode()).hexdigest()
            with patch.object(generator, "INSTALLED_RECORD_SHA256", digest):
                self.assertEqual(digest, generator.verify_release(Distribution(generator.RELEASE, record, root)))
                with self.assertRaisesRegex(ValueError, "RECORD hash mismatch"):
                    generator.verify_release(Distribution(generator.RELEASE, record + "changed", root))
                with self.assertRaisesRegex(ValueError, "Expected unstructured"):
                    generator.verify_release(Distribution("0.0.1", record, root))

    def test_changed_or_missing_installed_parser_is_refused_while_record_is_unchanged(self):
        with tempfile.TemporaryDirectory() as root:
            record = installed(root, b"pinned parser")
            digest = hashlib.sha256(record.encode()).hexdigest()
            with patch.object(generator, "INSTALLED_RECORD_SHA256", digest):
                parser = Path(root) / "unstructured/partition/md.py"
                parser.write_bytes(b"modified parser")
                with self.assertRaisesRegex(ValueError, "differs from RECORD: unstructured/partition/md.py"):
                    generator.verify_release(Distribution(generator.RELEASE, record, root))
                parser.unlink()
                with self.assertRaisesRegex(ValueError, "file missing"):
                    generator.verify_release(Distribution(generator.RELEASE, record, root))

    def test_corpus_inputs_must_match_manifest_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            corpus = Path(directory)
            (corpus / "a.md").write_bytes(b"# A\n")
            entry = {"filename": "a.md", "sha256": hashlib.sha256(b"# A\n").hexdigest()}
            (corpus / "manifest.json").write_text(json.dumps({"documents": [entry]}))
            self.assertEqual([("a.md", b"# A\n")], generator.load_inputs(corpus))
            (corpus / "a.md").write_bytes(b"# B\n")
            with self.assertRaisesRegex(ValueError, "hash mismatch"):
                generator.load_inputs(corpus)


if __name__ == "__main__":
    unittest.main()
