package dev.docweave.validation;

import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;

/** Injectable candidate boundary: tests may replay historical output, but cannot manufacture Word observations. */
@FunctionalInterface
interface WordCandidateCollector {
    /** Collects candidate output only; the replay coordinator supplies independent, pinned Word observations. */
    JsonNode collect(Path packet, JsonNode manifest) throws IOException;

    /** Calls the actual library through public interfaces; no launcher process or classpath file is needed. */
    static WordCandidateCollector create() {
        return new DefaultWordCandidateCollector();
    }
}
