package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JG-CFG-003 (catalog &sect;1.13): a string literal in main code that is a URL to {@code localhost}, a loopback or a
 * private-network address, or a JDBC URL naming a host. Works on the developer's machine, points at the wrong place (or
 * nowhere) in every other environment. Not reported: literals inside annotations ({@code @Value("${url:http://localhost}")}
 * defaults are configuration), literals of {@code @ConfigurationProperties} and {@code @Configuration} classes (their
 * defaults), embedded databases without a
 * host ({@code jdbc:h2:mem:}), test code. Like every file-local rule, only literals the session added are reported.
 */
final class Cfg003HardCodedHost extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-CFG-003", "Hard-coded host", Category.CONFIGURATION, Severity.MAJOR);

    private static final Pattern LOCAL_URL = Pattern.compile(
            "(?i)^[a-z][a-z0-9+.-]*://(?:[^/@\\s]*@)?(localhost|127(?:\\.\\d{1,3}){3}|0\\.0\\.0\\.0|\\[::1]|10(?:\\.\\d{1,3}){3}"
                    + "|192\\.168(?:\\.\\d{1,3}){2}|172\\.(?:1[6-9]|2\\d|3[01])(?:\\.\\d{1,3}){2})(?:[:/?#].*)?$");
    private static final Pattern JDBC_WITH_HOST = Pattern.compile(
            "(?i)^jdbc:[a-z0-9]+(?::[a-z0-9]+)*:(?://|@//|@|thin:@)([A-Za-z0-9.-]+)(?::\\d+)?[/:;?].*$");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        if (ctx.testScope()) {
            return List.of();
        }
        List<Finding> out = new ArrayList<>();
        for (StringLiteralExpr literal : ctx.unit().findAll(StringLiteralExpr.class)) {
            if (literal.findAncestor(AnnotationExpr.class).isPresent() || inConfigurationProperties(literal)) {
                continue;
            }
            String value = literal.asString().strip();
            Optional<String> host = host(value);
            host.ifPresent(h -> out.add(finding(ctx, literal, "The address " + h + " is hard-coded: it only exists on the machine "
                            + "or network this was written on.",
                    "Read the URL from configuration (@Value(\"${...}\") or a @ConfigurationProperties class) and define it per "
                            + "environment in application.yml or the environment.")));
        }
        return out;
    }

    static Optional<String> host(String value) {
        Matcher local = LOCAL_URL.matcher(value);
        if (local.matches()) {
            return Optional.of(local.group(1));
        }
        Matcher jdbc = JDBC_WITH_HOST.matcher(value);
        if (jdbc.matches()) {
            return Optional.of(jdbc.group(1) + " (JDBC URL)");
        }
        return Optional.empty();
    }

    private static boolean inConfigurationProperties(StringLiteralExpr literal) {
        return literal.findAncestor(ClassOrInterfaceDeclaration.class)
                .filter(c -> AstSupport.hasAnnotation(c, "ConfigurationProperties", "Configuration", "AutoConfiguration")).isPresent();
    }
}
