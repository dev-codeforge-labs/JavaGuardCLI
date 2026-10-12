package com.devmanchego.javaguard.analysis.syntax;

import com.devmanchego.javaguard.analysis.RuleInfo;
import com.devmanchego.javaguard.change.Fingerprints;
import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/**
 * JG-SYN-001 (catalog &sect;1.1): the file did not parse at all. Produced
 * directly by {@link com.devmanchego.javaguard.analysis.AstCheckEngine}, not
 * a normal {@link com.devmanchego.javaguard.analysis.AstCheck} (there is no
 * {@code CompilationUnit} to run one against).
 */
public final class SynCheck {

    public static final String RULE_ID = "JG-SYN-001";

    /** Metadata for the {@code javaguard rules} listing; this rule itself is never registered in a check list. */
    public static final RuleInfo INFO = RuleInfo.ast(RULE_ID, "Source does not parse", Category.SYNTAX, Severity.BLOCKER);

    private SynCheck() {
    }

    public static Finding parseFailure(String relativePath, String problemMessage, int line, int column) {
        return new Finding(RULE_ID, "Source does not parse", Category.SYNTAX, Severity.BLOCKER, "ast",
                Optional.of(Path.of(relativePath)), line, column, line, problemMessage,
                "Fix the syntax error before anything else can be analyzed or compiled.", Optional.empty(), Map.of(),
                Fingerprints.of(RULE_ID, relativePath, problemMessage));
    }
}
