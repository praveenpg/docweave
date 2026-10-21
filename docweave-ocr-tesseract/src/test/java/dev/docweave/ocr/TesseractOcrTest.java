package dev.docweave.ocr;

import dev.docweave.DocWeave;
import dev.docweave.partition.DocumentSource;
import dev.docweave.partition.PartitionException;
import dev.docweave.partition.PartitionerRegistry;
import dev.docweave.partition.PdfOcrOptions;
import dev.docweave.partition.PdfOptions;
import dev.docweave.partition.PdfPartitioner;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Owned JVM subprocess controls for immutable OCR policy, resource limits, cancellation and cleanup. */
class TesseractOcrTest {
    /** Creates engine-independent controls without model or executable discovery. */
    TesseractOcrTest() {
    }

    /**
     * Validates trusted arguments, TSV line projection and deletion of per-request temporary inputs.
     * @param directory owned child-process lifecycle markers
     * @throws Exception if the owned subprocess or marker inspection fails
     */
    @Test
    void validProcessArgumentsLineJoiningAndTemporaryCleanup(final @TempDir Path directory) throws Exception {
        final var result = provider("good", directory, options()).recognize(image());
        assertEquals(List.of("Hello world", "again"), result.stream().map(line -> line.text()).toList());
        assertEquals(10, result.getFirst().left());
        assertEquals(55, result.getFirst().right());
        assertThrows(UnsupportedOperationException.class, () -> result.clear());
        assertCleanup(directory);
        assertFalse(java.lang.reflect.Modifier.isPublic(TesseractOcr.create(options()).getClass().getModifiers()));
    }

    /**
     * Keeps exit, encoding, malformed output and stdout/stderr floods distinct without leaking diagnostics.
     * @param directory owned lifecycle markers
     * @throws Exception if fixture subprocess cleanup or marker inspection fails
     */
    @Test
    void processFailuresAndOutputFloodsAreTyped(final @TempDir Path directory) throws Exception {
        for (final var mode : List.of("exit", "badutf8", "malformed", "floodout", "flooderr")) {
            final var failure = assertThrows(
                    PartitionException.class, () -> provider(mode, directory, options()).recognize(image()));
            assertEquals(
                    mode.startsWith("flood") ? PartitionException.Code.OCR_LIMIT : PartitionException.Code.OCR_FAILURE,
                    failure.code(), mode);
            assertFalse(failure.toString().contains("private diagnostics"));
            if (!mode.startsWith("flood")) {
                assertNotNull(failure.getCause());
            }
        }
        assertCleanup(directory);
    }

    /**
     * Rejects excessive pixels, encoded bytes, lines and characters through typed adapter limits.
     * @throws Exception if the owned control process fails unexpectedly
     */
    @Test
    void imagePngAndTextBudgetsAreEnforced() throws Exception {
        final var base = options();
        final var pixelLimit = new TesseractOptions(base.executable(), null, base.languages(), base.timeout(), 1, 1,
                base.maxPngBytes(), base.maxOutputBytes(), base.maxLines(), base.maxCharacters());
        assertCode(PartitionException.Code.OCR_LIMIT, provider("good", null, pixelLimit));
        final var pngLimit = copy(base, base.timeout(), 1, 1, 100_000, 10, 100);
        assertCode(PartitionException.Code.OCR_LIMIT, provider("good", null, pngLimit));
        assertCode(PartitionException.Code.OCR_LIMIT,
                provider("good", null, copy(base, base.timeout(), 1, 100_000, 100_000, 1, 100)));
        assertCode(PartitionException.Code.OCR_LIMIT,
                provider("good", null, copy(base, base.timeout(), 1, 100_000, 100_000, 10, 5)));
    }

