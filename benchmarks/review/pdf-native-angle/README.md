# Independent native angle regression

The original Mozilla pdf.js `test/pdfs/rotated.pdf` is pinned to commit
`157d30e833e5afbd282db5b1280aae4843f55c0a`. Fetch the URL in `manifest.json` to a local
file; the source PDF is not redistributed here. SHA256, byte length and Git blob identity
are checked before extraction. The fixture was selected by its published name, then
inspected visually and through its original content stream.

The page shows three whole `test` words (horizontal, quarter-turn and slanted), one
upright stacked-letter `test`, a title and a footer. The slanted text matrices have
linear part `[0.7071 0.7071 -0.7071 0.7071]`: approximately45 degrees counterclockwise
in the PDF bottom-left frame, hence315 degrees clockwise in displayed coordinates.
These source observations define the oracle, independently of extracted output.

The optional Java test checks the slanted word/angle, case-sensitive scalar inventory
excluding whitespace, finite bounds within the original612x792 page, exact JSON round
trip, repeated extraction stability and the default strategy's explicit unsupported-angle
failure. It does not require a particular segmentation of the upright stacked letters or
assert logical reading order. No text normalization, geometry repair or source modification
is performed. Bounds containment is not an independent tight glyph-box oracle.

Run with an unused report path:

```sh
mvn -B -ntp -Dmaven.repo.local=/tmp/docweave-m2 verify \
  -Ddocweave.pdf.angle.pdf=/path/to/original/rotated.pdf \
  -Ddocweave.pdf.angle.report=/tmp/fresh-native-angle.json
```

Java21/25 evidence is stored alongside this file after verification. One regression fixture
is not general arbitrary-angle, complex-script, font, semantic-order or scanned-table accuracy.
Production extraction is unchanged by this evidence checkpoint.
