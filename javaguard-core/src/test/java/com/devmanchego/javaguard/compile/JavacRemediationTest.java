package com.devmanchego.javaguard.compile;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavacRemediationTest {

    @Test
    void aByteOrderMarkErrorGetsTheSpecificAdviceInAnyLocale() {
        // javac prints the character as the text ﻿; only the surrounding words depend on the language.
        for (String message : new String[] {"illegal character: '\\ufeff'", "carácter no válido: '\\ufeff'",
                "illegal character: '\\uFEFF'"}) {
            assertThat(JavacRemediation.forMessage(message, JavacRemediation.BUILD_DEFAULT))
                    .as(message).contains("byte order mark").contains("without BOM");
        }
        assertThat(JavacRemediation.forMessage("illegal start of expression", "x")).isEqualTo("x");
        assertThat(JavacRemediation.forMessage(null, "x")).isEqualTo("x");
    }

    @Test
    void theAdviceIsAsciiBecauseAgentFacingOutputIs() {
        assertThat(JavacRemediation.BYTE_ORDER_MARK).matches("\\A\\p{ASCII}*\\z");
    }
}
