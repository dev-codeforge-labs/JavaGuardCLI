package com.devmanchego.javaguard.report;

import java.text.Normalizer;

/**
 * Folds text to plain ASCII (specification &sect;12.1, {@code report.ascii},
 * default true) to avoid code-page corruption on Windows consoles.
 *
 * <p>Latin letters with diacritics decompose and lose their accent
 * ({@code "café"} -&gt; {@code "cafe"}); anything else outside ASCII
 * (CJK, emoji, symbols) becomes {@code '?'}. This is lossy by design: it
 * trades fidelity for a guarantee that the bytes render correctly everywhere.
 */
public final class AsciiFolder {

    private AsciiFolder() {
    }

    public static String fold(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD);
        StringBuilder out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) == Character.NON_SPACING_MARK) {
                continue; // combining accent already applied to the base letter above; drop it
            }
            out.append(c < 128 ? c : '?');
        }
        return out.toString();
    }
}
