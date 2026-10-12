package com.devmanchego.javaguard.compile;

import java.util.Locale;

/**
 * What to tell the agent about a javac error, shared by the in-process compiler and the Maven and Gradle output parsers.
 * Most errors get the generic advice; the few whose cause is not in the code the agent is looking at get a specific one.
 */
public final class JavacRemediation {

    /** The default advice for a build compilation error. */
    public static final String BUILD_DEFAULT =
            "Fix the compilation error; the cause is usually a missing or misspelled symbol, or a type mismatch.";
    /** The default advice for an in-process compilation error. */
    public static final String FAST_DEFAULT = "Fix the compile error before anything else can be analyzed.";

    static final String BYTE_ORDER_MARK = "The file starts with a UTF-8 byte order mark (BOM), which javac rejects, so the "
            + "source itself is probably fine. Save it again as UTF-8 without BOM. In Windows PowerShell, Out-File, "
            + "Set-Content -Encoding UTF8 and '>' add the BOM: write with [System.IO.File]::WriteAllText(path, text, "
            + "(New-Object System.Text.UTF8Encoding $false)) or use the apply_patch tool.";

    private JavacRemediation() {
    }

    /**
     * {@code fallback} unless the message says javac met a byte order mark. javac prints the character as the text
     * {@code ﻿} (in whatever language it reports in), so the test does not depend on the locale.
     */
    public static String forMessage(String message, String fallback) {
        if (message == null) {
            return fallback;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("\\ufeff") || message.indexOf('﻿') >= 0 ? BYTE_ORDER_MARK : fallback;
    }
}
