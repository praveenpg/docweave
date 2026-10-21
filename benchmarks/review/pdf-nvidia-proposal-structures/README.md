# NVIDIA detector-to-native-text diagnostic

Subsequent integration: the [managed alternative provider](../pdf-managed-yolox/README.md)
now reproduces these frozen geometries through the public API. This preserves the
strict results below; it adds no row-text accuracy claim.

Java 21 only. The original frozen NVIDIA source and all 110 publisher reference rows
remain unchanged. This is a benchmark replay, not a production detector integration.
The input manifest was frozen before structure inference; replay-manifest.json pins
the resulting captures before native extraction, and replay-tool.json pins the tool.

Both profiles processed all 11 original pages successfully. Every provider callback
matched the original rendered PNG hash. The unchanged JVM scorer selected tables by
geometry, never text, and retained missing rows and complete statement denominators.

| Profile | Associated regions | Observed rows | Exact ordinal rows | Complete statements |
| --- | ---: | ---: | ---: | ---: |
| TATR detector + TATR structure | 3/6 | 66 | 0/110 | 0/3 |
| YOLOX detector + TATR structure | 6/6 | 90 | 0/110 | 0/3 |

YOLOX separates the adjacent statements and restores coverage of the label column,
but structure inference still omits or merges source rows. The extra observed rows
are not a count of correct rows. Missing title/header rows also shift strict ordinal
comparison; the scorer deliberately does not realign or repair output. This experiment
does not establish full Unstructured parity or a production extraction improvement.

CaptureDetectorStructures.java now accepts generic safe capture identifiers. Existing
capture semantics are unchanged. ReplayNativeLayoutCaptures.java feeds the captured
model geometry through the public native-first partitioner and reuses the existing
validation scorer. Legacy captures lack header evidence, so headers are unspecified;
this is row-content diagnosis, not metadata parity. Numeric captures and original
rasters remain in the local cache; manifests retain their identities.

Validation: Java 21 compilation, strict private Javadocs, full original-document replay
for both profiles and exact raster/source/capture hash guards passed. Java 25 is deferred.
Next: inspect row/column structure failures without changing the frozen references,
before deciding whether an alternative detector merits a production provider.

Manual inspection of the original page 4 raster confirms two visible date/year
baselines. The model puts them in one cell row and native extraction joins their
text with spaces. Title lines remain ordinary text above the model-owned table.
Some strict reference differences also come from HTML presentation spaces before
closing numeric parentheses; the visible PDF has compact parentheses. These
observations do not amend the reference or improve its score. A local membership
diagnostic was used only to locate differences, never as a replacement metric.

Verify the saved populations on Java 21 by compiling VerifyReplay.java together
with ../pdf-unseen-business-nvidia/VerifyMultiplePageRows.java. Run VerifyReplay
with this evidence directory, the compiled class directory, and a fresh receipt.
It reuses the historical region arithmetic, checks source/tool protocol identities
and retains all six case/profile records and twelve region/profile observations.
