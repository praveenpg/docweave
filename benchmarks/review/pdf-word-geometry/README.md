# Actual OCR word geometry

Optional word SPI and hidden Tesseract implementation retain actual TSV token boxes,
complete immutable source-line decomposition and provider encounter order. Existing
line recognition has no token-list allocation. One managed subprocess per detailed call;
shared process/raster/output/deadline limits remain in force.

Owned tests cover immutable projection, mismatched text/outside geometry rejection,
hierarchy grouping, original line projection, exact token boxes, invalid UTF-8,
character limits, subprocess count and failure cleanup. Explicit installed English
Tesseract control recognizes six exact words and validates line/raster containment on
both Java21 and25. All seven installed engine families run in each full verify.
Strict Javadocs, pinned formatter and12 reference-tool guards pass. Counts are in
verification.json. Logs/reports remain under /tmp/docweave-word-java21 and java25.

This is provider geometry evidence, not table recognition or calibrated accuracy.
Rotation/deskew decorators still expose only line geometry. No third-party corpus
replay or upstream parity claim in this batch. Detailed geometry table integration
remains the next task; default extraction is unchanged.
