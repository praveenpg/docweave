# Explicit native form-depth failures

PDFBox stops traversing form XObjects beyond 50 nested invocations. Previously,
this could return a successful extraction with a missing leaf image. Native graphics
traversal now reports `INPUT_TOO_LARGE` for an invocation of the 51st form.

Owned fixtures exercise finite depths 50 and 51 and a self-referencing form, for
both ordinary forms and transparency groups. All six controls pass. An image inside
the 50th form remains eligible; images do not consume another form nesting level.
The limit is independent of the 64-level Type3 glyph limit and shared operator budget.
It is not a general bound on parser, decoder, heap or JVM stack use.

## Validation

`PdfFormDepthTest` contains three methods covering both form variants. The focused
19-test graphics set and full Java 21 reactor pass. Strict private Javadocs also pass.
See [the receipt](java21.json) for totals and source fingerprints.

A standalone Java 21 consumer also ran the six owned controls through `DocWeave.create()`
using only the packaged core and PDF JARs, PDFBox, pdfbox-io, FontBox and Commons Logging.
It excluded reactor classes, test classes, JUnit, Jackson, jsoup and POI from its runtime.
The consumer reused the test's source-authoring controls with local assertion helpers.
No external model or service was required.
