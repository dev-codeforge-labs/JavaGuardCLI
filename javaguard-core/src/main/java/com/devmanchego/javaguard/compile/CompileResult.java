package com.devmanchego.javaguard.compile;

import com.devmanchego.javaguard.model.Finding;

import java.util.List;

/**
 * Outcome of one {@link InProcessCompiler#compile(CompileRequest)} call.
 *
 * @param success   true when javac reported no ERROR-kind diagnostic (an
 *                  Error Prone finding promoted to ERROR severity also
 *                  makes this false, since javac itself treats it as one)
 * @param findings  {@code JAVAC} and {@code EP:*} findings, in diagnostic order
 */
public record CompileResult(boolean success, List<Finding> findings) {

    public CompileResult {
        findings = List.copyOf(findings);
    }
}
