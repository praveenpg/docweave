# Explicit page fragments for source paragraphs

`PdfTaggedParagraphOptions.PageBoundary.PAGE_FRAGMENTS` permits a source P element
whose unique ordered references advance across consecutive original pages. Each page
fragment is independently validated using the existing native ownership, script, geometry,
character, work and cancellation guards. Backward/revisited/skipped page transitions and
malformed references reject the source plan. Source tables still claim glyphs first.

Accepted fragments retain original page coordinates and the document-local source P ordinal.
Their one-based fragment index and total planned fragment count describe source structure,
not accepted output/chunk counts. A rejected native fragment remains ordinary text; valid
siblings may still group. Discretionary joining never crosses pages or borrows another
page's same-numbered MCID. No source text is dropped or logical page order invented.

The default remains SINGLE_PAGE. The original three-argument options constructor remains
binary-callable, with SINGLE_PAGE behavior. `LegacyOptionsConsumer.java` was compiled
against the signed beta.6 candidate JAR and executed against the changed classes on actual
Java21/25. The public options record gains a fourth component; source record patterns,
reflection/serialization and equality/hash/string shape consumers need review.

## Fixed original-source replay

The manifest preserves all four original source identities and references:28 paragraphs
and111 order pairs per profile. Every complete document runs under all five previous
profiles plus explicit page fragments. All20 previous numeric/hash profile rows are unchanged.
The new profile retains exact paragraph matches6/9,10/12,0/4 and0/3, with preserved order
pairs15/36,45/66,0/6 and0/3. All whole-document extractions succeed. This feature does
not improve these strict full-paragraph scores; running headers/sidebars and other text
mismatches remain. No reference normalization, denominator or production threshold changed.
All four sources are now development evidence, including the previously selected holdout.

The owned continuation control covers reused MCIDs, page-local soft-hyphen hints, partial
native fallback with retained text, original page metadata/geometry, backward and skipped
pages, default behavior and immutable configuration copies. Existing table/image, password,
limit and concurrency controls remain. Both full builds pass1,019 tests, zero failures/errors/
skips and strict public Javadocs. Changed private implementation/test and legacy-consumer
Javadocs pass both JDKs. All24 complete numeric/hash rows agree between JVMs; the paired
reports, complete-coverage receipt and20-row baseline verification are retained here.
The explicit original-source mode changes candidate text on all three tagged documents;
the untagged eLife document remains unchanged. This is grouping evidence, not improved scores.

Run `PdfScientificReadingOrderTest` with this manifest, an explicitly verified cache containing
recent/next/elife/holdout original PDF/XML pairs and a fresh report path. Reuse
`../pdf-scientific-reading/VerifyReports.java` for full cross-JVM equality and denominators.
Raw publisher sources and build caches stay outside Git. This is an unreleased development
addition after the immutable beta.6 candidate. No release or Central status request occurs.
