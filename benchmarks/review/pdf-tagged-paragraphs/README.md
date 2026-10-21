# Explicit source-tagged native paragraphs

This opt-in policy groups native text using supported source paragraph tags. It requires
complete, unique, single-page glyph ownership and one upright column. Tables claim glyphs
first. Unsupported or ambiguous paragraphs retain geometric extraction; this does not
establish logical page order, bidirectional correctness or general PDF accuracy.

The manifest retains all 25 independently selected publisher paragraphs and 108 order
pairs per profile from `../pdf-scientific-reading/manifest.json`. It adds two uniform
profiles before extraction, without changing reference text, source files, scoring or
candidate-dependent selection. All three complete original PDFs are processed.

Source-soft-hyphen joining improves exact paragraph matches from 1/9 to 6/9 and 2/12 to
10/12 on the two tagged PLOS documents. Preserved order pairs improve from 0/36 to 15/36
and 1/66 to 45/66. The untagged eLife document remains 0/4 and 0/6. Missing and ambiguous
matches remain in every denominator. These are development measurements, not held-out
accuracy. The preservation profile retains physical hard hyphens.

An initial implementation incorrectly required an explicit optional StructElem Type;
the corrected implementation accepts its standard omission while still requiring the
paragraph role. Initial runs and raw documents remain outside Git. The owned regression
suite includes omitted Type, malformed roles/references, shared ownership, form streams,
cross-page references, right-to-left text, tables/images, cancellation, resource limits,
password configuration and concurrent reuse.

Run the existing JVM scientific-reading test with
`docweave.pdf.scientific.reading.manifest=benchmarks/review/pdf-tagged-paragraphs/manifest.json`
and an explicitly configured source cache and fresh report path. The shared
`../pdf-scientific-reading/VerifyReports.java` checks complete cross-JVM report equality.

Both full builds pass 1,018 tests with zero failures, errors or skips and strict public
Javadocs. Changed private implementation/test Javadocs pass on both JDKs. All 15 complete
numeric/hash result rows agree across Java21/25; all nine existing baseline profile rows,
including full candidate-text hashes, remain unchanged. Reports and verification are
retained here. The initial optional-Type error is not represented as a successful result.
