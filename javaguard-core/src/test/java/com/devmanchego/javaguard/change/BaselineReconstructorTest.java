package com.devmanchego.javaguard.change;

import com.devmanchego.javaguard.adapter.codex.ApplyPatchParser;
import com.devmanchego.javaguard.model.EditFragment;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Specification section 9.1 step 2: exact pre-edit content, or nothing. */
class BaselineReconstructorTest {

    private static final String ORIGINAL = """
            package com.acme;

            class UserDao {
                List<User> find(String name) {
                    String sql = "SELECT * FROM users WHERE name = '" + name + "'";
                    return jdbc.query(sql, mapper);
                }
            }
            """;

    /** The agent tool's own forward semantics, used to build realistic round trips. */
    private static String applyForward(String text, List<EditFragment> fragments) {
        String result = text;
        for (EditFragment f : fragments) {
            assertThat(result).as("forward edit precondition").contains(f.oldText());
            result = f.replaceAll() ? result.replace(f.oldText(), f.newText())
                    : result.replaceFirst(java.util.regex.Pattern.quote(f.oldText()),
                    java.util.regex.Matcher.quoteReplacement(f.newText()));
        }
        return result;
    }

    @Test
    void singleEditRoundTrips() {
        List<EditFragment> edit = List.of(new EditFragment("return jdbc.query(sql, mapper);",
                "return jdbc.query(sql, rowMapper);", false));

        String current = applyForward(ORIGINAL, edit);

        assertThat(BaselineReconstructor.reconstruct(current, edit)).contains(ORIGINAL);
    }

    @Test
    void multiEditWhereTheSecondEditBuildsOnTheFirstIsReversedLastFirst() {
        List<EditFragment> edits = List.of(
                new EditFragment("String sql = ", "final String sql = ", false),
                new EditFragment("final String sql", "final String query", false));

        String current = applyForward(ORIGINAL, edits);

        assertThat(BaselineReconstructor.reconstruct(current, edits)).contains(ORIGINAL);
    }

    @Test
    void pureInsertionIsReversible() {
        List<EditFragment> edit = List.of(new EditFragment("class UserDao {\n",
                "class UserDao {\n    private static final int LIMIT = 10;\n", false));

        assertThat(BaselineReconstructor.reconstruct(applyForward(ORIGINAL, edit), edit)).contains(ORIGINAL);
    }

    @Test
    void codexHunksRoundTrip() {
        String patch = """
                *** Begin Patch
                *** Update File: UserDao.java
                @@
                     List<User> find(String name) {
                -        String sql = "SELECT * FROM users WHERE name = '" + name + "'";
                -        return jdbc.query(sql, mapper);
                +        return jdbc.query("SELECT * FROM users WHERE name = ?", mapper, name);
                     }
                *** End Patch
                """;
        List<EditFragment> hunks = ApplyPatchParser.parse(patch).get(0).fragments();

        String current = applyForward(ORIGINAL, hunks);

        assertThat(current).contains("WHERE name = ?");
        assertThat(BaselineReconstructor.reconstruct(current, hunks)).contains(ORIGINAL);
    }

    @Test
    void newTextNotFoundFallsThrough() {
        List<EditFragment> edit = List.of(new EditFragment("a", "text that is not there", false));

        assertThat(BaselineReconstructor.reconstruct(ORIGINAL, edit)).isEmpty();
    }

    @Test
    void newTextFoundTwiceIsAmbiguous() {
        String current = "x = 1;\nx = 1;\n";
        List<EditFragment> edit = List.of(new EditFragment("x = 0;", "x = 1;", false));

        assertThat(BaselineReconstructor.reconstruct(current, edit)).isEmpty();
    }

    @Test
    void replaceAllIsAmbiguousBecausePreExistingOccurrencesCannotBeTold() {
        String current = "total total total";
        List<EditFragment> edit = List.of(new EditFragment("legacyTotal", "total", true));

        assertThat(BaselineReconstructor.reconstruct(current, edit)).isEmpty();
    }

    @Test
    void emptyNewTextIsAmbiguousBecauseThereIsNothingToLocate() {
        List<EditFragment> edit = List.of(new EditFragment("private int unused;\n", "", false));

        assertThat(BaselineReconstructor.reconstruct(ORIGINAL, edit)).isEmpty();
    }

    @Test
    void lfFragmentsMatchACrlfFileAndTheResultKeepsCrlf() {
        String originalCrlf = ORIGINAL.replace("\n", "\r\n");
        List<EditFragment> lfEdit = List.of(new EditFragment(
                "    List<User> find(String name) {\n        String sql",
                "    List<User> findAll(String name) {\n        String sql", false));
        String currentCrlf = originalCrlf.replace("find(String name)", "findAll(String name)");

        assertThat(BaselineReconstructor.reconstruct(currentCrlf, lfEdit)).contains(originalCrlf);
    }

    @Test
    void noFragmentsMeansNothingToReconstructFrom() {
        assertThat(BaselineReconstructor.reconstruct(ORIGINAL, List.of())).isEmpty();
    }
}