    /**
     * Verifies timeout cleanup, running-call cancellation and preservation of the caller's interrupt flag.
     * @param directory owned lifecycle markers
     * @throws Exception if process joining, fixture I/O or cleanup fails
     */
    @Test
    void timeoutAndCancellationStopProcessesAndReleaseAdmission(final @TempDir Path directory) throws Exception {
        final var base = options();
        assertCode(PartitionException.Code.OCR_TIMEOUT,
                provider("hang", directory, copy(base, Duration.ofMillis(700), 1, 100_000, 100_000, 10, 100)));
        assertCleanup(directory);
        final var provider = provider("hang", directory, base);
        final var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final int initial = count(directory);
        final var worker = Thread.ofVirtual().start(() -> {
            try {
                provider.recognize(image());
                failure.set(new AssertionError("Expected cancellation"));
            } catch (final Throwable caught) {
                failure.set(Thread.currentThread().isInterrupted()
                                    ? caught
                                    : new AssertionError("Interrupt flag was cleared", caught));
            }
        });
        awaitCalls(directory, initial + 1);
        worker.interrupt();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertInstanceOf(InterruptedIOException.class, failure.get());
        assertCleanup(directory);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> provider.recognize(image()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    /**
     * A cancelled waiter must not launch a process, leak a permit or prevent a fresh admitted request.
     * @param directory owned lifecycle markers
     * @throws Exception if bounded waits or marker inspection fail
     */
    @Test
    void admissionIsBoundedAndQueuedCancellationPreservesReuse(final @TempDir Path directory) throws Exception {
        final var provider = provider("hang", directory, options());
        final var started = new java.util.concurrent.CountDownLatch(2);
        final var finished = new java.util.concurrent.CountDownLatch(2);
        final java.util.concurrent.Callable<java.util.List<dev.docweave.partition.PdfOcrProvider.Line>> operation =
                () -> {
            started.countDown();
            try {
                return provider.recognize(image());
            } finally {
                finished.countDown();
            }
        };
        try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            final var first = executor.submit(operation);
            awaitCalls(directory, 1);
            final var second = executor.submit(operation);
            assertTrue(started.await(5, TimeUnit.SECONDS));
            Thread.sleep(150);
            assertEquals(1, count(directory));
            assertTrue(second.cancel(true));
            first.cancel(true);
        }
        // Future cancellation signals cancellation, not completion of the provider's finally blocks.
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertCleanup(directory);
        // A fresh admitted call reaching the subprocess proves the original permit was released.
        final var worker = Thread.ofVirtual().start(() -> {
            try {
                provider.recognize(image());
            } catch (final InterruptedIOException expected) {
                assertTrue(Thread.currentThread().isInterrupted());
            } catch (final Exception failure) {
                throw new AssertionError(failure);
            }
        });
        awaitCalls(directory, 2);
        worker.interrupt();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertCleanup(directory);
    }

    /**
     * Missing executables produce typed failures while PDF integration retains the adapter's pixel ceiling.
     * @param directory isolated path for a nonexistent executable
     * @throws Exception if the owned blank PDF cannot be generated
     */
    @Test
    void unavailableExecutableFailsAndPdfRetainsTypedLimits(final @TempDir Path directory) throws Exception {
        final var base = options();
        final var missing = new TesseractOptions(directory.resolve("missing-executable"), null, List.of("eng"),
                base.timeout(), 1, 100_000, 100_000, 100_000, 10, 100);
        final var failure =
                assertThrows(PartitionException.class, () -> TesseractOcr.create(missing).recognize(image()));
        assertEquals(PartitionException.Code.OCR_FAILURE, failure.code());
        assertNotNull(failure.getCause());
        final byte[] bytes;
        try (final var document = new PDDocument(); final var output = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(output);
            bytes = output.toByteArray();
        }
        final var processor = DocWeave.create(PartitionerRegistry.of(List.of(PdfPartitioner.create(
                PdfOptions.defaults(), PdfOcrOptions.defaults(), provider("good", null, options())))));
        // The large rendered page fails the adapter's pixel policy, before the test executable is launched.
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(
                        PartitionException.class, () -> processor.partition(DocumentSource.bytes(bytes, "scan.pdf")))
                        .code());
    }

