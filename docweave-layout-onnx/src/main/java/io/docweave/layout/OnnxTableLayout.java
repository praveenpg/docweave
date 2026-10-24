package io.docweave.layout;

import io.docweave.partition.PdfTableLayoutProvider;

import java.io.IOException;
import java.util.Objects;

/**
 * Explicit managed local ONNX table-structure provider. The PDF core and convenience artifact do not
 * depend on this native runtime. Compatible caller-supplied pinned graphs are required; model files,
 * weights and backbones are never downloaded. Structure inference is intended for table images;
 * the two-graph factory explicitly enables whole-page ordinary-table detection.
 * Instances are thread-safe with bounded admission and must be closed by their owning application.
 * Class3 column-header evidence is retained as a contiguous top row prefix; it does not merge
 * header levels, modify cell spans or establish semantic correctness. Missing leading evidence
 * is not filled from a lower header prediction. Class scores/geometry heuristics do not establish
 * calibrated table/text accuracy.
 */
public interface OnnxTableLayout extends PdfTableLayoutProvider, AutoCloseable {
    /**
     * Reads a bounded local graph, verifies its expected hash and initializes one native CPU session.
     * Encoded caps do not bound arbitrary graph initialization/native allocations; use a trusted
     * compatible model. Construction is explicit native loading, not inference or network access.
     * @param options graph identity and resource policy
     * @return hidden managed implementation
     * @throws NullPointerException if options is null
     * @throws IOException if graph identity, schema, native availability or initialization fails
     */
    static OnnxTableLayout create(final OnnxTableOptions options) throws IOException {
        return new DefaultOnnxTableLayout(Objects.requireNonNull(options, "options"));
    }

    /**
     * Creates explicit whole-page detection followed by table-crop structure recognition.
     * The first graph requires three output labels (table, rotated table, background), the second
     * seven structure labels. Both use the documented maxHW800 bilinear/ImageNet preprocessing.
     * Ordinary table detections only are supported; rotated detections are left to PAGE fallback.
     * At most32 positive detections and64M crop fill/copy visits are admitted. The shorter timeout,
     * stricter table dimensions and aggregate prospective-slot limit apply across both stages.
     * Each graph retains its own model/input/query/concurrency/native-thread policy. Nothing is downloaded.
     * @param detection explicit trusted detection graph identity and resource policy
     * @param structure explicit trusted structure graph identity and resource policy
     * @return hidden managed two-session provider, owned and closed by the application
     * @throws NullPointerException if either policy is null
     * @throws IOException if either graph identity/schema/session or native loading fails
     */
    static OnnxTableLayout create(final OnnxTableOptions detection, final OnnxTableOptions structure)
            throws IOException {
        return new DetectedOnnxTableLayout(
                Objects.requireNonNull(detection, "detection"), Objects.requireNonNull(structure, "structure"));
    }

    /**
     * Creates explicit fixed-shape YOLOX page detection followed by existing TATR structure inference.
     * The trusted local detector requires FLOAT images[1,3,1024,768] and output[1,16128,16],
     * with eleven published classes and table class8. RGB input uses top-left114 padding and
     * pixel-center bilinear uint8 rounding; alpha input is first composited over white.
     * This declared JVM interpolation is not an OpenCV parity claim. All competing classes
     * participate in inclusive-area class-agnostic NMS0.1 before table selection/clipping.
     * Raw query/candidate/work limits and the existing crop/slot/deadline/admission/lifecycle
     * controls apply. Use {@link OnnxTableOptions#yoloXDefaults} for the detector, and ordinary
     * TATR options for the structure graph. This does not establish row recognition accuracy.
     * Nothing is downloaded, discovered or enabled by default; applications own and close the provider.
     * @param detection explicit YOLOX graph identity and resource policy
     * @param structure explicit seven-label TATR structure graph identity and policy
     * @return hidden managed two-session provider
     * @throws NullPointerException if either policy is null
     * @throws IOException if graph identity/schema/session or native loading fails
     */
    static OnnxTableLayout createWithYoloX(final OnnxTableOptions detection, final OnnxTableOptions structure)
            throws IOException {
        return new DetectedOnnxTableLayout(
                Objects.requireNonNull(detection, "detection"), Objects.requireNonNull(structure, "structure"), true);
    }

    /**
     * Prevents further admissions and releases this session after active calls leave native inference.
     * The shared runtime environment remains caller/process-owned. Closure is idempotent; queued
     * calls fail when they observe closure. Native termination is cooperative within the trusted graph.
     * @throws IOException if interruption or native cleanup prevents closure; closure may be retried
     */
    @Override void close() throws IOException;
}
