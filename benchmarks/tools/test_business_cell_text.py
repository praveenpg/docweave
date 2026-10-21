"""Offline guards for the optional text-free business comparison, without running OCR."""
import unittest
import json
from pathlib import Path
import tempfile
from compare_business_cell_text import compare, normalize, project, run, select


def table(text='A', rows=1, columns=1, rowspan=1, colspan=1, box=(0, 0, 10, 10)):
    left, top, right, bottom = box
    return dict(coordinates=dict(system='PDFUserSpace', points=[
        [left, top], [right, top], [right, bottom], [left, bottom]]),
        text_as_html='<table><tr><td>'+text+'</td></tr></table>',
        pdf_table_cells=dict(row_count=rows, column_count=columns, cells=[dict(
            row_index=0, column_index=0, row_span=rowspan, column_span=colspan)]))


class BusinessCellTextTest(unittest.TestCase):
    def test_missing_table_does_not_match_blank_expectations(self):
        result = compare([['A', '']], None)
        self.assertEqual(0, result['matched_positions'])
        self.assertFalse(result['full_grid_text_match'])

    def test_uncovered_slots_do_not_match_blanks(self):
        result = compare([['A', '']], table(columns=2))
        self.assertEqual(1, result['matched_positions'])
        self.assertEqual(0, result['matched_empty'])

    def test_merged_anchor_projection_is_evaluation_only(self):
        prediction = table(columns=2, colspan=2)
        self.assertTrue(compare([['A', '']], prediction)['full_grid_text_match'])
        self.assertEqual(1, len(prediction['pdf_table_cells']['cells']))

    def test_geometry_selection_does_not_choose_better_text(self):
        wrong = table('wrong')
        right = table('A', box=(0, 0, 9, 9))
        selected, overlap = select([wrong, right], [0, 0, 10, 10])
        self.assertIs(wrong, selected)
        self.assertEqual(1, overlap)
        self.assertIsNone(select([right], [20, 20, 30, 30])[0])

    def test_profile_names_cannot_escape_capture_directory_or_duplicate_cases(self):
        for profiles in [[], ['../outside'], ['default', 'default'], ['/absolute']]:
            with self.subTest(profiles=profiles), self.assertRaises(ValueError):
                run({}, Path('/unused-cache'), Path('/unused-capture'), profiles)

    def test_explicit_native_profile_preserves_reference_denominator(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root/'expected.csv').write_text('A,\n')
            (root/'runtime.json').write_text('{}')
            (root/'source.json').write_text('{}')
            capture = dict(outcome='SUCCESS', tables=[table(columns=2)], page_calls=0,
                           cell_calls=0, layout_calls=0, layout_tables=0, page_raster_sha256=[])
            (root/'default.json').write_text(json.dumps(capture))
            manifest = dict(inputs=[], cases=[dict(id='.', csv='expected.csv', expected_rows=1,
                expected_columns=2, domain='owned-control', roi_left_top_right_bottom=[0, 0, 10, 10])])
            result = run(manifest, root, root, ['default'])['cases'][0]
            self.assertEqual('default', result['profile'])
            self.assertEqual(2, result['reference_positions'])
            self.assertEqual(1, result['matched_positions'])
            self.assertEqual(0, result['page_calls'])

    def test_ascii_whitespace_only(self):
        self.assertEqual('A B', normalize(' A\t\n B '))
        self.assertNotEqual(normalize('a'), normalize('A'))
        self.assertEqual('A\u00a0B', normalize('A\u00a0B'))

    def test_escaped_html_text_and_overlapping_anchors(self):
        prediction = table('A &amp; B&lt;C')
        self.assertEqual('A & B<C', project(prediction)[1][0, 0])
        prediction['pdf_table_cells']['cells'] *= 2
        prediction['text_as_html'] = '<table><tr><td>A</td><td>B</td></tr></table>'
        with self.assertRaisesRegex(ValueError, 'Overlapping'):
            project(prediction)


if __name__ == '__main__':
    unittest.main()
