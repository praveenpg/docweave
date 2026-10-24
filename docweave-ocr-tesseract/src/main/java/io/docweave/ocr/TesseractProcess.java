package io.docweave.ocr;

import io.docweave.partition.PartitionException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/** Direct subprocess execution with concurrent bounded drains and best-effort tree termination. */
final class TesseractProcess {
    /** Utility class. */
    private TesseractProcess() {
    }

    /**
     * Runs one trusted command. Neither stderr contents nor recognized text are included in diagnostics.
     * @param command argument list, without a shell
     * @param maxOutput stdout-byte ceiling
     * @param deadline shared admission/process deadline
     * @return complete stdout bytes only after successful exit
     * @throws IOException if process startup, exit, I/O, cancellation or timeout fails
     */
    static byte[] run(final List<String> command, final int maxOutput, final OcrDeadline deadline) throws IOException {
        final var result = execute(command, maxOutput, deadline, false);
        if (result.status() != 0) {
            throw new IOException("Tesseract exited with status " + result.status());
        }
        return result.stdout();
    }

    /**
     * Returns bounded private diagnostics for orientation's explicit insufficient-text decision.
     * Callers must never expose stderr or recognized text in exception messages.
     * @param command trusted argument list without a shell
     * @param maxOutput stdout ceiling
     * @param deadline shared admission/encoding/process/parser budget
     * @return exclusively owned stdout/stderr plus observed exit code
     * @throws IOException if process/pipe limits, cancellation or timeout fail
     */
    static Result observe(final List<String> command, final int maxOutput, final OcrDeadline deadline)
            throws IOException {
        return execute(command, maxOutput, deadline, true);
    }

    /**
     * Runs the shared bounded lifecycle with optional private diagnostic retention.
     * @param command trusted direct command
     * @param maxOutput stdout byte ceiling
     * @param deadline existing call deadline
     * @param retainDiagnostics whether bounded stderr is needed for orientation's known abstention
     * @return owned result; nonzero status remains the caller's explicit policy decision
     * @throws IOException if execution, byte limits, cancellation or timeout fail
     */
    private static Result execute(final List<String> command, final int maxOutput, final OcrDeadline deadline,
            final boolean retainDiagnostics) throws IOException {
        deadline.remaining();
        final var builder = new ProcessBuilder(command);
        builder.environment().put("OMP_THREAD_LIMIT", "1");
        final var process = builder.start();
        final var pipeFailure = new AtomicReference<IOException>();
        final var readers = Executors.newVirtualThreadPerTaskExecutor();
        try {
            process.getOutputStream().close();
            final var stdout =
                    readers.submit(() -> drain(process.getInputStream(), maxOutput, true, process, pipeFailure));
            final var stderr = readers.submit(
                    () -> drain(process.getErrorStream(), 64 * 1024, retainDiagnostics, process, pipeFailure));
            if (!process.waitFor(deadline.remaining(), TimeUnit.NANOSECONDS)) {
                deadline.remaining();
                throw new PartitionException(PartitionException.Code.OCR_TIMEOUT, "Tesseract recognition timed out");
            }
            final var bytes = await(stdout, deadline);
            final var diagnostics = await(stderr, deadline);
            return new Result(process.exitValue(), bytes, diagnostics);
        } catch (final IOException failure) {
            if (pipeFailure.get() != null) {
                throw pipeFailure.get();
            }
            throw failure;
        } catch (final InterruptedException failure) {
            Thread.currentThread().interrupt();
            final var interrupted = new InterruptedIOException("Tesseract recognition interrupted");
            interrupted.initCause(failure);
            throw interrupted;
        } finally {
            terminate(process);
            readers.shutdownNow();
        }
    }

    /**
     * Private owned subprocess result, never exposed through public application APIs.
     * @param status observed exit code
     * @param stdout exclusively owned bounded stdout bytes
     * @param stderr exclusively owned bounded diagnostic bytes, empty on ordinary OCR calls
     */
    record Result(int status, byte[] stdout, byte[] stderr) {}

    /**
     * Drains one pipe. Overflow kills the process immediately so another pipe cannot block completion.
     * @param input owned process pipe
     * @param limit byte ceiling
     * @param retain whether captured bytes must be retained
     * @param process process to stop on failure
     * @param pipeFailure first pipe failure, retained before terminating other reads
     * @return retained bytes, or an empty array for discarded diagnostics
     * @throws IOException if reading or the byte budget fails
     */
    private static byte[] drain(final InputStream input, final int limit, final boolean retain, final Process process,
            final AtomicReference<IOException> pipeFailure) throws IOException {
        try (input; final var output = new ByteArrayOutputStream(Math.min(limit, 8192))) {
            final var buffer = new byte[8192];
            int count = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                if (read > limit - count) {
                    throw new PartitionException(
                            PartitionException.Code.OCR_LIMIT, "Tesseract pipe exceeds byte limit");
                }
                count += read;
                if (retain) {
                    output.write(buffer, 0, read);
                }
            }
            return output.toByteArray();
        } catch (final IOException failure) {
            pipeFailure.compareAndSet(null, failure);
            terminate(process);
            throw failure;
        }
    }

    /**
     * Obtains one completed drain while preserving checked causes.
     * @param future pipe task
     * @param deadline shared deadline
     * @return captured bytes
     * @throws IOException if draining, cancellation or timeout fails
     * @throws InterruptedException if the waiting thread is interrupted
     */
    private static byte[] await(final Future<byte[]> future, final OcrDeadline deadline)
            throws IOException, InterruptedException {
        try {
            return future.get(deadline.remaining(), TimeUnit.NANOSECONDS);
        } catch (final TimeoutException failure) {
            throw new PartitionException(PartitionException.Code.OCR_TIMEOUT, "Tesseract pipe timed out", failure);
        } catch (final ExecutionException failure) {
            if (failure.getCause() instanceof IOException checked) {
                throw checked;
            }
            throw new IOException("Tesseract pipe failed", failure.getCause());
        }
    }

    /**
     * Forcibly stops visible descendants and the direct process. Detached/reparented processes and OS
     * termination latency are outside this adapter's guarantees; trusted direct Tesseract binaries are required.
     * @param process owned subprocess
     */
    private static void terminate(final Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }
}
