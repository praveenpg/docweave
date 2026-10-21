# DocWeave all-formats convenience artifact

Coordinates: io.docweave:docweave (Java21+).

This artifact is a dependency bundle with no Java classes of its own. Its source and
Javadoc archives contain this guide and the project license. API sources and generated
Javadocs are provided by each dependency, including docweave-core, docweave-pdf,
docweave-docx, docweave-json, docweave-chunking and docweave-batch.

Use io.docweave:docweave-pdf for PDF without the other format dependencies.
Optional docweave-ocr-tesseract and docweave-layout-onnx adapters are installed separately;
no engine, traineddata or model weights are bundled or downloaded automatically.

Project source and module documentation: https://github.com/praveenpg/docweave
License: Apache License2.0, included as META-INF/LICENSE. Third-party dependency terms
remain with their respective artifacts. A snapshot build is not a published beta release.
