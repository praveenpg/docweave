package dev.docweave.validation;

/** JVM-only entry point for frozen baseline/contextual retrieval capture scoring. */
public final class EvaluateContextualRetrieval {
    /** Prevents standalone utility construction. */
    private EvaluateContextualRetrieval() {
    }

    /**
     * Replays every independent query with unchanged source identities and model/retrieval configuration.
     * This tool never generates context, calls an embedding/model API or reruns retrieval.
     * @param args manifest, evidence root, baseline capture, contextual capture and fresh numeric report
     * @throws Exception if source/configuration drifts, evidence is invalid or output already exists
     */
    public static void main(final String[] args) throws Exception {
        ContextualRetrievalReplay.run(args);
    }
}
