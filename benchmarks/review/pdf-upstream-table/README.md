# Pinned Unstructured comparison

This answers the specific question whether Unstructured extracts the table that DocWeave
rejects in PMC1626454_002_00.png. It returns a table, but does not extract the full published
structure/text correctly under the configurations tested. This is one development-selected
PubTabNet train image, with unknown model-training overlap, not a general comparison or accuracy estimate.

| Profile | Rows × columns | Cells | Matching positional/span keys | Exact positional/span/text cells |
| --- | --- | ---: | ---: | ---: |
| Published reference |9 ×12 |100 |100 |100 |
| DocWeave structure-only default | No table |0 |0 | Not evaluated here |
| Upstream postprocessing of the exact27 DocWeave decoded objects |9 ×12 |99 |98 |26 |
| Upstream table recognizer, same source weights and rendered image |9 ×12 |99 |98 |26 |
| Public upstream partition_pdf, hi_res, default model identities with pinned local files |7 ×10 |70 |61 |2 |

All upstream profiles have incomplete topology and incomplete topology/text. Text matches require
the same row, column and span too; a row offset can reduce these counts even where text is present.
These are single-table literal comparisons, not probabilities or general OCR accuracy.
The Python comparator parses HTML with BeautifulSoup and collapses ASCII whitespace, preserving
case/punctuation; its text counts are distinct from the historical Java/Jsoup corpus measurements.

The isolated profile uses the actual unmodified upstream objects_to_structures/structure_to_cells
functions with DocWeave's thresholded objects. It also uses upstream-generated OCR tokens,
which upstream postprocessing can use to refine axes. No annotation tokens are passed as OCR.
The shared-weights profile uses the same source safetensors as DocWeave's ONNX conversion,
but upstream's preprocessing/padding and PyTorch execution. It is not an ONNX numerical-parity test.
The public profile calls unmodified partition_pdf with infer_table_structure=True,
languages=['eng'], pdf_image_dpi=300 and hi_res_model_name='yolox'. Model initialization uses
upstream's supported local configuration and tables_agent.initialize methods with the published
default model identities. It uses Poppler rendering/detected crops, rather than the PDFBox
whole-table raster used by the other profiles. It does not represent the commercial hosted API.

The exact rendered input and decoded-object TSV match across Java21/25 captures, and all reported
metrics match. Captured PDFs may have different generated document IDs. Python inference runs
in Python3.12.15 on both captures; this is not a claim that Python executes on a JVM.
Unstructured0.27.28, inference1.6.13, transformers4.57.6 and CPU PyTorch2.10.0 are explicit pins.
Default table loading emitted upstream warnings about initial backbone copies into meta parameters;
loading and inference subsequently completed. No source patches, warning-based result repairs,
query deletions, model substitutions or golden-reference repins were applied.

The concrete conclusion is that DocWeave's conservative merge rejection is a capability gap worth
addressing. Upstream's overlap handling avoids complete rejection on the same decoded objects,
but does not recover all100 reference anchors. Dropping a low-score query on this development
image does not establish a safe general correction. Evaluate any explicit conflict-resolution
strategy across the complete fixed corpus, with crossing/nonrectangular/overlapping controls,
resource bounds and unchanged default regression evidence before claiming improvement.

## Reproduction

Original assets, generated PDF/PNG, OCR text, model weights and raw outputs stay local.
manifest.json pins source identity, model revisions/hashes, wheel hashes and captured Java class
fingerprints. comparison-java21.json and comparison-java25.json contain counts/settings/hashes only.
requirements.freeze.txt records the isolated Python environment. The Python files execute upstream's
Python reference API; the Java library acquires no Python runtime dependency.

1. Fetch the original image and JSONL using ../pdf-pubtabnet/manifest.json. Fetch the model/config
   files listed in this directory's manifest and verify their sizes/hashes. Keep a separate directory
   for the shared table weights and the default table weights. Populate an explicit local Hugging Face
   cache with the pinned timm backbone under models--timm--resnet18.a1_in1k/snapshots/REVISION/
   and refs/main containing that revision. The default table config requests pretrained backbone
   initialization even though the complete table weights are loaded afterwards.
2. Create a separate Python3.12 virtual environment. Install torch==2.10.0 and torchvision==0.25.0
   from https://download.pytorch.org/whl/cpu, then install requirements.freeze.txt in that environment.
   Pin/fetch the English spaCy wheel using its recorded URL/hash. Keep all caches in a writable
   local directory. These are explicit reference-environment setup steps, not automatic product downloads.
3. Build the Maven reactor and generate a test dependency classpath, for example:

```sh
mvn -B -ntp -pl docweave-validation -am package dependency:build-classpath -DskipTests -DincludeScope=test -Dmdep.outputFile=target/reference-classpath.txt
javac -cp "$(cat docweave-validation/target/reference-classpath.txt)" -d /tmp/capture-classes benchmarks/tools/CaptureTableReferenceInput.java
java -Djava.awt.headless=true --enable-native-access=ALL-UNNAMED -cp "/tmp/capture-classes:$(cat docweave-validation/target/reference-classpath.txt)" dev.docweave.layout.CaptureTableReferenceInput /path/to/original.png /tmp/new-capture /path/to/structure.onnx ff1d19a18cb5f041c1692ff681f891a5180f9430d68a71d2cf7a0a2e8e55b990
```

Use actual Java21 and25 installations for separate captures. Output directories must be new.
The helper uses the corpus's entire-image150DPI embedding and300DPI PDFBox render, preserving
the source and all27 decoded queries. Native sessions/images/streams have explicit ownership.

4. Put the pinned Tesseract executable on PATH, set its native library path if required, and set
   TESSDATA_PREFIX to the directory containing the recorded English model. Set HF_HOME to the
   populated local cache, MPLCONFIGDIR to a writable directory, OMP_NUM_THREADS=4 and
   UNSTRUCTURED_DISABLE_NOTICE=1. The launcher sets HF_HUB_OFFLINE/TRANSFORMERS_OFFLINE
   before imports; missing model files fail rather than trigger implicit downloads.

```sh
/path/to/reference-venv/bin/python benchmarks/tools/compare_pdf_upstream.py --capture /tmp/new-capture --cache /path/to/originals --filename PMC1626454_002_00.png --matched-model /path/to/shared-weights --default-model /path/to/default-weights --layout-model /path/to/yolox_l0.05.onnx --output /tmp/new-comparison.json
/path/to/reference-venv/bin/python -m unittest discover -s benchmarks/reference-tests -p 'test_pdf_upstream.py'
```

Report paths must be fresh. Six independent analytical controls cover rowspan occupancy,
literal Unicode labels, invalid/overlapping spans, wrong spans/text with equal dimensions,
duplicate positional keys and nonrectangular upstream cell lists. The existing12 standard-library
tooling guards also pass. The capture helper compiles/runs on actual21/25 and passes strict
Javadoc checks. Production code is unchanged; the latest full reactor baseline is885 tests
with zero failures/errors/skips and strict Javadocs on both Java versions, CI39067999560 passed.
