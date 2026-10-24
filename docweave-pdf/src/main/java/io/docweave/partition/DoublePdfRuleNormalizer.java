package io.docweave.partition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Explicit native-only normalization of isolated paired rules with empty intervening bands. */
final class DoublePdfRuleNormalizer {
    /** Existing source-axis tolerance; never widened by normalization. */
    private static final float SNAP = 0.5f;
    /** Maximum unscaled distance between a paired rule's two axes. */
    private static final float MAX_GAP = 2.5f;

    /** Creates a stateless page strategy. */
    DoublePdfRuleNormalizer() {
    }

    /**
     * One source axis bucket, coalescing only historical near-duplicate coordinates.
     * @param position first coordinate, avoiding transitive bucket drift
     * @param spans original along-axis intervals
     */
    private record Axis(float position, List<float[]> spans) {}

    /**
     * Candidate band plus source axes retained for independent coverage validation.
     * @param low lower coordinate
     * @param high higher coordinate
     * @param start envelope start
     * @param end envelope end
     * @param first first original axis
     * @param second second original axis
     */
    private record Band(float low, float high, float start, float end, Axis first, Axis second) {}

    /**
     * Validates both axes before moving source geometry; glyph/image barriers veto occupied bands.
     * Candidate coverage failure leaves the entire page geometry unchanged rather than partially repairing it.
     * @param segments original displayed native segments
     * @param barriers displayed glyph/image rectangles
     * @param work shared page table budget
     * @return original geometry or normalized immutable segments
     * @throws IOException if cancelled or the shared budget fails
     */
    List<PdfPageGraphics.Segment> normalize(final List<PdfPageGraphics.Segment> segments,
            final List<PdfLayoutItem.Bounds> barriers, final PdfTableWork work) throws IOException {
        final var horizontal = bands(axes(segments, true, work), true, barriers, work);
        final var vertical = bands(axes(segments, false, work), false, barriers, work);
        if (horizontal.isEmpty() && vertical.isEmpty()) {
            return segments;
        }
        for (final var band : horizontal) {
            if (!covered(band.first(), vertical, work) || !covered(band.second(), vertical, work)) {
                return segments;
            }
        }
        for (final var band : vertical) {
            if (!covered(band.first(), horizontal, work) || !covered(band.second(), horizontal, work)) {
                return segments;
            }
        }
        final var result = new ArrayList<PdfPageGraphics.Segment>();
        for (final var segment : segments) {
            work.step();
            // Unsupported diagonals/curves are not turned into axis-aligned rules.
            if (Math.abs(segment.y1() - segment.y2()) < SNAP || Math.abs(segment.x1() - segment.x2()) < SNAP) {
                result.add(new PdfPageGraphics.Segment(map(segment.x1(), segment.y1(), vertical, work),
                        map(segment.y1(), segment.x1(), horizontal, work),
                        map(segment.x2(), segment.y2(), vertical, work),
                        map(segment.y2(), segment.x2(), horizontal, work)));
            } else {
                result.add(segment);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Groups comparable parallel segments while retaining original intervals for later coverage validation.
     * @param segments page geometry
     * @param horizontal requested orientation
     * @param work shared counter
     * @return sorted source axes
     * @throws IOException on cancellation or budget exhaustion
     */
    private static List<Axis> axes(final List<PdfPageGraphics.Segment> segments, final boolean horizontal,
            final PdfTableWork work) throws IOException {
        final var selected = new ArrayList<PdfPageGraphics.Segment>();
        for (final var segment : segments) {
            work.step();
            final float along =
                    horizontal ? Math.abs(segment.x2() - segment.x1()) : Math.abs(segment.y2() - segment.y1());
            final float across =
                    horizontal ? Math.abs(segment.y2() - segment.y1()) : Math.abs(segment.x2() - segment.x1());
            if (along > 2 && across < SNAP) {
                selected.add(segment);
            }
        }
        selected.sort(Comparator.comparingDouble(segment -> horizontal ? segment.y1() : segment.x1()));
        final var axes = new ArrayList<Axis>();
        for (final var segment : selected) {
            work.step();
            final float position = horizontal ? segment.y1() : segment.x1();
            if (axes.isEmpty() || position - axes.getLast().position() > SNAP) {
                axes.add(new Axis(position, new ArrayList<>()));
            }
            final float from = horizontal ? segment.x1() : segment.y1();
            final float to = horizontal ? segment.x2() : segment.y2();
            axes.getLast().spans().add(new float[] {Math.min(from, to), Math.max(from, to)});
        }
        return axes;
    }

    /**
     * Requires an isolated pair, similar extents and an empty band; triples and chains are never coalesced.
     * @param axes source buckets
     * @param horizontal axis orientation
     * @param barriers text/image veto rectangles
     * @param work shared counter
     * @return eligible source pairs
     * @throws IOException on cancellation or budget exhaustion
     */
    private static List<Band> bands(final List<Axis> axes, final boolean horizontal,
            final List<PdfLayoutItem.Bounds> barriers, final PdfTableWork work) throws IOException {
        final var result = new ArrayList<Band>();
        for (int index = 0; index + 1 < axes.size(); index++) {
            work.step();
            final var first = axes.get(index);
            final var second = axes.get(index + 1);
            if (second.position() - first.position() > MAX_GAP
                    || index > 0 && first.position() - axes.get(index - 1).position() <= MAX_GAP
                    || index + 2 < axes.size() && axes.get(index + 2).position() - second.position() <= MAX_GAP) {
                continue;
            }
            final float[] one = envelope(first, work);
            final float[] two = envelope(second, work);
            if (Math.abs(one[0] - two[0]) > MAX_GAP || Math.abs(one[1] - two[1]) > MAX_GAP) {
                continue;
            }
            final var band = new Band(first.position(), second.position(), Math.min(one[0], two[0]),
                    Math.max(one[1], two[1]), first, second);
            boolean empty = true;
            for (final var box : barriers) {
                work.step();
                final float acrossStart = horizontal ? box.top() : box.left();
                final float acrossEnd = horizontal ? box.bottom() : box.right();
                final float alongStart = horizontal ? box.left() : box.top();
                final float alongEnd = horizontal ? box.right() : box.bottom();
                if (acrossEnd >= band.low() - SNAP && acrossStart <= band.high() + SNAP && alongEnd >= band.start()
                        && alongStart <= band.end()) {
                    empty = false;
                    break;
                }
            }
            if (empty) {
                result.add(band);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Finds the original extent of one axis.
     * @param axis source intervals
     * @param work shared counter
     * @return minimum start and maximum end
     * @throws IOException on cancellation or budget exhaustion
     */
    private static float[] envelope(final Axis axis, final PdfTableWork work) throws IOException {
        float start = Float.POSITIVE_INFINITY;
        float end = Float.NEGATIVE_INFINITY;
        for (final var span : axis.spans()) {
            work.step();
            start = Math.min(start, span[0]);
            end = Math.max(end, span[1]);
        }
        return new float[] {start, end};
    }

    /**
     * Requires each original rule to remain independently continuous after perpendicular junction mapping.
     * @param axis original source axis
     * @param perpendicular eligible perpendicular bands
     * @param work shared counter
     * @return whether no unsupported missing interval remains
     * @throws IOException on cancellation or budget exhaustion
     */
    private static boolean covered(final Axis axis, final List<Band> perpendicular, final PdfTableWork work)
            throws IOException {
        final var spans = new ArrayList<float[]>();
        for (final var span : axis.spans()) {
            work.step();
            spans.add(new float[] {map(span[0], axis.position(), perpendicular, work),
                    map(span[1], axis.position(), perpendicular, work)});
        }
        spans.sort(Comparator.comparingDouble(span -> span[0]));
        float end = spans.getFirst()[1];
        for (final var span : spans) {
            work.step();
            if (span[0] > end + SNAP) {
                return false;
            }
            end = Math.max(end, span[1]);
        }
        return true;
    }

    /**
     * Snaps coordinates only inside an eligible pair plus the historical endpoint tolerance.
     * @param position original coordinate
     * @param along perpendicular coordinate, restricting changes to source extent
     * @param bands eligible pairs
     * @param work shared counter
     * @return original coordinate or the pair midpoint
     * @throws IOException on cancellation or budget exhaustion
     */
    private static float map(final float position, final float along, final List<Band> bands, final PdfTableWork work)
            throws IOException {
        for (final var band : bands) {
            work.step();
            if (position >= band.low() - SNAP && position <= band.high() + SNAP && along >= band.start() - SNAP
                    && along <= band.end() + SNAP) {
                return band.low() + (band.high() - band.low()) / 2;
            }
        }
        return position;
    }
}
