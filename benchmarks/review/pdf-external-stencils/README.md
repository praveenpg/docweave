# Original stencil/pattern source regression

Three complete original Apache issue samples process successfully under both the
retained pre-fix and current packaged PDF JARs on Java21. All **186 emitted PNGs**
remain valid and byte-identical, and complete public output is equal after removing
only the candidate's two declared stencil labels. The183 pattern-stencil occurrences
now identify their payloads as stencil_mask_samples_v1. This tests admission,
provenance and fallback stability, not rendered-pattern or semantic figure quality.

| Original issue sample | Pages | Poppler stencil / ordinary entries | Emitted PNGs | Missing / extra inventory occurrences |
| --- | ---: | ---: | ---: | ---: |
| PDFBOX-6077 example | 1 | 2 / 0 | 2 | 0 / 0 |
| PDFBOX-5842 reduced | 1 | 1 / 1 | 1 | 1 / 0 |
| PDFBOX-5403 bad rendering | 3 | 180 / 3 | 183 | 0 / 0 |
| Total | 5 | 183 / 4 | 186 | 1 / 0 |

Every one of the187 independent inventory occurrences remains in the report. The
unmatched669x97 ordinary raster is Poppler object937:0, found in
Page1/Resources/ExtGState/GS82/SMask/G/Resources/XObject/Im0: it belongs to a soft-mask
form. Current direct source-image extraction does not emit it as a standalone Image.
This source-only qualification does not remove the miss, infer standalone figure
geometry or claim complete pattern/soft-mask composition. Poppler's list is an
independent extractor inventory, not an exhaustive semantic figure annotation set.

## Selection and source integrity

Before any fixture fetch or DocWeave extraction, selection.json fixed all three
original attachments named by the official PDFBox stencil-pattern regression tests
at commit276388d0c2ce3f26fafcb2f6e7da2acdd8a73c9e. This is an explicitly pinned source
revision, not a claim about the latest release. Each original matches the published
SHA512 in that revision's pdfbox/pom.xml and stays below the fixed8MiB fetch cap.
All sources, transcriptions, raw captures and model-free raster payloads remain
fetch-only/outside Git; no attachment redistribution license is assumed.

Attribution and source definitions:
[official regression tests](https://github.com/apache/pdfbox/blob/276388d0c2ce3f26fafcb2f6e7da2acdd8a73c9e/pdfbox/src/test/java/org/apache/pdfbox/rendering/TestQuality.java),
[official attachment identities](https://github.com/apache/pdfbox/blob/276388d0c2ce3f26fafcb2f6e7da2acdd8a73c9e/pdfbox/pom.xml).
Their page-composited pixel assertions are deliberately not used as source-mask PNG
expectations. The initial source-only PDFBox operator/paint inventory decoded no
image pixels and counted183 pattern stencils and three direct ordinary images.
It does not visit auxiliary soft-mask/pattern paint contents and shares PDFBox with
the product; it is not an independent figure or pixel correctness oracle.

The complete Poppler inventory was captured before the first product run. A separate
manifest then fixed all three files, original bytes/hashes/pages,187 inventory entries,
packaged library identities, unchanged native defaults except includeImageBytes=true,
and the full-output comparison rule. There are no per-case settings, crops, OCR,
models, source repairs, rejected-case removal or best-of choices. These are previously
published issue examples, not random business documents or held-out acceptance data.

## Reproduction and verification

Compile the existing CapturePdfDefaults.java at release21 against public APIs and its
Jackson serialization dependencies. Payload mode requires a packaged PDF service JAR
for the runtime identity receipt. Run the identical compiled tool once per library
version with that version's core/PDF JARs, the same PDFBox3.0.8/logging1.4.0 dependencies,
Jackson dependencies and source cache. Do not mix versions or reactor classes:

```sh
java -Xmx512m -Djava.awt.headless=true -cp "$capture_classes:$packaged_runtime" \
  dev.docweave.validation.CapturePdfDefaults \
  benchmarks/review/pdf-external-stencils/manifest.json /path/to/verified-originals \
  /path/to/fresh-raw-captures /path/to/pdfimages payloads
```

The optional fifth literal payloads enables only PNG payloads. Four-argument default
capture remains unchanged. Runtime receipts bind the actual PDF JAR, Java version,
manifest and Poppler executable. Each complete original is processed once per version;
typed failures retain empty failed output and the complete external inventory.

Compile VerifyPayloadRegression.java against Jackson, then supply manifest, original
source cache, old captures, new captures and a fresh numeric report. It revalidates
published SHA512, frozen SHA256/byte counts, all runtime/tool/source-protocol identities,
all cases/pages/reference multiplicities, PNG MIME/dimensions/decodability and exact
full parsed output after removing only image_stencil/image_pixel_policy labels.
PNG base64, element IDs, text, geometry, all other metadata, warnings and outcomes are
not excluded from comparison. All186 PNG byte identities are retained numerically.

A valid-PNG corruption control flips one original mask sample while preserving PNG
dimensions. The verifier records all three cases and all187 references, reports false
full-output equality and exits unsuccessfully. It does not hide the changed pixel or
the existing auxiliary-image miss. Original successful evidence stays untouched.
Compilation, strict private Javadocs and formatter/diff checks pass on Java21.
Production stays at the preceding1,130-test full verification; this evidence-only
follow-up adds no production code/dependency/default or Central operation. Java25
remains deferred. Independent original solid-fill stencil accuracy, rendered pattern
payloads, captions and semantic figure acceptance remain separate open boundaries.
