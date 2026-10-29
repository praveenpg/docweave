# PDF image output contracts

An image in a PDF can mean three different things. DocWeave keeps them separate, and never presents one as another:

| Contract | Question it answers | Provided by | Status |
|---|---|---|---|
| **A. Intrinsic source samples** | What pixels does the image XObject itself contain? | `Image` element payload: `image_base64` PNG (`includeImageBytes`), `image_width`/`image_height` | Provided |
| **B. Isolated rendered contribution** | What does this one image occurrence add to the page under its own graphics state (clip, transform, alpha, soft mask, groups), ignoring every other mark? | — | **Not provided** |
| **C. Final visible appearance** | What does the reader see in this region of the finished page? | `PdfPageCropRenderer` (explicit service; optional `BOX_4X` sampling) | Provided |
| *Original encoded bytes* | The image stream's original encoded data (for example a JPEG `DCTDecode` stream) | — | **Not provided**; contract A is decoded and re-encoded as PNG |

`PdfImageOutputContractsTest` is the executable form of this page: one red 40×40 image under each feature below, with
contract A and C assertions.

## Feature matrix

| Feature | A: intrinsic samples (`image_base64`) | C: final appearance (page crop) |
|---|---|---|
| Clipping (rectangular, curved, compound) | Not applied: all samples are retained. Clip geometry is described separately by opt-in metadata (`withImageClipBounds`, `withCurvedClipEnvelopes`). | Applied: clipped-out parts show what lies beneath (white page in the fixture). |
| Placement transform (scale, rotation, skew) | Not applied: samples in source orientation; placement is reported as coordinates. | Applied. |
| Constant alpha (`ca`) | Not applied (intrinsic alpha only). | Applied: composited over the backdrop (red at 0.5 over white is about 255/128/128). |
| Soft mask in the graphics state (`SMask`) | Not applied; described by graphics-state metadata. | Applied: masked-out parts show the backdrop. |
| Image's own mask or SMask (part of the image) | Applied: decoded into the PNG's alpha. | Applied. |
| Transparency groups, nested groups | Not applied; described by opt-in group context (`withImageGroupContext`). | Applied (nested 0.5 × 0.5 is about 255/191/191). |
| Isolated / knockout groups | Not applied. | Applied as PDFBox composites them. |
| Blend modes | Not applied; mode recorded in metadata. | Applied (multiply over white is unchanged red). |
| Later overpainting | Not applied: samples are unaffected. | **Shows the overpaint**; the image is not visible. |
| Identical-colour backdrop | Not applicable. | **Indistinguishable**: the crop is identical with and without the image. C attributes no pixel to an image. |
| Stencil masks | Solid fill painted into the PNG (`stencil_solid_fill_v1`); pattern stencils keep mask samples (`stencil_mask_samples_v1`). | Applied. |

Guarantees that apply to all contracts: budgets (`maxImagePixels`, `maxImageBytes`, crop options) are enforced with
typed failures (`IMAGE_LIMIT`, `INPUT_TOO_LARGE`, `MALFORMED_DOCUMENT`, `UNSUPPORTED_OPTION`); source coordinates and
provenance are retained; repeated occurrences are separate elements.

## Choosing a contract

- **Archive or re-use the picture itself (thumbnails, figure extraction, image search):** contract A.
- **Show what the reader saw (citations, review, OCR of a region):** contract C over the element's placement
  coordinates. It includes overlapping text, overpaint and backdrop; it is not "the image".
- **Labels such as "visible", "hidden" or "occluded":** neither A nor the clip and group metadata can establish
  them. Opt-in clip metadata reports only conservative geometric exclusion (`excludes_image_placement=true` means
  outside the clip; `false` leaves visibility unresolved).

## Why contract B is not provided

Owned prototypes (benchmarks `pdf-parked-followup-2026-10-22`, P7) show that a mask-aware isolated contribution is
feasible on 14 fixtures, including soft masks, nested and knockout groups and the `/BC` backdrop. It is not offered
because its meaning still depends on consumer decisions:

1. whether blend-mode colour effects belong to the contribution;
2. whether "contribution" excludes later overpaint (it does by definition, unlike C);
3. how it relates to identical-colour cases, where only B could distinguish them.

Adding a service before those are decided would create an ambiguous API. Each of these is a future, explicit design
decision, and no output claims exact source appearance.
