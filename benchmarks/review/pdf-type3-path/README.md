# Type3 glyph drawing-path isolation

An owned Type3 glyph draws and strokes one line while its caller has an unfinished
line. Before isolation the glyph stroke emitted both lines, consuming the caller path.
The regression now observes one glyph line, followed by the original caller line when
the caller strokes. Glyph coordinates use an explicit 1000-unit text transform to
counter the font's .001 matrix; no detection thresholds change.

The existing cancellation/operator-limit control also starts with an unfinished
caller line and verifies that the line survives the failures. The implementation
reuses form-path suspension/restoration, including pending clip construction state,
and restores it in `finally`. Shared operator counts and accumulated evidence remain
unchanged. Tests establish native traversal behavior, not rendered-pixel equivalence
or general PDF text/clipping fidelity.

## Verification

The initial regression fails before the fix: two glyph-stroke segments instead of one.
The focused 17-test graphics set passes after the fix. The final full Java 21 reactor,
including the extended failure control, passes 1,280 tests with no failures, errors or
skips. Strict private Javadocs and scoped formatting pass. Numeric results and source
fingerprints are recorded in [java21.json](java21.json).
