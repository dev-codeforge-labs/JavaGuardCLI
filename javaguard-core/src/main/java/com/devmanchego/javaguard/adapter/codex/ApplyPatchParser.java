package com.devmanchego.javaguard.adapter.codex;

import com.devmanchego.javaguard.model.ChangeType;
import com.devmanchego.javaguard.model.EditFragment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parses the Codex {@code apply_patch} envelope (contracts &sect;5.2):
 *
 * <pre>
 * *** Begin Patch
 * *** Add File: path          lines prefixed with '+'
 * *** Update File: path
 * *** Move to: new/path       (optional)
 * &#64;&#64; optional context header
 *  context line               ' ' prefix
 * -removed line               '-' prefix
 * +added line                 '+' prefix
 * *** End of File             (optional marker, ignored)
 * *** Delete File: path
 * *** End Patch
 * </pre>
 *
 * <p>The parser is deliberately lenient: JavaGuard must recover the set of
 * touched files even from a patch it does not fully understand, so stray
 * lines are ignored rather than rejected, envelope markers are optional,
 * CRLF is accepted, and hunk lines without a preceding {@code @@} open an
 * implicit hunk. An empty line inside a hunk is an empty context line.
 */
public final class ApplyPatchParser {

    private static final String ADD = "*** Add File:";
    private static final String UPDATE = "*** Update File:";
    private static final String DELETE = "*** Delete File:";
    private static final String MOVE_TO = "*** Move to:";

    private ApplyPatchParser() {
    }

    public static List<PatchFileOp> parse(String patch) {
        List<PatchFileOp> ops = new ArrayList<>();
        if (patch == null || patch.isBlank()) {
            return ops;
        }
        Builder current = null;
        String text = patch.replace("\r\n", "\n");
        if (text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1); // the final newline terminates a line, it does not start one
        }
        for (String rawLine : text.split("\n", -1)) {
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.startsWith(ADD)) {
                current = flush(ops, current);
                current = new Builder(ChangeType.ADD, header(line, ADD));
            } else if (line.startsWith(UPDATE)) {
                current = flush(ops, current);
                current = new Builder(ChangeType.UPDATE, header(line, UPDATE));
            } else if (line.startsWith(DELETE)) {
                current = flush(ops, current);
                current = new Builder(ChangeType.DELETE, header(line, DELETE));
            } else if (line.startsWith(MOVE_TO)) {
                if (current != null && current.type == ChangeType.UPDATE) {
                    current.movedTo = header(line, MOVE_TO);
                }
            } else if (line.startsWith("***")) {
                // *** Begin Patch, *** End Patch, *** End of File: structural markers only.
                if (current != null) {
                    current.closeHunk();
                }
            } else if (current != null && current.acceptsHunks()) {
                current.hunkLine(line);
            }
            // Lines outside any file, and '+' content lines of Add File, are ignored.
        }
        flush(ops, current);
        return ops;
    }

    private static String header(String line, String prefix) {
        return line.substring(prefix.length()).strip();
    }

    private static Builder flush(List<PatchFileOp> ops, Builder builder) {
        if (builder != null && !builder.path.isEmpty()) {
            ops.add(builder.build());
        }
        return null;
    }

    private static final class Builder {
        private final ChangeType type;
        private final String path;
        private String movedTo;
        private final List<EditFragment> fragments = new ArrayList<>();
        private List<String> oldLines;
        private List<String> newLines;

        Builder(ChangeType type, String path) {
            this.type = type;
            this.path = path;
        }

        boolean acceptsHunks() {
            return type == ChangeType.UPDATE;
        }

        void hunkLine(String line) {
            if (line.startsWith("@@")) {
                closeHunk();
                openHunk();
                return;
            }
            char prefix = line.isEmpty() ? ' ' : line.charAt(0);
            String content = line.isEmpty() ? "" : line.substring(1);
            if (prefix != ' ' && prefix != '-' && prefix != '+') {
                return; // not a hunk line; ignore
            }
            if (line.isEmpty() && oldLines == null) {
                return; // a blank line between sections never opens a hunk
            }
            if (oldLines == null) {
                openHunk(); // implicit hunk without '@@'
            }
            if (prefix == ' ' || prefix == '-') {
                oldLines.add(content);
            }
            if (prefix == ' ' || prefix == '+') {
                newLines.add(content);
            }
        }

        void openHunk() {
            oldLines = new ArrayList<>();
            newLines = new ArrayList<>();
        }

        void closeHunk() {
            if (oldLines != null && (!oldLines.isEmpty() || !newLines.isEmpty())) {
                fragments.add(new EditFragment(String.join("\n", oldLines), String.join("\n", newLines), false));
            }
            oldLines = null;
            newLines = null;
        }

        PatchFileOp build() {
            closeHunk();
            if (type == ChangeType.UPDATE && movedTo != null && !movedTo.isEmpty()) {
                return new PatchFileOp(ChangeType.MOVE, path, Optional.of(movedTo), fragments);
            }
            return new PatchFileOp(type, path, Optional.empty(), acceptsHunks() ? fragments : List.of());
        }
    }
}
