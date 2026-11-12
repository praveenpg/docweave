# Standalone image partitioning: Unstructured 0.27.28 comparison and memory (#76)

Evidence for [docs/image-support.md](../../../docs/image-support.md). Inputs are the owned fixtures in
`docweave-image/src/test/resources/io/docweave/partition/image/`, written by `ImageFixtureGenerator` (Java2D text on
white; no third-party content).

## Files

| File | Content |
| --- | --- |
| `upstream-0.27.28.json` | `compare_image_upstream.py` output: Unstructured `partition_image(strategy="ocr_only", languages=["eng"])` on the five fixtures and four malformed inputs |
| `docweave-tesseract-xmx1g.json`, `docweave-tesseract-xmx256m.json` | `ImageOcrInstalledTest` reports (fixture texts, pages, first boxes, warnings, memory rows) at `-Xmx1g` and `-Xmx256m` |
| `Containerfile`, `compare_image_upstream.py` | The reference runtime and script |

## Runtimes (2026-11-09 UTC)

- **Unstructured:** image `localhost/docweave-unstructured:0.27.28` (ID `9ce6103a…`), built from `Containerfile`
  on `ubuntu:24.04` (`sha256:78d4744e…`) with Python 3.12, Tesseract 5.3.4 and the existing pinned freeze
  [`../pdf-upstream-table/requirements.freeze.txt`](../pdf-upstream-table/requirements.freeze.txt) (unstructured
  0.27.28, unstructured-inference 1.6.13, unstructured-pytesseract 0.3.15; CPU torch 2.10.0). Run with
  `--network none`. `hi_res` was not run: it needs a downloaded layout model.
- **DocWeave:** Java 21 (`docweave-image` at this branch), Tesseract 5.3.4 with `eng` in an Ubuntu 24.04 qualification container, run with `--network none`:

```bash
mvn -o -pl docweave-image surefire:test -Dtest=ImageOcrInstalledTest -DargLine="-Xmx1g -Djava.awt.headless=true" \
  -Ddocweave.tesseract.executable=/usr/bin/tesseract -Ddocweave.tesseract.tessdata=/usr/share/tesseract-ocr/5/tessdata \
  -Ddocweave.image.report=/tmp/image-report.json -Ddocweave.image.memory=true
```

```bash
podman run --rm --network none -v FIXTURES:/fixtures:ro -v WORK:/work localhost/docweave-unstructured:0.27.28 \
  /venv/bin/python /work/compare_image_upstream.py /fixtures /work/out/upstream.json
```

Results and their interpretation are in [docs/image-support.md](../../../docs/image-support.md#comparison-with-unstructured-0.27.28)
and [measured memory](../../../docs/image-support.md#measured-memory-tesseract-534-java-21). These are observations on
five owned fixtures, not accuracy scores.

Public documentation omits internal host and container labels; the recorded runtime versions and raw result JSON are unchanged.
