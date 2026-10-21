# Owned real Tesseract control

`tesseract-owned-control.xml` is produced by `TesseractInstalledTest` with the final implementation,
using actual Tesseract 5.5.3/Leptonica 1.87.0 on Linux amd64, Corretto Java 25.0.4+7.
It confirms exact recognition of two owned raster text lines both directly and through an image-only
PDF, retention of the PDF's image element and explicit failure for an unavailable language model.
The raster uses DejaVu Sans bold 48; input hashes identify the exact generated inputs, whose PDF
serialization is not promised byte-stable across runs. This is a synthetic smoke/regression control,
not independent scanned-document accuracy, human review or Unstructured PDF parity.

`runtime-and-packages.json` records the actual engine version output, native shared-library identities,
three downloaded package hashes and production/test source plus runtime-class fingerprints. Packages
were downloaded over HTTPS from official Arch package redirects and extracted only under
`/tmp/docweave-tesseract-runtime`; no system package installation was performed. These are observed
package versions/bytes, not a claim that the mutable download URLs will always serve the same versions.
Neither packages, models, PDFs nor raster binaries are committed. Before claiming a replay, verify
cached bytes against hashes or record a new engine/model identity and fresh result.

Reproduction with an installed Tesseract 5 and English model is described in
[the adapter guide](../../../docs/ocr-tesseract.md). For this temporary extracted runtime, prepend
`LD_LIBRARY_PATH=/tmp/docweave-tesseract-runtime/usr/lib` and set the executable to
`/tmp/docweave-tesseract-runtime/usr/bin/tesseract`, the tessdata directory to
`/tmp/docweave-tesseract-runtime/usr/share/tessdata`, and a new `/tmp` report filename.
These inputs are stored outside the repository. A changed model/font/runtime can change recognition.

Owned Java subprocess controls separately verify process hangs, admission, interruption, byte floods,
invalid output, failures and temporary-file cleanup. They are not OCR implementations. Default hosted
CI runs those controls and the optional real-engine test's disabled guard; it does not install or execute
Tesseract. Real OCR here was run locally during full Maven verification. No Windows VM was needed.
