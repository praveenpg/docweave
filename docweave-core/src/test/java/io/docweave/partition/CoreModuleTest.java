package io.docweave.partition;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoreModuleTest {
    @Test
    void coreAloneHasNoInstalledParsersOrExternalRuntimeLibraries() {
        assertTrue(io.docweave.DocWeave.create().supportedFormats().isEmpty());
        for (final String type : java.util.List.of("org.jsoup.Jsoup", "org.commonmark.parser.Parser",
                     "org.apache.poi.xwpf.usermodel.XWPFDocument", "org.apache.commons.csv.CSVFormat",
                     "tools.jackson.databind.json.JsonMapper")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(type));
        }
        final var error = assertThrows(
                PartitionException.class, () -> io.docweave.DocWeave.create().partition(DocumentSource.text("text")));
        assertEquals(PartitionException.Code.UNSUPPORTED_FORMAT, error.code());
    }
}
