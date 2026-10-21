# Fixed native-first model transfer on the multi-page release

This follow-up retains the complete original NVIDIA PDF/HTML,110 reference rows, six fixed
original-page regions, association threshold and scoring from the initial native failure.
The frozen manifest appends one uniformly applied configuration; all36 prior observations
remain. References, source regions and issuer identities never enter extraction or inference.

The existing public native-first factory uses RULED_AND_PARTIAL_TEXT and original native
glyphs with explicitly supplied TATR detection/structure graphs. These are the same pinned
graphs used in earlier native-first work: hashes and encoded sizes are frozen here. Policy
is150DPI,16M render pixels,1024 combined native/raw-model anchors, four intra-operation CPU
threads, score threshold0.5,30-second per-call deadlines and KEEP_HIGHER_SCORE structure
conflicts. Both graphs retain256MiB encoded/input16M-pixel/query256/one-call bounds and the
existing maxHW800 bilinear/ImageNet preprocessing. No OCR or model download is performed.
The evaluator owns and closes the managed provider after complete-document extraction.

The model profile obtains geometry associations in3/6 original page regions and emits66
observed rows across those associations. Strict original row matches remain0/110, and no
statement has all its original regions associated. This does not establish recovery of three
correct semantic tables: a broad model envelope can overlap one region while combining
content across neighboring tables. The unchanged score retains that failure. It is an
existing configurable-path diagnostic, not a new production feature or accuracy improvement.

Both actual Java21/25 clean full builds pass1,109 tests, zero failures/errors/skips and strict
public Javadocs. The complete39 case/configuration records agree across runtimes. All36
earlier NVIDIA observations and124 earlier business observations remain unchanged on both
JDKs. Frozen scorer and baseline-report hashes are verified; strict private evaluator docs
and the pinned formatter pass. No production/default/dependency, release or Central changes.

This adds an explicit test-only `NATIVE_FIRST_ONNX_TABLES` profile. It requires local paths
and expected hashes via `docweave.pdf.current.business.detector`, `.detector.sha256`,
`.structure` and `.structure.sha256`; absent/invalid configuration is never silently replaced.
Use the same original-cache/manifest/fresh-report JVM test command documented in the prior
experiment, selecting this manifest and supplying those four properties. Verify paired
reports using the committed `VerifyMultiplePageRows.java` in the prior evidence directory.
No alternative model/window/crop/threshold is selected after reference scoring.

Remaining diagnosis: distinguish merged detector regions, rejected structure candidates
and incomplete continuation layouts before proposing a source-independent extraction change.
Keep all reference rows and the native/model failures when evaluating that change.

A separate Java21 full-original capture with the existing `CaptureNativeModelTables.java`
records numeric parent geometry only in `parent-geometry-audit-java21.json`. Page5 emits
one32x5 grid with bounds approximately[56,27,557,751], crossing both the original income
continuation and balance-table start. Page6 emits a12x4 parent starting nearx308, excluding
the source label column. Page4's12x5 and page7's26x5 envelopes omit some source heading or
continuation material. These are observable output defects; the audit does not yet attribute
each to detector output versus structure decoding. It does not alter labels or scores.
Raw captures contain publisher text and remain outside Git. Headless rendering is required
for the standalone capture, as it is for the Maven validation runs.
