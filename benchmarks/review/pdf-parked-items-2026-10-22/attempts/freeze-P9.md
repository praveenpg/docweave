# P9 attempts freeze (written before scoring)

Attempt 1 (extraction-side check): confirm on the actual source that DocWeave's two cells contain a
single U+0020 at BOTH source separators around each '&' (the source paints U+0009-mapped whitespace
glyphs on both sides), i.e. separator handling is symmetric and source-faithful; plus an owned
regression asserting that separator glyphs on both sides of a short token are kept as single spaces
under the native table text path. Pass = actual cells symmetric and owned test green. Any
asymmetry or dropped separator would be a real extraction defect to fix.

Attempt 2 (additive versioned reference): create reference v2 = original CSV with exactly the two
glyph-evidenced trailing separators restored ("&W" -> "& W"), nothing else. Tool verifies v1 pin,
that v2 differs from v1 in exactly two positions and only by inserting one U+0020 after '&'. Score
the unchanged extraction against v1 and v2. v1 (152/154) remains the historical result; v2 is a
proposed correction pending independent source-policy review, not a replacement. No extraction or
normalization change.
