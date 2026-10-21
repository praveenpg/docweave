"""Independent controls for rounded positive cell interiors, without model or OCR dependencies."""
import unittest
from compare_compact_layout import interior


class CompactLayoutTest(unittest.TestCase):
    def test_fractional_centerlines_round_inward_and_keep_compact_interiors_positive(self):
        self.assertEqual((16, 26, 21, 27), interior((10.5, 20.5, 26.5, 32.5)))
        self.assertEqual((15, 15, 21, 17), interior((10, 10, 26, 22)))

    def test_too_small_prediction_cannot_be_repaired_into_a_positive_interior(self):
        with self.assertRaises(ValueError): interior((10, 10, 20, 20))


if __name__ == '__main__':
    unittest.main()
