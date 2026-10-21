# Official consultation forms

This corpus adds two real-world blank forms from different publishers and templates
to the existing NRS report family and Apache POI samples. It is a bounded extension,
not a representative accuracy benchmark. Independent human review is pending.

Sources and licence statements were checked on 2026-10-16:

- [Law Commission supplementary consultation response form](https://lawcom.gov.uk/publication/response-form-supplementary-consultation/).
- [Scottish Government respondent information form](https://www.gov.scot/publications/respondent-information-form/).

The manifest records exact download URLs, SHA256, byte sizes, source pages,
attribution and Open Government Licence v3.0 links. Graphic assets and other
stated exclusions are not relicensed. Downloaded binaries, assets, package XML
and full extracted text stay in a fetch-only local cache. Measurement reports contain
text-free diagnostics; the provenance manifest also retains selected short text anchors
with the publisher attribution above. Do not substitute completed respondent forms.

## Results

| Input | Stored body paragraphs | Nonblank paragraphs checked | Tables | Content controls | Default elements | With stored headers/footers |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Law Commission | 88 | 56 | 8 | 5 | 64 | 64 |
| Scottish Government | 41 | 25 | 0 | 8 | 25 | 29 |

Both default and stored-part modes passed selected text anchors, expected table
minimums, stored Header/Footer element counts, zero-warning checks and normalized
substring presence for every nonblank body paragraph. The Scottish form adds three
Footer elements and one Image reference when stored parts are enabled. Its package
contains three header files, but none emits a Header text element; stored file
counts are deliberately distinguished from extracted text element counts.

There are no nested paragraphs or tables in these inputs. Content controls are
inventoried as stored XML; this review does not validate interactive form behaviour,
checkbox state, symbol-font glyphs, field evaluation or layout. Paragraph substring
presence does not prove multiplicity, order, ownership, correct categories or visual
fidelity. Inventory counts include revision and fallback branches. No upstream
reference was generated for these files and no upstream parity is claimed.

## Reproduce

Fetch the exact manifest URLs into a local cache. From repository root, build the
runtime JARs/classpath as described in the existing [DOCX corpus](../docx/README.md),
then use fresh output paths:

```sh
mvn -pl docweave-validation -am test \
  -Ddocweave.inventory.manifest=benchmarks/corpus/docx-forms/manifest.json \
  -Ddocweave.inventory.cache=/local/cache \
  -Ddocweave.inventory.report=/tmp/new-forms-inventory.json

mvn -pl docweave-validation -am test \
  -Ddocweave.corpus.manifest=benchmarks/corpus/docx-forms/manifest.json \
  -Ddocweave.corpus.cache=/local/cache \
  -Ddocweave.corpus.report=/tmp/new-forms-review.json
```

The Java ownership runner now pins manifest/validation-source hashes and the actual
ordered runtime classpath hashes (JARs or reactor class directories), Java version
and input hashes. Historical Python reports retain their original tool/launcher/JAR
hashes. Reports are never overwritten. Normal Maven tests make no corpus downloads.
The legacy substring/upstream reviewer is also Java/JUnit; select docweave.corpus.baseline=substring and optionally docweave.corpus.reference. The saved Python reports remain unchanged.

The separate ownership-review.json reruns both files with the opt-in
[ownership-v1 baseline](../../review/docx-paragraph-ownership/README.md). It passes
64/25 owners and56/25 nonblank paragraphs in both modes, with no unexpected body
elements. ownership-body-scope-review.json repeats this after the other-part text-box
scope correction, with unchanged results. Historical inventory/review files remain unchanged. This adds owner order,
kind and distinct-occurrence checks; neither form contains a text box or nested table.

Next coverage targets are real-world nested tables, text boxes, tracked revisions
and a different authoring application. Ownership-v1 supports a documented subset;
AlternateContent selection and blank text-box hosts still need separate evidence.
Human rendering review remains separate. The Windows VM is not required.
