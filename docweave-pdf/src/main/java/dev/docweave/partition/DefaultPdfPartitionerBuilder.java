package dev.docweave.partition;

import java.util.Objects;

/** Maps public routing presets onto the existing bounded native/OCR strategies. */
final class DefaultPdfPartitionerBuilder implements PdfPartitioner.Builder {
    /** Initial routing does not implicitly install or enable an engine. */
    private PdfStrategy strategy = PdfStrategy.AUTO;
    /** Immutable source/image policy shared by all selected paths. */
    private PdfOptions options = PdfOptions.defaults();
    /** Null retains preset-specific default resolution. */
    private PdfOcrOptions ocrOptions;
    /** Optional caller-owned recognition strategy. */
    private PdfOcrProvider ocrProvider;
    /** Optional caller-owned table geometry strategy. */
    private PdfTableLayoutProvider layout;

    /** Creates configuration state without provider discovery or execution. */
    DefaultPdfPartitionerBuilder() {
    }

    @Override
    public PdfPartitioner.Builder strategy(final PdfStrategy strategy) {
        this.strategy = Objects.requireNonNull(strategy, "strategy");
        return this;
    }

    @Override
    public PdfPartitioner.Builder options(final PdfOptions options) {
        this.options = Objects.requireNonNull(options, "options");
        return this;
    }

    @Override
    public PdfPartitioner.Builder ocrOptions(final PdfOcrOptions options) {
        this.ocrOptions = Objects.requireNonNull(options, "options");
        return this;
    }

    @Override
    public PdfPartitioner.Builder ocrProvider(final PdfOcrProvider provider) {
        this.ocrProvider = Objects.requireNonNull(provider, "provider");
        return this;
    }

    @Override
    public PdfPartitioner.Builder tableLayoutProvider(final PdfTableLayoutProvider provider) {
        this.layout = Objects.requireNonNull(provider, "provider");
        return this;
    }

    @Override
    public PdfPartitioner build() {
        if (strategy == PdfStrategy.FAST) {
            return PdfPartitioner.create(options);
        }
        if (ocrProvider == null) {
            if (strategy != PdfStrategy.AUTO || layout != null || ocrOptions != null) {
                throw new IllegalArgumentException("Selected PDF strategy/configuration requires an OCR provider");
            }
            return PdfPartitioner.create(options);
        }
        final var base = ocrOptions == null
                                 ? PdfOcrOptions.defaults().withDpi(strategy == PdfStrategy.HI_RES ? 300 : 150)
                                 : ocrOptions;
        var selected = base.withSelection(strategy == PdfStrategy.AUTO ? PdfOcrOptions.Selection.TEXTLESS_PAGES
                                                                       : PdfOcrOptions.Selection.ALL_PAGES);
        if (strategy == PdfStrategy.HI_RES) {
            selected = selected.withTableDetection(PdfOcrOptions.TableDetection.PROVIDER_LAYOUT);
        }
        final boolean providerTables =
                selected.tableDetection() == PdfOcrOptions.TableDetection.PROVIDER_LAYOUT
                || selected.tableDetection() == PdfOcrOptions.TableDetection.PROVIDER_LAYOUT_CELLS;
        final boolean needsWords =
                providerTables || selected.tableDetection() == PdfOcrOptions.TableDetection.ALIGNED_WORDS;
        if (needsWords && !(ocrProvider instanceof PdfWordOcrProvider)) {
            throw new IllegalArgumentException("Selected PDF strategy requires actual-word OCR capability");
        }
        if (providerTables != (layout != null)) {
            throw new IllegalArgumentException(
                    "Provider-layout OCR requires a matching explicit table layout provider");
        }
        return providerTables ? PdfPartitioner.create(options, selected, (PdfWordOcrProvider) ocrProvider, layout)
                              : PdfPartitioner.create(options, selected, ocrProvider);
    }
}
