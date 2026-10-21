# Source-image invocation state

Native images retain `pdf_image_graphics_state`: interpreted nonstroking alpha,
resolved blend-mode name, graphics-state soft-mask presence and alpha-source flag.
The same normalized PNG can have different appearances under different invocation
states; source pixels alone do not preserve those distinctions.

## Owned regression

Before the change, two new public-path methods failed because this metadata was
absent. The implemented change records state at each image invocation without
rewriting PNG samples, filtering source occurrences or changing placement geometry.
Three native methods cover:

- One raster under quarter-alpha Multiply, restored opaque Normal and zero-alpha
  Screen. All three PNGs are byte-identical; separate PDFBox rendering confirms
  different visible center pixels. Reference-only and payload modes retain the same
  invocation metadata, including the invisible source occurrence.
- Ordinary form inheritance, transparency-group internal resets and restored caller
  state. The group has its own composition stage, which is not flattened into the
  per-image map or payload.
- An actual graphics-state soft-mask group and alpha-as-shape flag. Intrinsic PNG
  samples remain unchanged and the auxiliary mask does not become another direct
  page Image.

A cross-module control retains all fields, source identity and payload bytes through
ElementJson round trips and physical coordinate conversion. Metadata remains immutable.
The PDF-only module retains its dependency boundary; no JSON/model dependency is added.

## Verification

`java21.json` pins the three changed Java source files and verification log identity.
The focused image suite passes 16 methods; the JSON/conversion integration method passes.
Full Java21 verification passes **1,269 tests, zero failures/errors/skips**, including strict
public Javadocs and standalone-source compilation. Three-file strict private Javadocs,
pinned formatting and diff checks pass. Java25 verification remains reserved for final
PDF acceptance or a release candidate.

The complete 28-page original magazine from the earlier affine review still emits all
94 source occurrences; all four frozen Cairo numerical corner references pass their
unchanged two-point tolerance. The unchanged JVM verifier and source/reference pins
are documented in [the earlier evidence](../pdf-source-image-corners/README.md).
`affine-java21.json` retains this geometric preservation check. This is not independent
validation of every alpha/blend value or final rendered pixels on that original.
Raw documents, page images and source transcriptions remain outside Git.

## Limits

This is interpreted current-stream state, not complete source syntax or a compositing
engine. Transparency groups reset internal alpha/blend/mask state; their enclosing
composition is separate. Clipping, backdrop dependence, isolation/knockout and occlusion
remain outside the source-image pixel contract. The new fields do not establish general
image fidelity, a retrieval improvement or Unstructured parity. Existing normalized
PNG limits, selection and ownership remain unchanged. No OCR/model run, engine download,
public synchronization, release tag or Central publication is part of this change.
