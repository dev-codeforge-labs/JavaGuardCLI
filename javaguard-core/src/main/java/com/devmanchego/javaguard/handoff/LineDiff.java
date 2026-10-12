package com.devmanchego.javaguard.handoff;

import java.util.ArrayList;
import java.util.List;

/** A small unified-style line diff (longest common subsequence) for the handoff's attempt diffs. */
final class LineDiff {

    private static final int MAX_LINES = 3000;
    private static final int CONTEXT = 2;

    private LineDiff() {
    }

    /** Hunks with {@code +}/{@code -} lines and two lines of context; empty when the texts are equal. */
    static List<String> diff(String before, String after) {
        List<String> a = lines(before);
        List<String> b = lines(after);
        if (a.equals(b)) {
            return List.of();
        }
        if (a.size() > MAX_LINES || b.size() > MAX_LINES) {
            return List.of("(file too large to diff: " + a.size() + " -> " + b.size() + " lines)");
        }
        int[][] lcs = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                lcs[i][j] = a.get(i).equals(b.get(j)) ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }
        List<String> edit = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() || j < b.size()) {
            if (i < a.size() && j < b.size() && a.get(i).equals(b.get(j))) {
                edit.add(" " + a.get(i++));
                j++;
            } else if (j < b.size() && (i == a.size() || lcs[i][j + 1] >= lcs[i + 1][j])) {
                edit.add("+" + b.get(j++));
            } else {
                edit.add("-" + a.get(i++));
            }
        }
        return withContext(edit);
    }

    private static List<String> withContext(List<String> edit) {
        boolean[] keep = new boolean[edit.size()];
        for (int k = 0; k < edit.size(); k++) {
            if (edit.get(k).charAt(0) != ' ') {
                for (int c = Math.max(0, k - CONTEXT); c <= Math.min(edit.size() - 1, k + CONTEXT); c++) {
                    keep[c] = true;
                }
            }
        }
        List<String> out = new ArrayList<>();
        boolean gap = false;
        for (int k = 0; k < edit.size(); k++) {
            if (keep[k]) {
                if (gap && !out.isEmpty()) {
                    out.add("@@");
                }
                gap = false;
                out.add(edit.get(k));
            } else {
                gap = true;
            }
        }
        return out;
    }

    private static List<String> lines(String text) {
        if (text.isEmpty()) {
            return List.of();
        }
        return List.of(text.split("\\R", -1));
    }
}
