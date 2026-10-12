package com.devmanchego.javaguard.change;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Immutable set of 1-based, inclusive line ranges, merged and sorted
 * (overlapping or adjacent ranges become one).
 */
public final class LineRanges {

    private static final LineRanges EMPTY = new LineRanges(List.of());

    private final List<int[]> ranges;

    private LineRanges(List<int[]> ranges) {
        this.ranges = ranges;
    }

    public static LineRanges empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return ranges.isEmpty();
    }

    public boolean contains(int line) {
        if (line < 1) {
            return false;
        }
        for (int[] r : ranges) {
            if (line < r[0]) {
                return false;
            }
            if (line <= r[1]) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return ranges.stream().map(r -> r[0] + "-" + r[1]).collect(Collectors.joining(", ", "[", "]"));
    }

    public static final class Builder {
        private final List<int[]> pending = new ArrayList<>();

        /** Adds {@code [from, to]}; ignored when empty or entirely below line 1. */
        public Builder add(int from, int to) {
            int start = Math.max(1, from);
            if (to >= start) {
                pending.add(new int[] {start, to});
            }
            return this;
        }

        public LineRanges build() {
            if (pending.isEmpty()) {
                return EMPTY;
            }
            List<int[]> sorted = new ArrayList<>(pending);
            sorted.sort(Comparator.comparingInt(r -> r[0]));
            List<int[]> merged = new ArrayList<>();
            for (int[] r : sorted) {
                int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
                if (last != null && r[0] <= last[1] + 1) {
                    last[1] = Math.max(last[1], r[1]);
                } else {
                    merged.add(new int[] {r[0], r[1]});
                }
            }
            return new LineRanges(List.copyOf(merged));
        }
    }
}
