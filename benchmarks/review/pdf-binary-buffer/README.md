# Borrowed read-only PDF input views

The framework now exposes an independent read-only `PartitionContext.binaryBuffer()`
view of its owned bounded binary snapshot. Native PDF loading and tagged paragraph
identity hashing use that view instead of requesting another full defensive array copy.
The existing `binaryContent()` contract remains unchanged. Custom contexts inherit a
compatible default that wraps their historical defensive copy.

PDFBox receives a fresh remaining-range reader for each password attempt. Accepted
readers belong to the document; failed attempts close their reader. Password erasure,
permissions and source-position limits retain their existing policy. Standard JDK
read-only views expose no writable backing array and do not share cursor state.

Validation on Java21: full clean verify **1,219 tests**, zero failures, errors or skips;
strict public documentation and standalone source checks pass. Four changed production
files also pass private doclint. Six new controls cover view isolation, caller snapshot
ownership, compatibility with custom contexts, text-only rejection, remaining-range
parsing, password retry/credential erasure and unchanged paragraph fingerprints.
Pinned formatter and diff whitespace checks pass.

All15 complete scientific output fingerprints equal the saved `pdf-empty-text-clips`
baseline. All39 NVIDIA business observations equal that baseline under the same frozen
8c4e990 scorer and13 profiles, with all11 original pages and110 references per profile.
Reports retain source identities and the complete population. No scoring changes or
extraction accuracy gain are claimed. A preliminary comparison additionally requested
an absent `documents` report field and failed; the actual `results` tree was exact in
both comparisons, and the corrected comparison passes without modifying either report.

This removes explicit source-array copies at two call sites; it is not whole-input
streaming. Source snapshots, PDFBox state and output still materialize under existing
limits. No quantitative whole-PDF heap or throughput claim is made. No new runtime
dependencies, OCR invocation, automatic downloads or publication are introduced.
