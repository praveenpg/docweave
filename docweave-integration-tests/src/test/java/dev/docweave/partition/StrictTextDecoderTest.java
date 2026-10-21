package dev.docweave.partition;

import org.junit.jupiter.api.Test;

import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class StrictTextDecoderTest {
    @Test
    void everyAsciiByteAndPayloadSlicesMatchStrictUtf8() throws Exception {
        final byte[] bytes = new byte[130];
        bytes[0] = (byte) 0xff;
        bytes[129] = (byte) 0xff;
        for (int point = 0; point < 128; point++) {
            bytes[point + 1] = (byte) point;
        }
        assertEquals(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes, 1, 128)).toString(),
                StrictTextDecoder.decode(bytes, 1, 128, StandardCharsets.UTF_8));
        assertEquals("", StrictTextDecoder.decode(bytes, 1, 0, StandardCharsets.UTF_8));
        assertEquals("a".repeat(30000), StrictTextDecoder.decode("a".repeat(30000).getBytes(StandardCharsets.UTF_8), 0,
                                                30000, StandardCharsets.UTF_8));
    }

    @Test
    void nonAsciiAndStatefulCharsetsKeepReportingDecoderSemantics() throws Exception {
        for (final String name : List.of("UTF-8", "UTF-16LE", "UTF-16BE", "UTF-32LE", "windows-1252", "ISO-2022-JP")) {
            final var charset = Charset.forName(name);
            final byte[] bytes = "Café 漢 🧵. ASCII.".getBytes(charset);
            String expected = charset.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            assertEquals(expected, StrictTextDecoder.decode(bytes, 0, bytes.length, charset));
        }
    }

    @Test
    void malformedUtf8AfterLongAsciiPrefixStillFailsStrictly() {
        for (final byte[] suffix : List.of(new byte[] {(byte) 0xc3}, new byte[] {(byte) 0x80},
                     new byte[] {(byte) 0xc0, (byte) 0xaf}, new byte[] {(byte) 0xed, (byte) 0xa0, (byte) 0x80},
                     new byte[] {(byte) 0xf4, (byte) 0x90, (byte) 0x80, (byte) 0x80})) {
            final byte[] bytes = new byte[20000 + suffix.length];
            Arrays.fill(bytes, (byte) 'a');
            System.arraycopy(suffix, 0, bytes, 20000, suffix.length);
            assertThrows(CharacterCodingException.class,
                    () -> StrictTextDecoder.decode(bytes, 0, bytes.length, StandardCharsets.UTF_8));
        }
        assertThrows(CharacterCodingException.class,
                () -> StrictTextDecoder.decode(new byte[] {(byte) 0x80}, 0, 1, StandardCharsets.US_ASCII));
        assertThrows(CharacterCodingException.class,
                () -> StrictTextDecoder.decode(new byte[] {65}, 0, 1, StandardCharsets.UTF_16LE));
    }

    @Test
    void randomUtf8BytesAgreeWithReportingDecoderOnSuccessAndFailure() throws Exception {
        final var random = new Random(532);
        for (int trial = 0; trial < 1000; trial++) {
            final byte[] bytes = new byte[random.nextInt(200)];
            random.nextBytes(bytes);
            final var decoder = StandardCharsets.UTF_8.newDecoder()
                                        .onMalformedInput(CodingErrorAction.REPORT)
                                        .onUnmappableCharacter(CodingErrorAction.REPORT);
            String expected;
            try {
                expected = decoder.decode(ByteBuffer.wrap(bytes)).toString();
            } catch (final CharacterCodingException failure) {
                final var actual = assertThrows(CharacterCodingException.class,
                        () -> StrictTextDecoder.decode(bytes, 0, bytes.length, StandardCharsets.UTF_8));
                assertEquals(failure.getClass(), actual.getClass());
                continue;
            }
            assertEquals(expected, StrictTextDecoder.decode(bytes, 0, bytes.length, StandardCharsets.UTF_8));
        }
    }

    @Test
    void customCharsetWithUtf8NameStillUsesItsOwnDecoder() throws Exception {
        final var invoked = new java.util.concurrent.atomic.AtomicBoolean();
        final var custom = new Charset("UTF-8", new String[0]) {
            public boolean contains(final Charset other) {
                return false;
            }
            public java.nio.charset.CharsetDecoder newDecoder() {
                invoked.set(true);
                return StandardCharsets.US_ASCII.newDecoder();
            }
            public java.nio.charset.CharsetEncoder newEncoder() {
                throw new UnsupportedOperationException();
            }
        };
        assertEquals("a", StrictTextDecoder.decode(new byte[] {97}, 0, 1, custom));
        assertTrue(invoked.get(), "A canonical name alone must not bypass a custom decoder");
    }

    @Test
    void interruptionPreservesFlagForAsciiUnicodeAndEmptyInputAndAllowsReuse() throws Exception {
        Thread.currentThread().interrupt();
        try {
            for (final String text : List.of("", "ascii", "漢🧵")) {
                final byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                assertThrows(InterruptedIOException.class,
                        () -> StrictTextDecoder.decode(bytes, 0, bytes.length, StandardCharsets.UTF_8));
                assertTrue(Thread.currentThread().isInterrupted());
            }
        } finally {
            Thread.interrupted();
        }
        assertEquals("a", StrictTextDecoder.decode(new byte[] {65 + 32}, 0, 1, StandardCharsets.UTF_8));
    }
}