    /** Ensures invalid paths, language identifiers and nonpositive limits fail before process construction. */
    @Test
    void immutableConfigurationRejectsInvalidPolicies() {
        assertThrows(IllegalArgumentException.class,
                ()
                        -> new TesseractOptions(Path.of("tesseract"), null, List.of("../eng"), Duration.ofSeconds(1), 1,
                                1, 1, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> copy(options(), Duration.ZERO, 1, 100, 100, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> copy(options(), Duration.ofDays(2), 1, 100, 100, 1, 1));
        assertThrows(UnsupportedOperationException.class, () -> options().languages().add("fra"));
        assertEquals(List.of("eng"), TesseractOptions.defaults().languages());
    }

    /**
     * Concurrent PAGE and TABLE_CELL calls retain their independent immutable segmentation choices.
     * @param directory owned lifecycle markers
     * @throws Exception if concurrent fixture calls or cleanup fail
     */
    @Test
    void cellSegmentationIsCallLocalAndDoesNotChangePages(final @TempDir Path directory) throws Exception {
        for (final var mode : TesseractOptions.CellSegmentation.values()) {
            final var provider = provider("psm", directory, options().withCellSegmentation(mode));
            final String expected = switch (mode) {
                case AUTO -> "mode-3";
                case SINGLE_BLOCK -> "mode-6";
                case SINGLE_LINE -> "mode-7";
            };
            try (final var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
                final var page = tasks.submit(() -> provider.recognize(image(), TesseractOcr.InputKind.PAGE));
                final var cell = tasks.submit(() -> provider.recognize(image(), TesseractOcr.InputKind.TABLE_CELL));
                assertEquals("mode-3", page.get(5, TimeUnit.SECONDS).getLast().text());
                assertEquals(expected, cell.get(5, TimeUnit.SECONDS).getLast().text());
            }
            assertEquals("mode-3", provider.recognize(image()).getLast().text());
        }
        assertCleanup(directory);
        assertEquals(TesseractOptions.CellSegmentation.AUTO, options().cellSegmentation());
        final var changed = options().withCellSegmentation(TesseractOptions.CellSegmentation.SINGLE_BLOCK);
        assertEquals(options(), changed.withCellSegmentation(TesseractOptions.CellSegmentation.AUTO));
        assertThrows(NullPointerException.class, () -> options().withCellSegmentation(null));
        assertThrows(NullPointerException.class, () -> provider("psm", null, options()).recognize(image(), null));
        final var constrained = copy(changed, changed.timeout(), 1, 100_000, 100_000, 1, 100)
                                        .withCellSegmentation(TesseractOptions.CellSegmentation.SINGLE_BLOCK);
        assertEquals(PartitionException.Code.OCR_LIMIT,
                assertThrows(PartitionException.class,
                        () -> provider("psm", null, constrained).recognize(image(), TesseractOcr.InputKind.TABLE_CELL))
                        .code());
    }

    /**
     * Every trusted page mode, including variable-size single columns, leaves cell policy and reuse unchanged.
     * @param directory owned lifecycle markers
     * @throws Exception if concurrent fixture calls or cleanup fail
     */
    @Test
    void pageSegmentationIsExplicitAndIndependentOfCellPolicy(final @TempDir Path directory) throws Exception {
        for (final var mode : TesseractOptions.PageSegmentation.values()) {
            final var policy = options()
                                       .withCellSegmentation(TesseractOptions.CellSegmentation.SINGLE_LINE)
                                       .withPageSegmentation(mode);
            final var provider = provider("psm", directory, policy);
            final String expected = switch (mode) {
                case AUTO -> "mode-3";
                case SINGLE_BLOCK -> "mode-6";
                case SPARSE_TEXT -> "mode-11";
                case SINGLE_COLUMN -> "mode-4";
            };
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var page = executor.submit(() -> provider.recognize(image()));
                final var cell = executor.submit(() -> provider.recognize(image(), TesseractOcr.InputKind.TABLE_CELL));
                assertEquals(expected, page.get(5, TimeUnit.SECONDS).getLast().text());
                assertEquals("mode-7", cell.get(5, TimeUnit.SECONDS).getLast().text());
            }
            assertEquals(mode, policy.withCellSegmentation(TesseractOptions.CellSegmentation.AUTO).pageSegmentation());
            assertEquals(TesseractOptions.CellSegmentation.SINGLE_LINE,
                    policy.withPageSegmentation(TesseractOptions.PageSegmentation.AUTO).cellSegmentation());
        }
        assertCleanup(directory);
        assertEquals(TesseractOptions.PageSegmentation.AUTO, options().pageSegmentation());
        final var changed = options().withPageSegmentation(TesseractOptions.PageSegmentation.SPARSE_TEXT);
        assertEquals(options(), changed.withPageSegmentation(TesseractOptions.PageSegmentation.AUTO));
        assertThrows(NullPointerException.class, () -> options().withPageSegmentation(null));
        final var oldCellConstructor = new TesseractOptions(options().executable(), options().tessdataDirectory(),
                options().languages(), options().timeout(), options().maxConcurrentProcesses(),
                options().maxImagePixels(), options().maxPngBytes(), options().maxOutputBytes(), options().maxLines(),
                options().maxCharacters(), TesseractOptions.CellSegmentation.SINGLE_BLOCK);
        assertEquals(TesseractOptions.PageSegmentation.AUTO, oldCellConstructor.pageSegmentation());
    }

    /**
     * Detailed words reuse one bounded subprocess and the same failure, geometry and cancellation contract.
     * @param directory owned lifecycle markers
     * @throws Exception if detailed fixture recognition or cleanup fails
     */
    @Test
    void detailedRecognitionUsesOneManagedProcessAndSharedFailurePolicy(final @TempDir Path directory)
            throws Exception {
        final var detailed = (dev.docweave.partition.PdfWordOcrProvider) provider("good", directory, options());
        final var result = detailed.recognizeWords(image(), TesseractOcr.InputKind.PAGE);
        assertEquals(1, count(directory));
        assertEquals(List.of("Hello", "world"), result.getFirst().words().stream().map(word -> word.text()).toList());
        assertEquals("Hello world", result.getFirst().line().text());
        assertCleanup(directory);
        final var invalid = (dev.docweave.partition.PdfWordOcrProvider) provider("badutf8", directory, options());
        assertEquals(PartitionException.Code.OCR_FAILURE,
                assertThrows(
                        PartitionException.class, () -> invalid.recognizeWords(image(), TesseractOcr.InputKind.PAGE))
                        .code());
        assertCleanup(directory);
        assertThrows(NullPointerException.class, () -> TesseractOcr.createWithWords(null));
        assertFalse(
                java.lang.reflect.Modifier.isPublic(TesseractOcr.createWithWords(options()).getClass().getModifiers()));
    }

    /**
     * Real owned subprocess arguments keep explicit thresholding independent of concurrent page/cell modes.
     * @param directory temporary input lifecycle markers
     * @throws Exception if the owned subprocess or bounded concurrent request fails
     */
    @Test
    void explicitThresholdingKeepsPageCellAndWordContracts(final @TempDir Path directory) throws Exception {
        final var policy = options()
                                   .withPageSegmentation(TesseractOptions.PageSegmentation.SPARSE_TEXT)
                                   .withCellSegmentation(TesseractOptions.CellSegmentation.SINGLE_BLOCK);
        for (final var thresholding : TesseractThresholding.values()) {
            final var provider = thresholdProvider("threshold", directory, policy, thresholding);
            final String expected = switch (thresholding) {
                case ENGINE_DEFAULT -> "default";
                case OTSU -> "0";
                case ADAPTIVE_OTSU -> "1";
                case SAUVOLA -> "2";
            };
            try (final var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                final var page = executor.submit(() -> provider.recognize(image()));
                final var cell =
                        executor.submit(()
                                                -> ((dev.docweave.partition.PdfWordOcrProvider) provider)
                                                        .recognizeWords(image(), TesseractOcr.InputKind.TABLE_CELL));
                assertEquals("threshold-" + expected + "-mode-11", page.get(5, TimeUnit.SECONDS).getLast().text());
                assertEquals(
                        "threshold-" + expected + "-mode-6", cell.get(5, TimeUnit.SECONDS).getLast().line().text());
            }
            assertCleanup(directory);
        }
    }

    /**
     * Public factories remain lazy/private and explicit image policies retain typed subprocess failures.
     * @param directory temporary input lifecycle markers
     * @throws Exception if the owned subprocess fails unexpectedly
     */
    @Test
    void thresholdFactoriesRemainLazyBoundedAndTyped(final @TempDir Path directory) throws Exception {
        assertThrows(NullPointerException.class, () -> TesseractOcr.create(options(), null));
        assertThrows(NullPointerException.class, () -> TesseractOcr.create(null, TesseractThresholding.SAUVOLA));
        assertThrows(NullPointerException.class, () -> TesseractOcr.createWithWords(options(), null));
        assertThrows(
                NullPointerException.class, () -> TesseractOcr.createWithWords(null, TesseractThresholding.SAUVOLA));
        for (final var thresholding : TesseractThresholding.values()) {
            assertFalse(java.lang.reflect.Modifier.isPublic(
                    TesseractOcr.create(options(), thresholding).getClass().getModifiers()));
            assertFalse(java.lang.reflect.Modifier.isPublic(
                    TesseractOcr.createWithWords(options(), thresholding).getClass().getModifiers()));
        }
        assertCode(PartitionException.Code.OCR_FAILURE,
                thresholdProvider("exit", directory, options(), TesseractThresholding.SAUVOLA));
        final var limited = copy(options(), Duration.ofMillis(700), 1, 100_000, 100_000, 10, 100);
        assertCode(PartitionException.Code.OCR_TIMEOUT,
                thresholdProvider("hang", directory, limited, TesseractThresholding.ADAPTIVE_OTSU));
        assertCleanup(directory);
    }

    /**
     * Uses an owned JVM subprocess to observe actual process arguments and cleanup with an explicit image policy.
     * @param mode owned subprocess behavior
     * @param marker temporary lifecycle marker directory
     * @param options validated OCR policy
     * @param thresholding fixed trusted binarization choice
     * @return hidden adapter using the same production request path
     */
    private static TesseractOcr thresholdProvider(final String mode, final Path marker, final TesseractOptions options,
            final TesseractThresholding thresholding) {
        final String java = Path.of(System.getProperty("java.home"), "bin",
                                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                                    .toString();
        return new DefaultTesseractOcr(options, thresholding,
                List.of(java, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                        OcrChildProcess.class.getName(), mode, marker.toString()));
    }

    /**
     * Supplies tight deterministic policies for the owned JVM child rather than an installed OCR engine.
     * @return immutable two-language policy with one admitted process
     */
    private static TesseractOptions options() {
        return new TesseractOptions(Path.of("tesseract"), null, List.of("eng", "deu"), Duration.ofSeconds(10), 1,
                100_000, 100_000, 100_000, 10, 100);
    }

    /**
     * Copies resource limits while preserving the source command and language policy.
     * @param base original fixture options
     * @param timeout bounded request deadline
     * @param concurrency admitted process count
     * @param png maximum encoded PNG bytes
     * @param output maximum process output bytes
     * @param lines maximum parsed lines
     * @param characters maximum recognized characters
     * @return independent immutable resource policy
     */
    private static TesseractOptions copy(final TesseractOptions base, final Duration timeout, final int concurrency,
            final long png, final int output, final int lines, final int characters) {
        return new TesseractOptions(base.executable(), base.tessdataDirectory(), base.languages(), timeout, concurrency,
                base.maxImagePixels(), png, output, lines, characters);
    }

    /**
     * Runs an owned Java child through the production command/admission/TSV/cleanup path.
     * @param mode explicit child behavior
     * @param marker lifecycle directory, or null when marker files are unnecessary
     * @param options immutable bounds and segmentation policy
     * @return hidden adapter without process execution at construction
     */
    private static TesseractOcr provider(final String mode, final Path marker, final TesseractOptions options) {
        final String java = Path.of(System.getProperty("java.home"), "bin",
                                        System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                                    .toString();
        return new DefaultTesseractOcr(
                options, List.of(java, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"),
                                 OcrChildProcess.class.getName(), mode, marker == null ? "-" : marker.toString()));
    }

    /**
     * Creates a small opaque raster independent of installed fonts and source documents.
     * @return caller-owned100x100 RGB pixels
     */
    private static BufferedImage image() {
        return new BufferedImage(100, 100, BufferedImage.TYPE_INT_RGB);
    }

    /**
     * Requires the complete production call to fail with the expected public category.
     * @param code expected typed failure
     * @param provider owned child adapter
     */
    private static void assertCode(final PartitionException.Code code, final TesseractOcr provider) {
        assertEquals(code, assertThrows(PartitionException.class, () -> provider.recognize(image())).code());
    }

    /**
     * Counts launched child calls using only owned marker filenames.
     * @param directory lifecycle marker directory
     * @return number of observed child launches
     * @throws Exception if marker enumeration fails
     */
    private static int count(final Path directory) throws Exception {
        try (final var files = Files.list(directory)) {
            return (int) files.filter(path -> path.getFileName().toString().startsWith("call-")).count();
        }
    }

    /**
     * Waits at most five seconds for actual child launch evidence instead of guessing process startup timing.
     * @param directory lifecycle markers
     * @param expected exact launch count
     * @throws Exception if waiting or marker enumeration fails
     */
    private static void awaitCalls(final Path directory, final int expected) throws Exception {
        final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (count(directory) < expected && System.nanoTime() < end) {
            Thread.sleep(10);
        }
        assertEquals(expected, count(directory));
    }

    /**
     * Requires temporary input files/directories to disappear and marked child processes to terminate.
     * @param directory owned lifecycle markers
     * @throws Exception if fixture paths or bounded process-exit observation fail
     */
    private static void assertCleanup(final Path directory) throws Exception {
        try (final var files = Files.list(directory)) {
            for (final var marker : files.filter(path -> path.getFileName().toString().startsWith("call-")).toList()) {
                final var input = Path.of(Files.readString(marker));
                assertFalse(Files.exists(input));
                assertFalse(Files.exists(input.getParent()));
                final long pid =
                        Long.parseLong(marker.getFileName().toString().replace("call-", "").replace(".txt", ""));
                final long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while (ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false) && System.nanoTime() < end) {
                    Thread.sleep(10);
                }
                assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
            }
        }
    }
}
