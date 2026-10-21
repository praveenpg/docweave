"""Guard model-control replay against local capture IDs hiding real geometry changes."""
import tempfile
import unittest
from pathlib import Path
from compare_ruled_scans import canonical_tables, same_physical_frame


def capture(root, name, crop, table, edge=10):
    """Write one independent numeric layout fixture with caller-selected capture identities."""
    directory = root / name; directory.mkdir()
    (directory / 'outcome.txt').write_text('OK\n')
    (directory / 'tables.tsv').write_text('crop\ttable\trows\tcolumns\tleft\ttop\tright\tbottom\n'
                                         + f'{crop}\t{table}\t1\t1\t0\t0\t10\t10\n')
    (directory / 'anchors.tsv').write_text('crop\ttable\trow\tcolumn\trow_span\tcolumn_span\tleft\ttop\tright\tbottom\n'
                                          + f'{crop}\t{table}\t0\t0\t1\t1\t0\t0\t{edge}\t10\n')
    return directory


class RuledScanTest(unittest.TestCase):
    def test_resolution_change_does_not_allow_source_replacement_or_repositioning(self):
        first = dict(sha256='original', original_width='100', original_height='100',
                     pdf_width='48.0', pdf_height='48.0', render_dpi='150', width='99', height='99')
        higher = dict(first, render_dpi='300', width='199', height='199')
        self.assertTrue(same_physical_frame(first, higher))
        for changed in (dict(higher, sha256='changed'), dict(higher, pdf_width='49.0'),
                        dict(higher, original_width='101')):
            self.assertFalse(same_physical_frame(first, changed))

    def test_capture_ids_do_not_change_geometry_identity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = capture(root, 'first', 3, 7)
            second = capture(root, 'second', 0, 0)
            self.assertEqual(canonical_tables(first), canonical_tables(second))

    def test_same_topology_cannot_hide_changed_cell_geometry_or_failure(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            first = capture(root, 'first', 0, 0)
            second = capture(root, 'second', 0, 0, edge=9)
            self.assertNotEqual(canonical_tables(first), canonical_tables(second))
            (second / 'outcome.txt').write_text('TABLE_LIMIT\n')
            self.assertNotEqual(canonical_tables(first), canonical_tables(second))
            self.assertEqual(('TABLE_LIMIT', ()), canonical_tables(second))


if __name__ == '__main__':
    unittest.main()
