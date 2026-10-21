package dev.docweave.partition;

import dev.docweave.core.ElementType;
import org.apache.commons.csv.CSVFormat;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.util.ArrayList;

/** Stateless quoted-record extraction shared by CSV and TSV strategies. */
final class DelimitedTablePartitioner {
    /** Prevents instantiation of this stateless utility class. */
    private DelimitedTablePartitioner() {
    }

    /**
     * Extracts quoted records through common table budgets and emission rules.
     * @param context synchronous document context
     * @param delimiter comma or tab selected by the installed strategy
     * @throws IOException if parsing, budgets or interruption fails
     */
    static void partition(final PartitionContext context, final char delimiter) throws IOException {
        final var options = context.options().delimitedOptions();
        final var table = new StructuredTable(options.tableLimits());
        final var format = CSVFormat.RFC4180.builder()
                                   .setDelimiter(delimiter)
                                   .setIgnoreEmptyLines(true)
                                   .setTrim(false)
                                   .setIgnoreSurroundingSpaces(false)
                                   .setLenientEof(false)
                                   .setTrailingData(false)
                                   .get();
        int width = -1;
        int ragged = 0;
        // Stream quoted delimited records while enforcing table budgets and checking column count consistency against
        // the first record.
        try (final var parser = format.parse(new StringReader(context.text()))) {
            for (final var record : parser) {
                ElementCollector.checkInterrupted();
                table.checkRow(record.size());
                if (width < 0) {
                    width = record.size();
                } else if (record.size() != width) {
                    if (options.strictColumns()) {
                        throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT,
                                "Delimited record " + record.getRecordNumber() + " has inconsistent column count");
                    }
                    ragged++;
                }
                final var values = new ArrayList<StructuredTable.Cell>(record.size());
                for (final String value : record) {
                    values.add(new StructuredTable.Cell(value));
                }
                table.row(values, table.rowCount() == 0 && options.header() == DelimitedOptions.Header.FIRST_ROW);
            }
        } catch (final PartitionException | InterruptedIOException error) {
            throw error;
        } catch (final UncheckedIOException error) {
            throw new PartitionException(PartitionException.Code.MALFORMED_DOCUMENT,
                    "Malformed quoted delimited document", error.getCause());
        } catch (final IOException error) {
            throw new PartitionException(
                    PartitionException.Code.MALFORMED_DOCUMENT, "Malformed quoted delimited document", error);
        }
        if (table.rowCount() == 0) {
            return;
        }
        if (ragged > 0) {
            context.warn(PartitionResult.WarningCode.RAGGED_TABLE,
                    "Preserved " + ragged + " records with inconsistent column counts");
        }
        context.emit(ElementType.Table, table.text(), table.metadata());
    }
}
