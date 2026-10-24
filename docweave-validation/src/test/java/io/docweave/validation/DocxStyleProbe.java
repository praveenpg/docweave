package io.docweave.validation;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Independent stored-style inventory and explicitly instrumented derivatives; never a rendering oracle. */
interface DocxStyleProbe {
    /** Controlled interventions, preserving source paragraph styles and numbering. */
    enum Mode { ENABLED, DISABLED, DIRECT_OVERRIDE }

    /** Counts stored definitions and paragraph visits, including revision/fallback branches. */
    Map<String, Object> inspect(Path source) throws IOException;

    /** Replaces table styles/look/bands and clears cached condition flags; other part contents stay intact. */
    byte[] derive(Path source, Mode mode) throws IOException;

    /** Creates the hidden JDK DOM/ZIP implementation; no production parser internals are used. */
    static DocxStyleProbe create() {
        return new DefaultDocxStyleProbe();
    }
}
