"""Regression controls for geometry-only proposal comparisons; no inference dependencies needed."""
import unittest
from compare_scan_detectors import maximum_matches, metric, source_box


class ScanDetectorTest(unittest.TestCase):
    def test_duplicate_predictions_cannot_count_a_reference_twice(self):
        box = (0, 0, 10, 10)
        result = metric([box], [box, box])
        self.assertEqual(1, result['matched_reference_tables_at_iou_0_5'])
        self.assertEqual(1, result['unmatched_proposals'])

    def test_augmenting_path_avoids_order_dependent_under_count(self):
        references = [(0, 0, 10, 10), (0, 0, 5, 10)]
        candidates = [(0, 0, 8, 10), (5, 0, 10, 10)]
        self.assertEqual(2, len(maximum_matches(references, candidates)))
        self.assertEqual(2, len(maximum_matches(list(reversed(references)), candidates)))

    def test_empty_and_canvas_floor_do_not_invent_matches_or_rescaling(self):
        self.assertEqual([], maximum_matches([(0, 0, 10, 10)], []))
        frame = dict(original_width=100, original_height=100, width=99, height=99,
                     pdf_width=48, pdf_height=48, render_dpi=150)
        self.assertEqual((0, 0, 90, 90), source_box((0, 0, 90, 90), frame))


if __name__ == '__main__':
    unittest.main()
