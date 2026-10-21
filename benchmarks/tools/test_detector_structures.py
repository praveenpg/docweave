"""Controls for reference-free preparation and geometry-only structure association."""
import unittest
from compare_detector_structures import proposals, structure_metric


def table(box, anchors=None):
    """Build a minimal positional-span fixture without model or OCR dependencies."""
    return dict(box=box, rows=1, columns=1, anchors={(0, 0, 1, 1)} if anchors is None else anchors, profile='test')


class DetectorStructureTest(unittest.TestCase):
    def test_extra_duplicate_cannot_multiply_topology_credit(self):
        reference = table((0, 0, 10, 10))
        result = structure_metric([reference], [reference, reference])
        self.assertEqual(2, result['candidate_tables'])
        self.assertEqual(1, result['exact_topologies_one_to_one_at_iou_0_5'])

    def test_topology_cannot_choose_a_geometrically_unmatched_prediction(self):
        reference = table((0, 0, 10, 10))
        wrong = table((0, 0, 10, 10), {(0, 0, 1, 2)})
        far_exact = table((20, 20, 30, 30))
        result = structure_metric([reference], [wrong, far_exact])
        self.assertEqual(0, result['exact_topologies_one_to_one_at_iou_0_5'])
        self.assertEqual(0, result['matches'][0]['candidate'])
        self.assertEqual(0, structure_metric([reference], [far_exact])['matched_reference_tables_at_iou_0_5'])

    def test_preparation_preserves_thresholded_scores_and_unclipped_boxes(self):
        native = [dict(label='0', score='0.51', left='0', top='1', right='9', bottom='10'),
                  dict(label='1', score='0.9', left='0', top='1', right='9', bottom='10')]
        reference = [dict(label=8, score=0.26, box=[-2, 1, 12, 10]), dict(label=9, score=0.9, box=[0, 1, 9, 10])]
        self.assertEqual([dict(score=0.51, box=[0, 1, 9, 10])], proposals('tatr', native, reference))
        self.assertEqual([reference[0]], proposals('yolox', native, reference))
        with self.assertRaises(ValueError): proposals('oracle', native, reference)


if __name__ == '__main__':
    unittest.main()
