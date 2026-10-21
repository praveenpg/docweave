"""Controls preventing empty/duplicate reference predictions from inflating benchmark results."""

import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location('summary', Path(__file__).parents[1] / 'tools/summarize_pdf_upstream.py')
SUMMARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SUMMARY)


class CorpusAggregationTest(unittest.TestCase):
    def table(self, anchors=10, complete=True):
        return dict(anchors=anchors, complete_topology=complete, complete_topology_and_text=False,
                    positional_span_matches=anchors)

    def test_empty_recognition_is_retained_without_table_credit(self):
        result = SUMMARY.aggregate([{SUMMARY.PROFILES[1]: self.table(0, False)}], SUMMARY.PROFILES[1])
        self.assertEqual(1, result['inputs'])
        self.assertEqual(0, result['nonempty_tables'])
        self.assertEqual(0, result['exactly_one_complete_topology'])

    def test_duplicate_tables_do_not_double_matches_or_receive_complete_input_credit(self):
        result = SUMMARY.aggregate([{SUMMARY.PROFILES[0]: [self.table(), self.table()]}], SUMMARY.PROFILES[0])
        self.assertEqual(2, result['nonempty_tables'])
        self.assertEqual(1, result['inputs_with_multiple_tables'])
        self.assertEqual(10, result['best_candidate_positional_span_matches'])
        self.assertEqual(0, result['exactly_one_complete_topology'])

    def test_extra_public_element_without_html_prevents_complete_input_credit(self):
        result = SUMMARY.aggregate([{SUMMARY.PROFILES[2]: [self.table()], 'public_detected_tables': 2}], SUMMARY.PROFILES[2])
        self.assertEqual(0, result['exactly_one_complete_topology'])

    def test_misses_stay_in_denominator_and_text_credit_remains_separate(self):
        result = SUMMARY.aggregate([{SUMMARY.PROFILES[0]: []}, {SUMMARY.PROFILES[0]: [self.table()]}], SUMMARY.PROFILES[0])
        self.assertEqual(2, result['inputs'])
        self.assertEqual(1, result['inputs_with_table'])
        self.assertEqual(1, result['exactly_one_complete_topology'])
        self.assertEqual(0, result['exactly_one_complete_topology_and_text'])

    def test_java_extra_tables_prevent_complete_input_credit(self):
        table = dict(same_shape=True, positional_span_matches=10, complete_shape_text_match=True)
        result = SUMMARY.candidate_summary({'results': [{'published_anchors': 10, 'emitted_tables': [table, table]}]})
        self.assertEqual(2, result['nonempty_tables'])
        self.assertEqual(0, result['exactly_one_complete_topology'])


if __name__ == '__main__':
    unittest.main()
