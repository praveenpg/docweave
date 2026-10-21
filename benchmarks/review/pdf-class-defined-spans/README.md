# Cell spans supplied through attribute classes

Explicit source-tagged tables now resolve positive integer RowSpan/ColSpan values
from bounded revision-zero cell attribute classes. Later entries in source class
and attribute-object arrays take precedence. Direct cell attributes retain their
existing strict validation and override the class result. Row/column spans are
not inferred from ancestor elements, style values, text or geometry.

The hidden PdfTaggedAttributeClasses resolver separates presentation-only table/row
validation from cell-span resolution. Class names/definitions retain the existing
64/128 ceilings and shared structural work/cancellation budget. Missing classes,
aliases, duplicate names, malformed values, unsupported namespaces and revisions
retain whole-table fallback. Invalid class spans are rejected even if a direct
attribute could override them. Spans on table/row nodes remain unsupported.

The ordering/precedence rules follow the
[PDF Association's ISO errata](https://pdf-issues.pdfa.org/32000-2-2020/clause14.html)
and [Adobe's ISO 32000-1 text](https://opensource.adobe.com/dc-acrobat-sdk-docs/pdfstandards/PDF32000_2008.pdf).
This is a restricted table-cell subset, not general style/inheritance/revision support.

Three added JVM tests cover class-only row/column spans, direct overrides before
dimension checks, later class/attribute-array precedence, default spans, invalid
types/ranges/owners/namespaces and preservation of public text, spans, coordinates,
outside text and raster placement. Existing malformed class and ownership controls
remain active. Full Java 21 Maven verification passes 1,122 tests with zero failures
or errors, plus strict Javadocs. Strict private Javadocs pass for the resolver,
source planner and owned tests. The internal resolver/test names now reflect their
broader role; obsolete class files were removed by incremental compilation and do
not appear among the executed tests. Java 25 remains deferred.

Before candidate source extraction, both manifests froze the original source and
reference populations, every existing profile, candidate/scorer identities and the
prior reports. All 45 AMD case/profile observations and all 39 Walmart observations
remain exactly equal. AMD retains 81/120 exact rows under its explicit cell-envelope
profile; Walmart retains 105/105. All missing/failed observations and original row
denominators remain. No source labels, normalization, thresholds or defaults changed.

These sources establish regression preservation. They do not establish independently
observed class-defined span fidelity or an additional external row-accuracy gain;
the new class-span functionality is currently demonstrated by owned JVM controls.
Source PDFs/HTML and downloaded images remain outside Git. No new engine, model,
dependency, Python test, release or Central deployment is introduced.

Reproduce with PdfCurrentBusinessRowsTest and the original source caches using the
corresponding manifests and fresh Java 21 report paths. VerifyBusinessRows21.java
compares each report to its declared baseline with no changed profiles. Candidate
source hashes are retained in each manifest and verified against final source bytes.
