# Provider-declared header-row prefix

Provider/model header evidence now survives table decoding and PDF extraction as bounded metadata.
It supplies context for later native-token refinement without merging rows or anchors. The known
2/214 education mismatch is **not fixed** by this feature and all previous quality scores remain.

## Contract

`PdfTableLayoutProvider.Table` gains an integer `headerRows` component, constrained to0..rows.
It declares a contiguous prefix of logical atomic rows starting at0. The original five-argument
constructor and accessors remain; the constructor defaults the count to0 (unknown/absent evidence).
Providers are responsible for their declarations. A model can be wrong; this is not calibrated
confidence or independently verified semantics.

Positive declarations produce `pdf_table_header_rows` and
`pdf_table_header_policy=provider_declared_prefix_v1` in native provider, native-first admitted
model, word-OCR and cell-OCR table metadata. Zero produces neither field, preserving historical
undeclared output. The logical count is unchanged by detector-crop translation. No HTML `<td>`
becomes `<th>`, no text changes, no row or span is collapsed, and chunking is unchanged. Anchors
crossing a header/body boundary stay intact; the prefix is not a whole-anchor semantic guarantee.
Native rule tables do not invent provider header evidence.

The ONNX topology decoder now retains class3 column-header objects previously ignored. A row
qualifies when one table-member header object covers at least half of its aligned vertical extent.
Only the contiguous qualified prefix starting at row0 is retained. Missing leading evidence and
later header islands are not filled or promoted. Duplicate evidence does not extend the count;
projected-row-header class4 is not a column-header class. Membership/score thresholds, atomic
boundaries, merge policy and confidence limitations remain. Alignment comparisons charge the
existing shared geometry/deadline/cancellation counter, with no new unbounded arrays or service.

The pinned Microsoft source's align_headers also uses half-height overlap, but can extend a
lower header upward. This implementation deliberately requires actual top-prefix evidence; no
full postprocessor parity is claimed. Its existing MIT attribution remains in the optional
adapter. Source URL/hash and this semantic boundary are in manifest.json.

## Validation

Eight new owned controls cover:

- Stacked declared headers and a distinct data row retain the original atomic shape/anchors.
- Lower-only declarations and header islands stop at missing evidence; ordering is immaterial.
- Exact half-height versus below-half and duplicate/class4 evidence are distinguished.
- A merged anchor spanning header/body is retained without splitting or row merging.
- Native glyph text/HTML/cell geometry stay identical, with and without optional cell coordinates.
- Former construction defaults0 and invalid prefix counts fail at construction.
- Word and independent cell OCR retain the prefix and unclaimed source words once.
- Real ONNX Runtime inference and both detector-crop translations retain a synthetic header.

The native fixture control changes only the owned fixed-table graph's last query from class5
to class3. It requires one exact complete49-float logits-tensor match before substitution;
shapes and all boxes stay fixed. The temporary hash-pinned graph produces two3x2 tables with
six unmerged anchors and one declared header row each. No trained graph, downloads, Python
runtime or source reference enters these ordinary tests. This is native plumbing coverage,
not evidence that learned headers are accurate on independent documents.

The old interface source at e385ff7 was separately compiled for release21. A consumer compiled
against its five-argument constructor (not the new interface) then ran against the updated
classes on actual Java21/25. It checked the original constructor/accessors and reflected a zero
headerRows default. The prior API classes were omitted from the execution classpath. This is
binary-construction evidence, not a claim of compatibility for every record-based consumer.
LegacyTableConsumer.java is included for reproduction: compile the pinned former interface first,
compile the consumer with that old API directory preceding project dependencies, then execute the
consumer with only updated project classes/dependencies (omit the old API directory). Repeat the
execution on both JDKs. This prevents accidentally compiling or running both sides with the new API.

The record now has six components. Five-component source record patterns need updating;
reflection, record serialization, equality/hash/string consumers must account for the new
shape. New six-argument construction requires the new artifact. Existing beta.5 artifacts and
coordinates are unchanged. These upgrade details are included in deferred beta.6 notes.

Full Java21/25 verification passes958 tests per runtime and strict Javadocs. Pinned formatting/
diff checks, previous regression controls and the modified helper's public/private Javadocs pass. Original quality reports/source labels are
not rewritten. Stage-capture diagnostics additionally record numeric header_rows for future
trained-model review, without changing the existing row-stage count fields or inference policy.
The binary consumer uses only owned geometry and records source/output hashes in identities.json.

Fresh upstream metadata still reports0.27.28/main1b02b4d versus historical0.27.27. The strict
release-drift assertion remains an expected failure, not a freshness/parity pass. No reference
pin is changed. No learned-source or full table-text accuracy gain is claimed.

## Reproduce and next gate

Run ordinary `mvn verify` on actual Java21/25. Controlled native fixtures are local and caller
source/model inputs are not needed. Existing live CaptureNativeModelTables captures preserve
these metadata fields automatically; CaptureDetectedTableInput reports header_rows alongside
numeric table shape/anchor counts. Models remain explicit, hash-pinned and caller-owned.

Next replay trained header evidence on the fixed source set, retaining missing/incorrect model
roles and all negative cases, before evaluating token-assisted header/span refinement. A top
header declaration alone cannot distinguish wrapped text from legitimate stacked subheaders.
Recent business/scientific breadth, scan text, calibration and final comparison gates remain
open. Release preparation is deferred; no candidate, signing, tag, prerelease or Central write.
