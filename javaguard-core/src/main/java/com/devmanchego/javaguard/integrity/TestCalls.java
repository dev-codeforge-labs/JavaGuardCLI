package com.devmanchego.javaguard.integrity;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Name-based recognition of the calls that make a test a test: assertions, assumptions and
 * expected-exception constructs. There is no symbol resolution, so a call is recognized by its simple name
 * (and, for the ambiguous {@code verify}/{@code then}, by its scope). A project helper called
 * {@code assertSomething(...)} counts as an assertion, which is what a reader would say too.
 */
public final class TestCalls {

    private static final Set<String> MOCKITO_SCOPES = Set.of("Mockito", "BDDMockito", "EasyMock", "MockitoAnnotations");
    private static final Pattern ASSERT_THAT_EXCEPTION = Pattern.compile("assertThat\\w*Exception");

    private TestCalls() {
    }

    /** {@code assert*}, {@code fail}, Mockito/EasyMock {@code verify*}, {@code expect*} and BDD {@code then(...)}. */
    public static boolean isAssertion(MethodCallExpr call) {
        String name = call.getNameAsString();
        if (name.startsWith("assert") || name.equals("fail") || name.startsWith("expect")) {
            return true;
        }
        if (name.startsWith("verify")) {
            return call.getScope().isEmpty() || scopeName(call).map(MOCKITO_SCOPES::contains).orElse(false);
        }
        if (name.equals("then")) {
            return call.getScope().isEmpty() || scopeName(call).map(s -> s.equals("BDDMockito") || s.equals("BDDAssertions")).orElse(false);
        }
        return false;
    }

    /** {@code Assumptions.assume*}, {@code Assume.assume*}, {@code assumingThat}. */
    public static boolean isAssumption(MethodCallExpr call) {
        String name = call.getNameAsString();
        return name.startsWith("assume") || name.equals("assumingThat");
    }

    /** Constructs that state "this call must throw": {@code assertThrows*}, AssertJ's, {@code expectThrows}, {@code catchThrowable}. */
    public static boolean isExceptionExpectation(MethodCallExpr call) {
        String name = call.getNameAsString();
        return name.equals("assertThrows") || name.equals("assertThrowsExactly") || name.equals("expectThrows")
                || name.equals("assertThatThrownBy") || name.equals("assertThatExceptionOfType")
                || name.equals("catchThrowable") || name.equals("catchThrowableOfType")
                || ASSERT_THAT_EXCEPTION.matcher(name).matches();
    }

    public static boolean isFail(MethodCallExpr call) {
        return call.getNameAsString().equals("fail");
    }

    /**
     * Assertions in a test method, counting one level of same-file helpers it calls: moving assertions into
     * {@code assertValid(x)} must not look like deleting them.
     *
     * @param helpers methods declared in the same file, by simple name
     */
    public static int assertionCount(MethodDeclaration method, Map<String, List<MethodDeclaration>> helpers) {
        return count(method, helpers, TestCalls::isAssertion, 0);
    }

    public static int assumptionCount(MethodDeclaration method) {
        return count(method, Map.of(), TestCalls::isAssumption, 0);
    }

    public static int exceptionExpectationCount(MethodDeclaration method) {
        return count(method, Map.of(), TestCalls::isExceptionExpectation, 0);
    }

    private static int count(MethodDeclaration method, Map<String, List<MethodDeclaration>> helpers,
                             java.util.function.Predicate<MethodCallExpr> counted, int depth) {
        int total = 0;
        for (MethodCallExpr call : method.findAll(MethodCallExpr.class)) {
            if (counted.test(call)) {
                total++;
            } else if (depth < 2 && call.getScope().isEmpty() && helpers.containsKey(call.getNameAsString())) {
                for (MethodDeclaration helper : helpers.get(call.getNameAsString())) {
                    if (helper != method) {
                        total += count(helper, helpers, counted, depth + 1);
                    }
                }
            }
        }
        return total;
    }

    /** Simple name of a call's scope when it is a bare name ({@code Mockito.verify} gives {@code Mockito}). */
    public static Optional<String> scopeName(MethodCallExpr call) {
        return call.getScope().filter(Expression::isNameExpr).map(e -> ((NameExpr) e).getNameAsString());
    }
}
