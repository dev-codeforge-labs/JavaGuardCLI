package com.devmanchego.javaguard.change;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Chunk;
import com.github.difflib.patch.Patch;

import java.util.List;

/**
 * Lines of the current file changed relative to the baseline (specification
 * &sect;9.2), computed with java-diff-utils on line lists, so line terminators
 * ({@code \n}, {@code \r\n}, {@code \r}) never count as changes.
 *
 * <p>Inserted and modified lines are marked directly. A pure deletion has no
 * line of its own in the current file; the lines immediately before and after
 * the gap are marked instead, because that is where a finding caused by the
 * deletion (a removed {@code close()}, a removed null check) is reported.
 */
public final class ChangedLines {

    private ChangedLines() {
    }

    public static LineRanges between(String baseline, String current) {
        List<String> original = baseline.lines().toList();
        List<String> revised = current.lines().toList();
        if (revised.isEmpty()) {
            return LineRanges.empty();
        }
        Patch<String> patch = DiffUtils.diff(original, revised);
        LineRanges.Builder ranges = LineRanges.builder();
        for (AbstractDelta<String> delta : patch.getDeltas()) {
            Chunk<String> target = delta.getTarget();
            int position = target.getPosition(); // 0-based index in the current file
            if (target.size() > 0) {
                ranges.add(position + 1, position + target.size());
            } else {
                // Deletion: the gap sits between 1-based lines `position` and `position + 1`.
                ranges.add(Math.max(1, position), Math.min(revised.size(), position + 1));
            }
        }
        return ranges.build();
    }
}
