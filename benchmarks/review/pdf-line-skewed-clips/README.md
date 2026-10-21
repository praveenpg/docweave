# Line-built skewed clipping: false table correction

Before this correction, equivalent clip shapes produced different extraction: an affine
rectangle operator correctly suppressed an invisible grid, while a four-corner move/line
path allowed one false Table to claim six ordinary labels. Their complete rendered pages
are pixel-identical in the owned PDFBox render comparison. After the fix, both sources
retain the same six ordinary text elements with no false Table.

Native traversal retains bounded finite four-corner line paths, with an optional explicit
return to the first corner, and honors the source winding rule. It reuses fixed candidate
storage and the existing deferred intersection/state lifecycle. Larger general polygons,
curves and compound paths remain unsupported. Inherited-clip bounding envelopes reject
only wholly disjoint rule evidence; partial trimming, image cropping and final visibility
are not implemented. Shared operator budgets and source image traversal remain active.

The new regression covers 24 rectangle-versus-line pairs (both winding rules, three paint
modes, four rotations with crop/UserUnit) plus 24 restored-visible-table controls. It fails
before the fix because a Table replaces ordinary text. The focused 21-method graphics set
passes after the fix. Full Java 21 verification passes 1,282 tests with zero failures,
errors or skips. Private strict Javadocs and scoped formatting pass.

See [the numeric receipt](java21.json). This is a real extraction correction on owned
source counterexamples, not a measured original-document table-recovery improvement.
The separate [financial row-gap attempt](../pdf-financial-row-gap-attempt/README.md)
produced no original-source gain and was parked without production changes.
