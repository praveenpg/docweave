# Source Figure discovery and image controls

Before product extraction, all three pass PDFs in the Figure-properties clause
of the pinned veraPDF PDF/UA-2 corpus were selected in lexical order. Selection,
original Git identities and repository/license attribution are in `selection.json`.
The three source-author synthetic samples are **one identical rendered layout
with three metadata variants**, not three diverse business/scientific documents.

Each original has one Figure node and no Caption node. One Figure has an Alt key;
two have ActualText keys. Those properties are not interpreted as captions. A
bounded source-only JVM inventory verifies original length/Git SHA1 and records
SHA256, role counts and ancestor relationships without copying source strings.
Owned controls cover role alias resolution, duplicate object ownership,
Figure/Caption ancestry and role-alias cycle rejection. No product extraction or
model inference enters this source inventory.

Independent Poppler 26.10.0 96-DPI renders were inspected before product output.
All three render hashes equal
`953d4f782b415520cebb39c97b13440ddc11bed62f8894a3a302879d833d458b`.
They show a small raster logo and ordinary company text, without a separate
caption. `pdfimages -list` independently identifies one 75x16 image and its
auxiliary 75x16 soft mask per original. These sources therefore cannot close the
positive figure-caption gate. The mask is not a second standalone source image.

`qualified.json` fixes that count/dimension/no-caption contract before product
extraction and pins both JVM utilities. Both native and source-tagged factories
then process every complete original under all three text-rendering policies,
with PNG extraction explicitly enabled. All **18/18** observations succeed:
one decodable 75x16 image, correct dimension/MIME metadata, empty image text,
no native-text policy label on images and zero fabricated FigureCaption elements.
All PNG payload hashes equal
`5398a47e6c01fc82b28c8fdc1e8807a4618f560d9a3f835558a07ed40a68e2b6`.
This demonstrates image-count/dimension and metadata-variant stability, not
independent pixel/color accuracy or semantic caption accuracy.

No product source changes were needed. The preceding full Java 21 verification
remains 1,155 passing tests. Both standalone tools compile with Java 21 and pass
strict private Javadocs and the pinned formatter. No Python test/dependency or
Java 25 run is introduced; source PDFs, transcriptions and rasters are not copied
into Git. Positive caption labels, broader figures and held-out review remain open.

To reproduce, fetch the exact originals in `selection.json` into an explicit local
cache. Compile `InventorySourceFigureRoles.java` and `VerifySourceFigureImages.java`
against the validation-module Java 21 test classpath. Run from the repository root:

1. Source inventory: selection manifest, original cache, new inventory report.
2. Independent qualification: `pdftoppm -r 96 -singlefile -png` and `pdfimages -list`.
3. Product check: qualified manifest, original cache, new numeric report, headless AWT.

No tool downloads assets, treats Alt/ActualText as a caption or selects cases by
product output. Complete source and product reports accompany this README.
