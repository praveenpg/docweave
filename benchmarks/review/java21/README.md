# Java 21 baseline validation

The minimum source/runtime version is Java 21. No library API required Java 25; six standalone example
and fixture/probe launchers used compact-source/instance-main syntax. They now use explicit classes
and standard static main methods. Historical benchmark reports retain their original JDK/source identities.

`java21-verification.json` records actual Corretto 21.0.12.1 validation, including the downloaded official
JDK archive SHA256, all 706 Maven tests/strict Javadocs, production classfile major 65 and compilation
of all 15 standalone Java tools/examples with `--release 21`. It also records execution of TXT/HTML
partitioning and chunking examples on the actual Java 21 binary. The temporary JDK was extracted to
`/tmp/docweave-jdk21`; neither the system JDK nor the user's SDKMAN default was changed.

`java25-verification.json` records the same full suite/strict docs on Corretto 25.0.4+7, targeting release 21.
Real OCR controls were enabled locally for both JDKs; the table/textless/mixed reports are stored under
`../pdf-scanned-tables/`. Ordinary hosted CI is a fresh-build 21/25 matrix with separate artifact names;
it runs owned OCR process/raster controls, not an installed Tesseract engine. Current implementation
fingerprints accompany the PDF evidence; no historical goldens were repinned.

Actual Java 21 execution exposed cancellation/completion assumptions. Untimed batches now submit an
explicit FutureTask through execute, keeping the executor's runnable tracked until operation/finally
completion. Timed batches retain their documented nonwaiting timeout behavior. OCR and outer-caller
cancellation tests use explicit completion latches before asserting cleanup. These changes preserve
the original cleanup contract rather than weakening assertions to accept unfinished operations.

Reproduction: use an installed JDK21 through JAVA_HOME and run `mvn verify`; repeat on JDK25.
Source/API release and doclint source are tied to the root compiler property. The validation module
compiles every standalone source for release 21, so a future compact-source or newer API regression
fails normal verification. No preview APIs or changed Java package names were introduced.
