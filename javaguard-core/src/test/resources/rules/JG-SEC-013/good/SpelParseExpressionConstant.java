import org.springframework.expression.ExpressionParser;

class SpelParseExpressionConstant {
    Object evaluate(ExpressionParser parser) {
        return parser.parseExpression("#root.name").getValue();
    }
}
