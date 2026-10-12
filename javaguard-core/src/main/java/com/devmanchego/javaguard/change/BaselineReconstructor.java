package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.model.EditFragment;

import java.util.List;
import java.util.Optional;

/**
 * Recovers a file's exact pre-edit content from its current content and the
 * edit fragments the agent reported (specification &sect;9.1 step 2), by
 * reverse-applying the fragments last to first.
 *
 * <p>Reconstruction either succeeds unambiguously or does not happen: a wrong
 * baseline would silently hide or invent findings. It gives up (returns
 * empty, so the caller falls through to git) when a fragment:
 * <ul>
 *   <li>was a replace-all: occurrences of the new text that existed before
 *       the edit cannot be told apart from the ones it created;</li>
 *   <li>has empty new text (a pure deletion): there is nothing to locate;</li>
 *   <li>has new text occurring zero or several times in the text at that step.</li>
 * </ul>
 * Fragments are matched as given and, if the file uses CRLF, also with their
 * {@code \n} converted to {@code \r\n} (agents commonly report LF-normalized
 * strings for CRLF files).
 */
public final class BaselineReconstructor {

    private BaselineReconstructor() {
    }

    public static Optional<String> reconstruct(String current, List<EditFragment> fragments) {
        if (fragments.isEmpty()) {
            return Optional.empty();
        }
        String text = current;
        for (int i = fragments.size() - 1; i >= 0; i--) {
            Optional<String> reversed = reverse(text, fragments.get(i));
            if (reversed.isEmpty()) {
                return Optional.empty();
            }
            text = reversed.get();
        }
        return Optional.of(text);
    }

    private static Optional<String> reverse(String text, EditFragment f) {
        if (f.replaceAll() || f.newText().isEmpty()) {
            return Optional.empty();
        }
        Optional<String> direct = replaceUnique(text, f.newText(), f.oldText());
        if (direct.isPresent() || !text.contains("\r\n") || !f.newText().contains("\n")) {
            return direct;
        }
        return replaceUnique(text, toCrlf(f.newText()), toCrlf(f.oldText()));
    }

    private static Optional<String> replaceUnique(String text, String find, String replacement) {
        int first = text.indexOf(find);
        if (first < 0 || text.indexOf(find, first + 1) >= 0) {
            return Optional.empty();
        }
        return Optional.of(text.substring(0, first) + replacement + text.substring(first + find.length()));
    }

    private static String toCrlf(String s) {
        return s.replace("\r\n", "\n").replace("\n", "\r\n");
    }
}
