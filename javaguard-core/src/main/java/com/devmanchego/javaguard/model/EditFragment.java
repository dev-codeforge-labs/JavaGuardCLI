package com.devmanchego.javaguard.model;

import java.util.Objects;

/**
 * One textual replacement the agent applied to a file: {@code oldText} was
 * replaced by {@code newText}.
 *
 * <p>Every agent edit shape maps onto this record, so baseline reconstruction
 * (specification &sect;9.1, Phase 4) has a single algorithm: reverse-apply
 * the fragments, last first.
 *
 * <ul>
 *   <li>Claude {@code Edit}: one fragment ({@code old_string}, {@code new_string}, {@code replace_all}).</li>
 *   <li>Claude {@code MultiEdit}: one fragment per entry of {@code edits[]}, in order.</li>
 *   <li>Gemini {@code replace}: one fragment.</li>
 *   <li>Codex {@code apply_patch} {@code Update File}: one fragment per {@code @@} hunk;
 *       {@code oldText} = context and removed lines, {@code newText} = context and added lines.</li>
 * </ul>
 *
 * @param oldText    text before the edit (may be empty, never null)
 * @param newText    text after the edit (may be empty, never null)
 * @param replaceAll true when every occurrence of {@code oldText} was replaced
 */
public record EditFragment(String oldText, String newText, boolean replaceAll) {

    public EditFragment {
        Objects.requireNonNull(oldText, "oldText");
        Objects.requireNonNull(newText, "newText");
    }
}
