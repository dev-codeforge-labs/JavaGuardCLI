import org.springframework.expression.ExpressionParser;

class SpelParseExpressionConcatenation {
    Object evaluate(ExpressionParser parser, String field) {
        return parser.parseExpression("#root." + field).getValue();
    }
}
