# Two-rectangle compound clipping

Two identical source rectangle subpaths cancel under even-odd clipping. Previously the
compound clip was ignored and a wholly hidden grid could claim ordinary page labels as
one false Table. The bounded two-rectangle subset now retains the source winding rule:
the hidden grid supplies no table evidence, while nonzero winding retains its visible table.

Candidate construction retains at most two finite affine rectangle-operator subpaths.
A third invalidates the candidate, rather than accidentally applying only two of its
rectangles. Larger compound paths, curves and general mixed construction remain unsupported.
Filled-rule recognition still requires a single eligible subpath. Deferred clipping, saved
state/form restoration, source images and shared operator budgets retain their scope.
The inherited envelope rejects only wholly disjoint or known-empty evidence; it does not
trim partial rules or determine paint visibility inside holes of a nonempty clipping area.

The new owned method covers four rotations and three paint modes: 12 invisible even-odd
cases, 12 nonzero-visible cases, 12 restored-visible cases and 12 three-rectangle visible
fallback controls. Hidden cases are full-page raster-identical to no-grid sources; visible
controls are raster-identical to the ordinary grid. The test fails before the fix on the
false Table. PDFBox rendering is used for these owned pixel controls; no independent
Poppler or original-corpus gain is claimed for this change.

The focused 22-method graphics set passes before the final three-rectangle extension.
The final full Java 21 reactor includes that extension and passes 1,284 tests with no
failures, errors or skips. Strict private Javadocs, scoped formatting and diff checks pass.
See [the numeric receipt](java21.json). No OCR/model or Java 25 run was needed.
