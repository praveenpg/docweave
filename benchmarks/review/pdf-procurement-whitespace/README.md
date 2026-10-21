# Procurement residual whitespace discrepancy

The historical 14x2/14x8 fragmentation was already corrected by
[implicit filled-rule closure](../pdf-implicit-filled-rules/README.md). Current complete-source
native extraction yields one 14x11 table on the selected page. All 154 saved CSV positions
remain: 152 match, including 11/11 header and 141/143 body positions. This review adds no
production change or extraction gain.

The two mismatches are a space following an ampersand: the saved CSV joins the following
word, while native extraction retains a space. Independently executed Poppler layout text
also retains that space in both source rows. This is not independent human semantic review,
but it does not support deleting source spaces to improve the saved-reference score.
The original CSV and score are preserved; no normalization relaxation or reference repair
is made. Further action requires source/reference review rather than table-fragment joining.

A local JVM evaluator reuses the pinned header evaluator's projection/ASCII normalization
on the full five-page original, with the original PDF and CSV SHA256 identities and all
14x11 positions. [The numeric report](java21.json) excludes source text. Raw glyph/rule,
render and Poppler text inspection remain outside Git. No Python test, new dependency,
model or OCR execution was used. This review is distinct from the subsequently implemented
two-rectangle source-clip correction.
