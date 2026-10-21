"""Checks for the diagnostic comparator's association and coordinate-frame boundaries."""
from pathlib import Path
import tempfile
import unittest

from compare_tiled_regions import compare, predictions


class TiledRegionsTest(unittest.TestCase):
    def test_identical_span_keys_without_spatial_overlap_are_not_a_match(self):
        reference = {'rows': 1, 'columns': 1, 'anchors': {(0, 0, 1, 1)}, 'box': (0, 0, 10, 10)}
        candidate = dict(reference, box=(20, 20, 30, 30), profile='whole')
        result = compare(reference, [candidate])
        self.assertFalse(result['exact_topology'])
        self.assertEqual(0, result['matching_anchors'])
        self.assertTrue(result['no_positive_overlap'])

    def test_spatial_association_does_not_select_an_oracle_topology(self):
        reference = {'rows': 1, 'columns': 1, 'anchors': {(0, 0, 1, 1)}, 'box': (0, 0, 10, 10)}
        wrong = dict(reference, rows=2, anchors={(0, 0, 1, 1), (1, 0, 1, 1)}, profile='whole')
        oracle = dict(reference, box=(0, 0, 2, 2), profile='upper')
        result = compare(reference, [oracle, wrong])
        self.assertEqual('whole', result['profile'])
        self.assertFalse(result['exact_topology'])
        self.assertEqual(1, result['best_iou'])

    def test_raster_canvas_floor_does_not_rescale_source_geometry(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / 'outcome.txt').write_text('OK\n')
            (path / 'tables.tsv').write_text('crop\ttable\trows\tcolumns\tleft\ttop\tright\tbottom\n0\t0\t1\t1\t0\t0\t90\t90\n')
            (path / 'anchors.tsv').write_text('crop\ttable\trow\tcolumn\trow_span\tcolumn_span\n0\t0\t0\t0\t1\t1\n')
            frame = dict(original_width=100, original_height=100, width=99, height=99,
                         pdf_width=48, pdf_height=48, render_dpi=150)
            self.assertEqual((0, 0, 90, 90), predictions(path, frame)[0]['box'])
            (path / 'outcome.txt').write_text('OCR_LIMIT\n')
            self.assertEqual([], predictions(path, frame))


if __name__ == '__main__':
    unittest.main()
