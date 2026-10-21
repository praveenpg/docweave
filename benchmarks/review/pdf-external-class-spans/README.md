# Independent class-span source discovery

The selection protocol was frozen before downloading or extracting candidate PDFs.
The official PDF Association reference archive returned HTTP 403, so discovery moved
to the official public [veraPDF corpus](https://github.com/veraPDF/veraPDF-corpus),
explicitly pinned to the master revision recorded in selection.json. This is not a
claim that master is its latest/default branch. The repository README states CC BY
4.0; attribution is to the veraPDF corpus contributors. Originals remain fetch-only.

All seven pass PDFs in the PDF/UA-2 table clause were selected, including every
metadata variant. Original byte counts and Git blob SHA-1 identities were checked
before source inspection. A Java 21 raw PDFBox inventory, without DocWeave extraction,
found no ClassMap definitions or cell C references in any of them. No eligible
class-defined span case was found. Nothing was mutated to manufacture that evidence.

Independent class-defined span validation remains open. The discovery outcome is
not output accuracy, a completed quality gate or general PDF/UA compliance. The
sources were subsequently used for a separate
[whole-original table topology check](../pdf-tagged-reference-tables/README.md).

source-inventory-java21.json retains every selected file, source hash and zero
eligibility result. Inventory counts inspect original root ClassMap definitions and
cell C references; product scoring uses the checked-in JVM comparison tool in the
linked evidence. No source PDFs, derived rasters, Python test or dependency is added.
