# Trailing financial rows: source-support feasibility check

The three structural NVIDIA misses remain parked. This bounded check tested a distinct
hypothesis: source structure tags or painted table rules could independently support a
safe extension through the blank row below the page-7 provider proposal, instead of
relaxing the already parked baseline-gap threshold.

On the same SHA-pinned original NVIDIA PDF (`89de999d7155649197672bf490c519607f1abbcdc96e2e4c6718bf6ecbb13564`),
PDFBox reports no document structure tree. The existing bounded `PdfPageGraphics` visitor
records no horizontal segment longer than 100 units or vertical segment longer than 10
units intersecting the displayed source band y=573.6 through635 on page7. Those dimensions
are a stated source-rule feasibility check, not a detector tuning sweep. The original
proposal bottom and missing row baselines606.5/624.4 are documented in the
[complete classification](../pdf-business-miss-classification/README.md).

Therefore this source-supported extension has no evidence to act on for the target case.
No runtime candidate was justified, no bounds widened, and no extraction/scorer/reference
changed. A nearby label plus aligned numbers alone can also describe a separate note;
this check does not establish that any particular rule would absorb footnotes, because
no such runtime rule was implemented or tested. Future trailing-row candidates still need
owned footnote-absorption negatives and a separately supported membership signal.
The third structural miss is a previous-page heading and is not addressed by a page-local
trailing-row proposal. This is diagnosis, not a quality improvement or a new accuracy score.

`probe-source.java.txt` is the exact JVM feasibility probe. Compile against the existing
PDF validation/runtime classpath, with current PDF classes first, and run
`dev.docweave.partition.ProbeTrailingSupport` with the original PDF path. Numeric output:

```text
structure_tree=false
page=7; source_band_top=573.6; source_band_bottom=635.0; long_horizontal_rules=0; vertical_column_rules=0
```

The investigation moved to the distinct page-level OCR gutter hypothesis. No further
row-gap, crop, numeric grammar or model threshold attempts were made here.
