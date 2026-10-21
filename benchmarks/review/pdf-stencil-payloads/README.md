# Stencil PNG payload correction

Before this change, a red stencil exported as opaque black/white pixels. Stencils
have no intrinsic color: each placement supplies paint, and unpainted mask samples
are transparent. The opt-in payload path now applies a solid nonstroking source fill
through PDFBox's stencil decoder and encodes the resulting RGBA PNG. Reused masks,
inline image masks and paint inherited inside forms retain their occurrence colors.

The identical compiled public-API probe ran against the retained pre-fix packaged
PDF JAR and the candidate packaged JAR, using identical owned PDF bytes:

| Authored check | Pre-fix | Candidate |
| --- | ---: | ---: |
| Exact solid paint RGBA, red and blue placements | 0/2 | 2/2 |
| Transparent mask sample alpha | 0/2 | 2/2 |

These are analytic owned-pixel expectations, not external image/figure accuracy.
The baseline PDF JAR SHA256 matches the preceding independent consumer's pinned
receipt. The same probe bytecode, six dependency JARs and source input are used for
both runs; no reference pixels are generated using the decoder under test. The probe
associates source placements by fixed authored coordinates and retains observed
mismatching samples in the baseline report. The fixture has fixed IDs; two independent
fixture generations produce byte-identical PDFs. Its source SHA256 is pinned in
identities.json. No third-party source images or transcriptions are redistributed.

## Contract and boundaries

Reference-only extraction remains unchanged and does not decode images. Payload
metadata on stencils adds image_stencil=true and image_pixel_policy:

- stencil_solid_fill_v1: current solid fill plus transparent mask samples.
- stencil_mask_samples_v1: original decoded monochrome samples for pattern-painted
  stencils; this is explicitly a mask, not a rendered pattern preview.

Ordinary images keep their intrinsic decoded RGB/alpha and existing metadata. PNGs
remain source-image payloads, not rendered page crops: placement transforms, clipping,
global graphics-state alpha/blending and pattern rendering are not baked in. Pixel
preflight runs before decoding and the encoded-byte budget remains cumulative across
all occurrences. Corrected PNG byte sizes may differ from earlier opaque masks.
There is no new dependency, public API signature, source repair, model or OCR path.

## Validation

Five public-path JVM test methods cover both standard decode polarities, separately
painted repeats, saved/restored color, inherited form paint, inline masks, explicit
pattern-mask fallback, ordinary semi-transparent RGBA, exact cumulative byte ceilings
and source pixel limits. Placement labels are fixed from authored source coordinates,
independent of geometric output ordering. The first test attempt assumed content-stream
order; it correctly failed because public output is geometrically ordered. Association
was corrected to fixed source coordinates without changing expected paint/alpha values.

Full Java21 verification passes1,130 tests, zero failures/errors, and strict public
Javadocs. Strict private Javadocs pass for the implementation/API, tests and probes.
After final test-documentation/constructor clarification, all five controls pass again.
The pinned formatter and diff check pass. Java25 remains deferred to PDF completion.

The nine frozen original/scan-container default sources were replayed with unchanged
PdfOptions.defaults(), then verified on the JVM against the complete earlier captures.
All parsed JSON fields are exactly equal, including element IDs, text, metadata,
outcomes and warnings. Independent Poppler raster inventories also remain equal,
retaining all33 ordinary placements. That corpus has zero painted stencil entries:
this establishes default-output regression, not external stencil accuracy. Older
reports and all failure/unsupported boundaries remain unchanged.

## Reproduction

Build verified snapshot core/PDF JARs. Compile StencilPayloadProbe.java at release21
against only core/PDF, PDFBox3.0.8, PDFBox IO, FontBox and Commons Logging1.4.0.
The probe itself requires no Jackson or test classes. Generate one owned input:

```sh
java -Djava.awt.headless=true -cp "$probe_classes:$six_jar_classpath" \
  StencilPayloadProbe --fixture /path/to/fresh-owned-stencils.pdf
```

Pass the identical input bytes and their expected SHA256 to separate old/new JAR runs,
using fresh properties report paths. Do not place reactor classes or both library
versions on either runtime classpath:

```sh
java -Djava.awt.headless=true -cp "$probe_classes:$six_jar_classpath" \
  StencilPayloadProbe /path/to/owned-stencils.pdf "$expected_source_sha256" \
  /path/to/fresh-pixel-report.properties
```

For default regression, run the existing CapturePdfDefaults.java with the frozen
pdf-interim-defaults manifest, verified original source cache, new capture directory
and explicit installed pdfimages executable. Compile VerifyDefaultReplay.java with
Jackson on its separate verifier classpath; pass that manifest, source cache, original
baseline captures, new captures and a fresh numeric output. It verifies all source
hashes, all nine complete captures and unchanged independent inventories without
normalization or excluded fields. Local raw captures remain outside Git.

This fixes a concrete payload defect; semantic figure/caption quality, external stencil
coverage, pattern rendering and broader PDF acceptance remain separate open boundaries.
No Central upload, tag, release or repository-visibility change is involved.
