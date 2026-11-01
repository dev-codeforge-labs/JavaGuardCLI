package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JG-SEC-004 (catalog &sect;1.2): an XML parser factory created without any
 * call in the same method that looks like hardening it (a
 * {@code setFeature}/{@code setAttribute}/{@code setProperty}/
 * {@code setExpandEntityReferences}/{@code setXIncludeAware} call, regardless
 * of its exact arguments). Checking the exact anti-XXE feature name would add
 * little precision for a lot of extra complexity; a project that calls one of
 * these at all on the factory is treated as having made a deliberate choice.
 */
final class Sec004XxeVulnerable extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-SEC-004", "XML parser open to XXE", Category.SECURITY, Severity.CRITICAL);

    private static final Set<String> FACTORY_TYPES = Set.of(
            "DocumentBuilderFactory", "SAXParserFactory", "XMLInputFactory", "TransformerFactory", "SchemaFactory");
    private static final Set<String> READER_METHODS = Set.of("createXMLReader", "getXMLReader");
    private static final Set<String> MITIGATIONS = Set.of(
            "setFeature", "setAttribute", "setProperty", "setExpandEntityReferences", "setXIncludeAware");

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        for (MethodCallExpr call : ctx.unit().findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            boolean isFactoryCreation = name.equals("newInstance") && call.getScope()
                    .filter(Expression::isNameExpr)
                    .map(s -> s.asNameExpr().getNameAsString())
                    .filter(FACTORY_TYPES::contains)
                    .isPresent();
            boolean isReaderCreation = READER_METHODS.contains(name);
            if ((isFactoryCreation || isReaderCreation) && !hasMitigationInMethod(call)) {
                out.add(finding(ctx, call, "An XML parser is created without disabling DTDs or external entities.",
                        "Disable DTDs before parsing: factory.setFeature(\"http://apache.org/xml/features/disallow-doctype-decl\", "
                                + "true) on DocumentBuilderFactory/SAXParserFactory; setProperty(XMLInputFactory.SUPPORT_DTD, "
                                + "false) on XMLInputFactory; setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, \"\") and "
                                + "ACCESS_EXTERNAL_STYLESHEET/SCHEMA on TransformerFactory/SchemaFactory."));
            }
        }
        return out;
    }

    private boolean hasMitigationInMethod(MethodCallExpr call) {
        return AstSupport.enclosingMethod(call).stream()
                .flatMap(m -> m.findAll(MethodCallExpr.class).stream())
                .anyMatch(c -> MITIGATIONS.contains(c.getNameAsString()));
    }
}
