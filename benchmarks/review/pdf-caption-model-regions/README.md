# Fixed generic caption-model diagnostic

All27original PeerJ pages were rendered whole at150DPI and inferred on Java21
using the existing caller-supplied pinned YOLOX graph. No caption strings, JATS,
reference crops, structure model, OCR or source-dependent settings enter inference.

The unchanged managed detector validates all16128queries, numeric values and
resource limits and performs the same class-agnostic NMS before class selection.
A scratch decoder copy changes the selected published model class from Table8
to Caption0. The primary installed unstructured-inference1.6.13 label-map source
hash is recorded in the manifest. The capture verifies the actually loaded
decoder bytecode hash before inference; internal canonical table-label integers
are not exported or used as semantic ground truth.

All **27/27** page inferences succeed. There is **one generic caption proposal**,
on page5. None occurs on pages7,11,12,13or14, which contain the six source figure
captions. Generic Caption0 can also represent table captions; the page5 proposal
is not declared a false positive without independent annotation. These counts
do not establish text, box or general model accuracy.

This fixed profile does not supply useful figure-caption coverage on the selected
source, so no production caption-provider API is added on its evidence. No lower
threshold, new crop, per-source model winner or omitted page rescues the result.
The existing table provider is unchanged. This is not an execution or ranking of
the complete Unstructured public pipeline and is not bitwise OpenCV parity.

Compile `CaptureCaptionModelRegions.java` and a scratch decoder copy with the
recorded `class-selection.patch` on Java21. Put the scratch class directory first
on the runtime classpath. The CLI takes original PDF/hash, local graph/hash,
compiled decoder class SHA256 and a fresh numeric report. Source/model hashes and
the fixed entire population are checked; a mismatched loaded decoder is rejected.
No asset is downloaded or discovered by the utility. All per-page PNG identities,
dimensions, typed outcomes and numeric proposals are retained in `java21.json`.

Compilation, strict private Javadocs and pinned formatting pass. No product code
or dependency changes; the preceding complete Java21 checkpoint remains1,159
passing tests. Java25 stays deferred. Neither models, original media nor publisher
text are redistributed.
