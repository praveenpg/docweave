"""Independent analytical controls for the optional Python reference comparison only."""

import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('comparison', Path(__file__).parents[1] / 'tools/compare_pdf_upstream.py')
COMPARISON = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COMPARISON)


class ReferenceTableMetricsTest(unittest.TestCase):
    def test_vertical_span_skips_occupied_position(self):
        actual = COMPARISON.html_grid('<table><tr><td rowspan="2">A</td><td>B</td></tr><tr><td>C</td></tr></table>')
        self.assertEqual((2, 2, [(0, 0, 2, 1, 'A'), (0, 1, 1, 1, 'B'), (1, 1, 1, 1, 'C')]), actual)

    def test_labels_supply_literal_unicode_text_and_preserve_punctuation(self):
        grid = COMPARISON.html_grid('<table><tr><td colspan="2"></td></tr></table>',
                                    [{'tokens': ['<b>α</b>', '  A', '\nB!']}])
        self.assertEqual((1, 2, [(0, 0, 1, 2, 'α A B!')]), grid)

    def test_invalid_overlapping_and_overlong_spans_are_not_repaired(self):
        for html in ('<table><tr><td rowspan="2">A</td></tr></table>',
                     '<table><tr><td>A</td><td rowspan="2">B</td></tr><tr><td colspan="2">C</td></tr></table>'):
            with self.assertRaises(ValueError):
                COMPARISON.html_grid(html)

    def test_same_dimensions_do_not_hide_wrong_spans_or_text(self):
        expected = (1, 2, [(0, 0, 1, 2, 'A')])
        split = (1, 2, [(0, 0, 1, 1, 'A'), (0, 1, 1, 1, '')])
        self.assertFalse(COMPARISON.metrics(expected, split)['complete_topology'])
        changed = (1, 2, [(0, 0, 1, 2, 'a')])
        result = COMPARISON.metrics(expected, changed)
        self.assertTrue(result['complete_topology'])
        self.assertFalse(result['complete_topology_and_text'])
        self.assertEqual(0, result['exact_cell_text_matches'])

    def test_duplicate_keys_cannot_inflate_matches(self):
        with self.assertRaises(ValueError):
            COMPARISON.metrics((1, 1, [(0, 0, 1, 1, 'A')]), (1, 1, [(0, 0, 1, 1, 'A')] * 2))

    def test_upstream_positions_are_preserved_and_nonrectangular_cells_rejected(self):
        self.assertEqual((2, 3, [(0, 1, 2, 2, 'A')]), COMPARISON.cell_grid(
            [{'row_nums': [0, 1], 'column_nums': [1, 2], 'cell text': 'A'}]))
        with self.assertRaises(ValueError):
            COMPARISON.cell_grid([{'row_nums': [0, 2], 'column_nums': [0], 'cell text': 'A'}])


if __name__ == '__main__':
    unittest.main()
