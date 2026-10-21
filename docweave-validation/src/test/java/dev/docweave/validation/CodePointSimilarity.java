package dev.docweave.validation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Independently implemented longest-contiguous-block diagnostic, matching SequenceMatcher with no junk filter.
 * Tie order is earliest reference position, then earliest candidate position; argument order matters.
 * Popular symbols are never discarded. This is not edit distance, OCR accuracy or an upstream metric.
 */
final class CodePointSimilarity {
    private record Window(int leftStart, int leftEnd, int rightStart, int rightEnd) {}

    private record Block(int left, int right, int size) {}

    private CodePointSimilarity() {
    }

    /** Uses Unicode code points and an explicit work stack, avoiding UTF-16 counts and recursive stack growth. */
    static double ratio(final String reference, final String candidate) {
        final int[] left = reference.codePoints().toArray();
        final int[] right = candidate.codePoints().toArray();
        final long total = (long) left.length + right.length;
        if (total == 0) {
            return 1.0;
        }
        final var positions = new HashMap<Integer, List<Integer>>();
        for (int index = 0; index < right.length; index++) {
            positions.computeIfAbsent(right[index], ignored -> new ArrayList<>()).add(index);
        }
        final var pending = new ArrayDeque<Window>();
        pending.push(new Window(0, left.length, 0, right.length));
        long matched = 0;
        while (!pending.isEmpty()) {
            final var window = pending.pop();
            final var block = longest(left, positions, window);
            if (block.size() == 0) {
                continue;
            }
            matched += block.size();
            if (window.leftStart() < block.left() && window.rightStart() < block.right()) {
                pending.push(new Window(window.leftStart(), block.left(), window.rightStart(), block.right()));
            }
            final int leftEnd = block.left() + block.size();
            final int rightEnd = block.right() + block.size();
            if (leftEnd < window.leftEnd() && rightEnd < window.rightEnd()) {
                pending.push(new Window(leftEnd, window.leftEnd(), rightEnd, window.rightEnd()));
            }
        }
        return 2.0 * matched / total;
    }

    /** Tracks contiguous runs ending at matching candidate positions; strict improvement preserves tie order. */
    private static Block longest(final int[] left, final Map<Integer, List<Integer>> positions, final Window window) {
        var best = new Block(window.leftStart(), window.rightStart(), 0);
        Map<Integer, Integer> previous = Map.of();
        for (int index = window.leftStart(); index < window.leftEnd(); index++) {
            final var current = new HashMap<Integer, Integer>();
            for (final int other : positions.getOrDefault(left[index], List.of())) {
                if (other < window.rightStart()) {
                    continue;
                }
                if (other >= window.rightEnd()) {
                    break;
                }
                final int length = previous.getOrDefault(other - 1, 0) + 1;
                current.put(other, length);
                if (length > best.size()) {
                    best = new Block(index - length + 1, other - length + 1, length);
                }
            }
            previous = current;
        }
        return best;
    }
}
