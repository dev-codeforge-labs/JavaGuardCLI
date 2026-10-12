package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JG-TST-014 (catalog &sect;1.7): the class a test is named after ({@code XTest}, {@code XTests}) is itself
 * mocked - {@code @Mock X}, {@code @MockBean X} or {@code mock(X.class)}. Such a test exercises the mock,
 * not the code. {@code @InjectMocks X} is the legitimate shape and is not flagged. Test sources only.
 */
final class Tst014ClassUnderTestMocked extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-TST-014", "Class under test mocked", Category.TEST_INTEGRITY, Severity.MAJOR);

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public boolean mainOnly() {
        return false; // Catalog scope: test.
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        if (!ctx.testScope()) {
            return out;
        }
        for (ClassOrInterfaceDeclaration testClass : ctx.unit().findAll(ClassOrInterfaceDeclaration.class)) {
            Optional<String> underTest = classUnderTest(testClass.getNameAsString());
            if (underTest.isEmpty()) {
                continue;
            }
            String x = underTest.get();
            for (FieldDeclaration field : testClass.getFields()) {
                if (AstSupport.hasAnnotation(field, "Mock", "MockBean", "SpyBean") && field.getVariables().stream()
                        .anyMatch(v -> AstSupport.simpleName(v.getTypeAsString()).equals(x))) {
                    out.add(finding(ctx, field, message(testClass, x), remediation(x)));
                }
            }
            for (Parameter parameter : testClass.findAll(Parameter.class)) {
                if (AstSupport.hasAnnotation(parameter, "Mock") && AstSupport.simpleName(parameter.getTypeAsString()).equals(x)) {
                    out.add(finding(ctx, parameter, message(testClass, x), remediation(x)));
                }
            }
            for (MethodCallExpr call : testClass.findAll(MethodCallExpr.class)) {
                if (call.getNameAsString().equals("mock") && !call.getArguments().isEmpty()
                        && call.getArgument(0) instanceof ClassExpr classExpr
                        && AstSupport.simpleName(classExpr.getTypeAsString()).equals(x)) {
                    out.add(finding(ctx, call, message(testClass, x), remediation(x)));
                }
            }
        }
        return out;
    }

    private static Optional<String> classUnderTest(String testClassName) {
        for (String suffix : List.of("Tests", "Test")) {
            if (testClassName.endsWith(suffix) && testClassName.length() > suffix.length()) {
                return Optional.of(testClassName.substring(0, testClassName.length() - suffix.length()));
            }
        }
        return Optional.empty();
    }

    private static String message(ClassOrInterfaceDeclaration testClass, String x) {
        return testClass.getNameAsString() + " mocks " + x + ", the class it is named after, so it tests the mock.";
    }

    private static String remediation(String x) {
        return "Use a real " + x + " (e.g. @InjectMocks) and mock only its collaborators.";
    }
}
