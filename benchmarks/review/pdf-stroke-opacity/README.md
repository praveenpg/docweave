# Zero-opacity stroke evidence correction

An owned source with zero-opacity grid strokes rendered pixel-for-pixel identically
to the same text without a grid, but previously produced a Table. The new JVM test
first reproduced that failure againsta0b16ff. Zero-opacity strokes now contribute no
rule geometry. Nonzero stroke evidence retains its earlier interpretation, including
small positive opacity; this is not a general rendered-visibility filter.

Combined fill/stroke operators need separate handling: when strokes are invisible,
existing supported thin/dark/opaque fill interpretation still runs. Arbitrary wide
filled outlines do not become table borders. Positive-opacity combined strokes retain
legacy behavior. Native text remains available to fallback, rather than being claimed
by a grid with no stroke contribution. Output element types/IDs may change for affected
invisible-grid inputs. Operator, glyph, source, work and table limits remain unchanged.

Three owned test methods cover stroke-only and combined operators, exact independently
rendered no-grid RGB equality, source text preservation, supported fill fallback and
wide-fill rejection. Four displayed rotations, nonzero crop and UserUnit2 are exercised.
Opaque/quarter-opacity/small-positive strokes and combined strokes retain identical
text/cell geometry. The first fixture helper compile needed its checked IOException
declaration; the corrected before-fix test failed on the intended false Table assertion.
The final full Java21 build passes1,188 tests with zero failures/errors/skips and strict
public Javadocs. Strict private Javadocs and pinned formatting also pass. Java25 remains
deferred. Prior OCR source studies are retained; this build does not claim new real OCR calls.

## Complete original regression

HashNativePartitionResults hashes all element IDs/types/text/metadata and warnings,
canonicalizing map-key order only. Envelope timing/format/encoding/input-byte fields
are outside that fingerprint; complete original bytes are independently pinned. All
five scientific originals under three fixed native profiles produce15 identical old/new
fingerprints. Existing compound wrapping is enabled as previously declared. The old
visitor is compiled froma0b16ff into an isolated first-classpath override; the current
visitor runs in a separate JVM. Neither source tree nor historical evidence is changed.

The earlier complete native-compound preservation check also passes all15 records,
including frozen source-text/caption hashes. Both full11-page NVIDIA profile replays
succeed; all six statement records and both document summaries exactly reproduce the
frozen baseline. Strict rows remain0/110. No original-source recall gain is claimed;
this corrects demonstrated owned-source false table evidence while retaining these
original regressions. It does not infer semantic row/header correctness.

## Reproduction

Run PdfStrokeOpacityTest and PdfFilledRuleTest, then full Java21 mvn verify. Reuse all
original source pins and private four-column TSV instructions in ../pdf-native-compound-wraps.
Compile HashNativePartitionResults.java together with CompareNativeCompoundText.java on
the existing validation/test classpath. Compile the old PdfPageGraphics.java froma0b16ff
in a separate class directory and place it first only for the baseline JVM. Run old and
current helpers with the same TSV and fresh report paths; compare complete results arrays.
Original PDF/HTML content is fetch-only, not redistributed. The NVIDIA replay retains
the exact frozen8c4e990 scorer source/class override described in
../pdf-synchronized-model-rows; no hash guard, reference or normalization is changed.

Clipping, dash interpretation, soft-mask/compositing effects and final paint occlusion
remain outside this correction. No publication, version/tag or repository visibility change.
