package com.devmanchego.javaguard.change;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds suppression markers in Java source (specification &sect;11.3).
 *
 * <p>Line markers are found by a line-by-line regex and never depend on the
 * source parsing. Element markers need the declaration's extent, so they need
 * JavaParser; the parse runs only when the source mentions
 * {@code javaguard:} inside an annotation, and a parse failure only loses
 * element markers (the file is probably mid-edit), never line markers.
 */
public final class SuppressionScanner {

    private static final Pattern LINE_MARKER = Pattern.compile(
            "//\\s*javaguard:ignore\\s+([A-Za-z0-9:_.-]+)(?:\\s+--\\s*(.*))?");
    private static final String ELEMENT_PREFIX = "javaguard:";

    private SuppressionScanner() {
    }

    public static List<SuppressionMarker> scan(String source) {
        List<SuppressionMarker> markers = new ArrayList<>(lineMarkers(source));
        if (source.contains("\"" + ELEMENT_PREFIX)) {
            markers.addAll(elementMarkers(source));
        }
        markers.sort(Comparator.comparingInt(SuppressionMarker::markerLine));
        return markers;
    }

    private static List<SuppressionMarker> lineMarkers(String source) {
        List<SuppressionMarker> markers = new ArrayList<>();
        List<String> lines = source.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            Matcher m = LINE_MARKER.matcher(lines.get(i));
            if (m.find()) {
                int line = i + 1;
                markers.add(new SuppressionMarker(m.group(1), SuppressionMarker.Kind.LINE, line, line + 1, line,
                        reason(m.group(2))));
            }
        }
        return markers;
    }

    private static List<SuppressionMarker> elementMarkers(String source) {
        ParseResult<CompilationUnit> parsed = new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
                .setAttributeComments(false))
                .parse(source);
        if (!parsed.isSuccessful() || parsed.getResult().isEmpty()) {
            return List.of();
        }
        List<String> lines = source.lines().toList();
        List<SuppressionMarker> markers = new ArrayList<>();
        for (AnnotationExpr annotation : parsed.getResult().get().findAll(AnnotationExpr.class)) {
            if (!"SuppressWarnings".equals(annotation.getNameAsString())
                    && !"java.lang.SuppressWarnings".equals(annotation.getNameAsString())) {
                continue;
            }
            Optional<Node> declaration = annotation.getParentNode();
            if (declaration.isEmpty() || annotation.getBegin().isEmpty() || declaration.get().getEnd().isEmpty()
                    || declaration.get().getBegin().isEmpty()) {
                continue;
            }
            int annotationLine = annotation.getBegin().get().line;
            int from = declaration.get().getBegin().get().line;
            int to = declaration.get().getEnd().get().line;
            Optional<String> reason = trailingComment(lines, annotationLine);
            for (String value : values(annotation)) {
                if (value.startsWith(ELEMENT_PREFIX) && value.length() > ELEMENT_PREFIX.length()) {
                    markers.add(new SuppressionMarker(value.substring(ELEMENT_PREFIX.length()).strip(),
                            SuppressionMarker.Kind.ELEMENT, from, to, annotationLine, reason));
                }
            }
        }
        return markers;
    }

    private static List<String> values(AnnotationExpr annotation) {
        Expression value;
        if (annotation instanceof SingleMemberAnnotationExpr single) {
            value = single.getMemberValue();
        } else if (annotation instanceof NormalAnnotationExpr normal) {
            value = normal.getPairs().stream().filter(p -> p.getNameAsString().equals("value"))
                    .map(p -> p.getValue()).findFirst().orElse(null);
        } else {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        if (value instanceof StringLiteralExpr s) {
            out.add(s.asString());
        } else if (value instanceof ArrayInitializerExpr array) {
            array.getValues().forEach(v -> {
                if (v instanceof StringLiteralExpr s) {
                    out.add(s.asString());
                }
            });
        }
        return out;
    }

    /** {@code // reason} after the annotation on its own line. */
    private static Optional<String> trailingComment(List<String> lines, int line) {
        if (line < 1 || line > lines.size()) {
            return Optional.empty();
        }
        String text = lines.get(line - 1);
        int comment = text.lastIndexOf("//");
        return comment < 0 ? Optional.empty() : reason(text.substring(comment + 2));
    }

    private static Optional<String> reason(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String stripped = raw.strip();
        return stripped.isEmpty() ? Optional.empty() : Optional.of(stripped);
    }
}
