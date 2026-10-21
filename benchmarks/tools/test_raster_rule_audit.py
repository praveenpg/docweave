"""Guard isolated prototype construction against accidental source drift or broader policy changes."""
import unittest
from audit_raster_rules import DARK, PROFILES, SOURCE_FILE, prototype

SOURCE = SOURCE_FILE


class RasterRuleAuditTest(unittest.TestCase):
    def test_baseline_changes_access_only_and_unknown_source_is_rejected(self):
        source = SOURCE.read_text()
        expected = source.replace('private static List<PdfPageGraphics.Segment> trimCaps(', 'static List<PdfPageGraphics.Segment> trimCaps(')
        expected = expected.replace('private static boolean hasRuleRemnants(', 'static boolean hasRuleRemnants(')
        self.assertEqual(expected, prototype(source, 'baseline'))
        with self.assertRaises(ValueError): prototype(source + '\n', 'baseline')
        with self.assertRaises(ValueError): prototype(source, 'unknown')

    def test_compact_preserves_dark_predicate_and_resource_caps(self):
        source = prototype(SOURCE.read_text(), 'compact')
        self.assertIn(DARK, source)
        self.assertIn('MIN_RUN = 32;', source)
        for unchanged in ('MAX_RUNS = 4096;', 'MAX_THICKNESS = 8;', 'MAX_PIXEL_VISITS = 64_000_000;'):
            self.assertIn(unchanged, source)
        self.assertEqual((32, 16, 12), PROFILES['compact'])


if __name__ == '__main__':
    unittest.main()
