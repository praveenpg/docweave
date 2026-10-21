package dev.docweave.ocr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.imageio.ImageIO;

/** Owned subprocess control; exercises real process pipes without claiming to perform OCR. */
public final class OcrChildProcess {
    public static void main(final String[] args) throws Exception {
        final var image = ImageIO.read(Path.of(args[2]).toFile());
        if (image == null || image.getWidth() != 100 || image.getHeight() != 100) {
            System.exit(9);
        }
        final boolean whiteCellEdge = image.getRGB(30, 12) == java.awt.Color.WHITE.getRGB();
        final boolean whitePagePixel = image.getRGB(17, 19) == java.awt.Color.WHITE.getRGB();
        image.flush();
        if (!args[1].equals("-")) {
            final var directory = Path.of(args[1]);
            final var pending = Files.createTempFile(directory, "pending-", ".tmp");
            Files.writeString(pending, args[2]);
            // Publish readiness only after the whole input path is visible to the parent test.
            Files.move(pending, directory.resolve("call-" + ProcessHandle.current().pid() + ".txt"),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        switch (args[0]) {
        case "osd", "osd-low", "osd-first-hang" -> {
            if (!Arrays.asList(args).contains("osd") || !args[Arrays.asList(args).indexOf("--oem") + 1].equals("0")
                    || !args[Arrays.asList(args).indexOf("--psm") + 1].equals("0")) {
                System.exit(8);
            }
            if (args[0].equals("osd-first-hang")) {
                try (final var markers = Files.list(Path.of(args[1]))) {
                    if (markers.filter(path -> path.getFileName().toString().startsWith("call-")).count() == 1) {
                        Thread.sleep(30_000);
                    }
                }
            }
            System.out.println("Page number: 0\nOrientation in degrees: 270\nRotate: 90\nOrientation confidence: "
                               + (args[0].equals("osd-low") ? "4.0" : "18.0")
                               + "\nScript: Latin\nScript confidence: 3.5");
        }
        case "osd-too-few" -> {
            System.err.println("Too few characters. Skipping this page");
            System.exit(1);
        }
        case "hang" -> Thread.sleep(30_000);
        case "floodout" -> System.out.write(new byte[1_000_000]);
        case "flooderr" -> System.err.write(new byte[128_000]);
        case "badutf8" -> System.out.write(new byte[] {(byte) 0xc0, (byte) 0xaf});
        case "exit" -> {
            System.err.println("private diagnostics must not be exposed");
            System.exit(7);
        }
        case "malformed" -> System.out.println("unexpected schema");
        default -> {
            if (!Arrays.asList(args).contains("eng+deu")) {
                System.exit(8);
            }
            System.out.println(
                    "level\tpage_num\tblock_num\tpar_num\tline_num\tword_num\tleft\ttop\twidth\theight\tconf\ttext");
            System.out.println("5\t1\t1\t1\t1\t1\t10\t10\t20\t10\t90\tHello");
            System.out.println("5\t1\t1\t1\t1\t2\t35\t10\t20\t10\t90\tworld");
            final String threshold = Arrays.stream(args)
                                             .filter(arg -> arg.startsWith("thresholding_method="))
                                             .findFirst()
                                             .orElse("thresholding_method=default")
                                             .substring("thresholding_method=".length());
            final String last =
                    args[0].equals("threshold")
                            ? "threshold-" + threshold + "-mode-" + args[Arrays.asList(args).indexOf("--psm") + 1]
                    : args[0].equals("dpi") ? "dpi-"
                                                      + (Arrays.asList(args).contains("--dpi")
                                                                      ? args[Arrays.asList(args).indexOf("--dpi") + 1]
                                                                      : "default")
                                                      + "-threshold-" + threshold
                    : args[0].equals("page-whitepoint") ? (whitePagePixel ? "input-white" : "input-original")
                    : args[0].equals("cell-edge")       ? (whiteCellEdge ? "edge-white" : "edge-black")
                    : args[0].equals("psm")             ? "mode-" + args[Arrays.asList(args).indexOf("--psm") + 1]
                                                        : "again";
            System.out.println("5\t1\t1\t1\t2\t1\t10\t30\t20\t10\t90\t" + last);
        }
        }
    }
}
