package com.devmanchego.javaguard.analysis;

import com.devmanchego.javaguard.model.Category;
import com.devmanchego.javaguard.model.Finding;
import com.devmanchego.javaguard.model.Severity;
import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * JG-AI-004 (catalog &sect;1.8): three or more consecutive {@code //} line
 * comments that, stripped of their leading slashes, parse as a block of valid
 * Java statements are dead code someone commented out instead of deleting -
 * clutter that erodes trust in what is actually live.
 */
final class Ai004CommentedOutCodeAdded extends AbstractAstCheck {

    static final RuleInfo INFO = RuleInfo.ast("JG-AI-004", "Commented-out code added", Category.AI_DEBRIS, Severity.INFO);

    private static final int MIN_CONSECUTIVE_LINES = 3;

    @Override
    public RuleInfo info() {
        return INFO;
    }

    @Override
    public boolean mainOnly() {
        return false; // Catalog scope: all.
    }

    @Override
    public List<Finding> check(AstCheckContext ctx) {
        List<Finding> out = new ArrayList<>();
        List<LineComment> lineComments = ctx.comments().stream()
                .filter(Comment::isLineComment)
                .map(Comment::asLineComment)
                .filter(c -> c.getBegin().isPresent())
                .sorted(Comparator.comparingInt(c -> c.getBegin().get().line))
                .toList();

        int start = 0;
        while (start < lineComments.size()) {
            int end = start;
            while (end + 1 < lineComments.size()
                    && lineComments.get(end + 1).getBegin().get().line == lineComments.get(end).getBegin().get().line + 1) {
                end++;
            }
            List<LineComment> group = lineComments.subList(start, end + 1);
            if (group.size() >= MIN_CONSECUTIVE_LINES && looksLikeCode(group)) {
                out.add(finding(ctx, group.get(0), group.size() + " consecutive commented-out lines parse as Java statements.",
                        "Delete dead code instead of commenting it out; version control already keeps the history. If the "
                                + "lines are an explanation, rewrite them as prose."));
            }
            start = end + 1;
        }
        return out;
    }

    private boolean looksLikeCode(List<LineComment> group) {
        StringBuilder body = new StringBuilder();
        for (LineComment c : group) {
            body.append(c.getContent()).append('\n');
        }
        if (body.toString().isBlank()) {
            return false;
        }
        try {
            StaticJavaParser.parseBlock("{" + body + "}");
            return true;
        } catch (ParseProblemException e) {
            return false;
        }
    }
}
