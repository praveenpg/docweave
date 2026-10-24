package io.docweave.validation;

import java.io.IOException;
import java.nio.file.Path;

/** Generates owned experimental inputs only; it never generates or attaches Word observations. */
interface WordFixtureGenerator {
    /** Builds ten follow-ups from verified original seeds into a directory that must not exist. */
    void followups(Path source, Path output) throws IOException;

    /** Builds four heading controls from verified follow-ups, keeping them outside candidate comparison. */
    void outlineControls(Path source, Path output) throws IOException;

    /** Creates a development-only generator with no production service registration. */
    static WordFixtureGenerator create() {
        return new DefaultWordFixtureGenerator();
    }
}
