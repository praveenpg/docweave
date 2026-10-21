# Provider-declared PDF headers reach structural chunks

Native provider tables and both page-word/cell-OCR provider routes already emit
`pdf_table_header_rows` and `provider_declared_prefix_v1`. The shared structural
chunker consumes the format-neutral `table_leading_header_rows` field. The PDF
emitters now expose the same positive declaration through that existing field.
If any anchor crosses the header/body boundary, the neutral hint is omitted while
the original declaration remains. This avoids repeating merged-in body text.
No new public API, inferred header role, topology change or dependency is added.
Source table text, HTML, coordinates and existing declaration provenance remain.

Structural chunks repeat the declared prefix through the existing bounded header
mechanism when enabled and feasible. Whole-source PDF header count/policy fields
are removed from fragments, including text-only fallback; original-element
provenance retains the source declaration when requested. Fragment-neutral header
counts describe the actual emitted fragment. Original/whole HTML keeps the current
serializer convention; only carried headers use the existing `<thead>/<th>` form.

## Owned before/after control

This retrospective regression uses the same owned seven-row, two-column fixture
under all three public provider text routes. Native glyphs and independent OCR
controls use identical authored source positions and row values. It compares
compiled source at `5223e01` with the candidate, changing only the two PDF emitter
classes, shared layout validator and chunker class. Four baseline classes are compiled into an isolated
classpath override; the fixture and all remaining classes are the same.

| Fixed route, one declared header | Before | After |
| --- | ---: | ---: |
| Native: continuation chunks with repeated header |0|2|
| Word OCR: continuation chunks with repeated header |0|2|
| Cell OCR: continuation chunks with repeated header |0|2|

Every route retains all6 body rows exactly once before and after. With declaration
zero, each route still emits2 chunks with no repeated header. With declaration one,
the prior2 chunks become3 because repeated headers also consume the180-character
budget. Extra chunks are expected, not an extraction loss. The complete six-case
reports retain both declared and unknown cases. No original-source table recognition,
NVIDIA strict-row gain or independent header accuracy is claimed.

Four integration tests exercise all routes, source HTML, exact original-element
round trip, body preservation,180-character HTML limits, whole-table behavior,
unknown declarations, disabled repetition, crossing header/body merges and20-character text fallback. Existing
native-layout, provider-continuation and25 structural-table tests pass. Full Java21
verification passes1,164 tests with zero failures/errors/skips; strict public and
private Javadocs pass. Java25 verification remains deferred.

The first new assertion incorrectly expected `<th>` for original headers; the
existing chunk serializer uses that form only for carried headers. The test now
checks the documented original/carried distinction without changing serialization.
An initial standalone probe omitted integration-test classes and failed to load the
fixture; adding that required classpath produced both complete reports. Neither
setup failure is a scored extraction failure or a removed case.

## Reproduction

Build/test the Java21 reactor. Reconstruct the four baseline sources from
`5223e01` as listed in the manifest and compile them into a separate directory
against the same reactor/runtime classpath. Compile this directory's
`ProbeProviderHeaderChunks.java` against that classpath. The probe reflectively
reuses the owned integration fixture; include
`docweave-integration-tests/target/test-classes` and JUnit API dependencies.

Run the probe twice with headless AWT and fresh numeric report paths, putting the
baseline classes before all current module classes for the first invocation only.
The fixture, Java runtime, chunk budget and other module classes are unchanged.
The final formatted probe is replayed on the candidate. The manifest records source
identities; source PDFs, OCR engines and external models are not required.
