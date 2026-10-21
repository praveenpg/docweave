# Explicit native cell script runs

Production basis:0775a8c, Java21. The opt-in `INLINE_SCRIPT_RUNS` policy reuses the
bounded whole-run interpreter inside existing provider and source-tagged cells.
It does not move source glyphs, change cell ownership/topology, rewrite characters,
join dates/hyphens or alter OCR/ordinary text. Default policies remain unchanged.

Eight owned font-placement cases cover one-/two-glyph raised/lowered runs through
both public extraction routes. Each retains authored `alpha12beta`-style word order
where the historical baseline sorts the script separately. Original cell and parent
coordinates and outside text remain equal. Original character limits are enforced
before interpretation; rotated source tables and ordinary wrapped dates retain their
historical output. Existing small-run ambiguity, overlap, distance, length, work and
interruption controls remain applicable. Whole-table glyph counts stay in original
tables and chunk provenance, and are removed from structured/text-only fragments.

Full Java21 verification passes1,143 tests including the optional original-image
regression. Changed production/private test Javadocs, compiler and formatter pass.
Java25 remains deferred; there is no release or default change.

## Original-source representation check

The complete previously used PLOS0324609, PLOS0330904 and eLife90049 PDFs run under
both supported cell axes and explicit logical cell text envelopes. Both policies
use the same current binary, source-first extraction and unchanged closed-rule/
ordinary fallback, without a model or OCR engine. Coordinate arrays are enabled only
for supported axes, as required by the logical-geometry API. A preliminary harness
attempt incorrectly requested coordinates for logical geometry and failed during
configuration; the corrected uniform profiles are the reported complete run.

| Original | Geometry profiles | Successful pairs | Table observations | Changed tables | Assigned script glyphs |
| --- | ---: | ---: | ---: | ---: | ---: |
| PLOS0324609 |2|2|2|0|0|
| PLOS0330904 |2|2|2|0|0|
| eLife90049 |2|2|0|0|0|

These are six profile observations of three originals, with two distinct source
tables observed twice each. eLife supplies no accepted table in either selected
profile and stays in the report. All ordinary elements and other table metadata
remain exactly equal. HTML cell roles/attributes and each cell's exact character
inventory excluding ASCII joining spaces are checked; NBSP and all other Unicode
characters are retained in the inventory. No reference text, fuzzy matching or
character replacement enters extraction or comparison. Typed extraction failures
and representation discrepancies remain in the complete numeric report.

No qualifying script run is assigned on these originals. This is regression evidence,
not an original-source script accuracy gain, mathematical interpretation, held-out
acceptance or a DocWeave/Unstructured ranking. The earlier publisher row/span scores
and their stricter normalization remain unchanged; this diagnostic does not rescore them.

## Reproduction

Verify `input-identities.sha256`. Obtain the exact originals from the pinned input
metadata in [PLOS0324609](../pdf-recent-scientific-headers/manifest.json),
[PLOS0330904](../pdf-scientific-next-source/manifest.json) and
[eLife90049](../pdf-scientific-second-publisher/manifest.json). Source PDFs remain
fetch-only. Create a local TSV with those three case IDs, complete PDF paths, byte
lengths and SHA256 values, in that order. Compile the committed Java diagnostic
against the existing DocWeave/JSON/jsoup runtime classpath, then run:

```sh
javac --release 21 -cp "$runtime_classpath" -d "$audit_classes" \
  benchmarks/tools/CompareScientificCellScripts.java
java -Djava.awt.headless=true -cp "$audit_classes:$runtime_classpath" \
  dev.docweave.validation.CompareScientificCellScripts \
  /path/to/pinned-inputs.tsv /path/to/new-report.json
```

`java21.json` contains the complete numeric result. Its input-TSV hash identifies the
original local protocol, including local paths; relocating source files changes that
hash while the reported source-byte identities remain the same. The utility refuses
existing report paths and verifies original file lengths/hashes before extraction.
