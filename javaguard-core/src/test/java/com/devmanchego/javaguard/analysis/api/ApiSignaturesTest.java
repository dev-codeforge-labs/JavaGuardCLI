package com.devmanchego.javaguard.analysis.api;

import com.devmanchego.javaguard.analysis.api.ApiSignatures.Difference;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 11: public/protected signatures and their comparison (specification &sect;11.5). */
class ApiSignaturesTest {

    private static final String BASE = """
            package com.acme.api;

            import java.util.List;

            public class Calculator {
                public static final int LIMIT = 10;
                protected String label;
                private int hidden;
                int packagePrivate;

                public Calculator() {
                }

                protected Calculator(int seed) {
                }

                public int add(int a, int b) {
                    return a + b;
                }

                public List<String> names(List<String> in) {
                    return in;
                }

                protected void hook() {
                }

                private void internal() {
                }

                void packagePrivateMethod() {
                }

                public static class Nested {
                    public void run() {
                    }
                }

                private static class Secret {
                    public void run() {
                    }
                }
            }
            """;

    private static List<Difference> diff(String before, String after) {
        return ApiSignatures.compare(ApiSignatures.extract(before), ApiSignatures.extract(after));
    }

    private static List<String> displays(List<Difference> differences) {
        return differences.stream().map(d -> d.kind() + " " + d.before().display()).toList();
    }

    @Test
    void onlyPublicAndProtectedMembersOfExposedTypesAreApi() {
        ApiSignatures.Api api = ApiSignatures.extract(BASE);

        assertThat(api.packageName()).isEqualTo("com.acme.api");
        assertThat(api.signatures().keySet()).containsExactlyInAnyOrder(
                "type:Calculator", "field:Calculator#LIMIT", "field:Calculator#label", "ctor:Calculator#()", "ctor:Calculator#(int)",
                "method:Calculator#add(int,int)", "method:Calculator#names(List)", "method:Calculator#hook()",
                "type:Calculator.Nested", "method:Calculator.Nested#run()");
    }

    @Test
    void anUnchangedFileHasNoDifferences() {
        assertThat(diff(BASE, BASE)).isEmpty();
    }

    @Test
    void aRemovedPublicMethodIsReported() {
        String after = BASE.replace("    public int add(int a, int b) {\n        return a + b;\n    }\n", "");

        assertThat(displays(diff(BASE, after))).containsExactly("REMOVED add(int,int) in Calculator");
    }

    @Test
    void aChangedReturnTypeIsAChangeNotARemoval() {
        String after = BASE.replace("public int add(int a, int b)", "public long add(int a, int b)");

        List<Difference> differences = diff(BASE, after);

        assertThat(differences).singleElement().satisfies(d -> {
            assertThat(d.kind()).isEqualTo(Difference.Kind.CHANGED);
            assertThat(d.before().full()).contains("int add(int,int)");
            assertThat(d.after().orElseThrow().full()).contains("long add(int,int)");
        });
    }

    @Test
    void aChangedParameterTypeIsARemovalOfTheOldOverload() {
        String after = BASE.replace("public int add(int a, int b)", "public int add(long a, long b)");

        assertThat(displays(diff(BASE, after))).containsExactly("REMOVED add(int,int) in Calculator");
    }

    @Test
    void reducingVisibilityOrAddingFinalOrStaticIsAChange() {
        assertThat(displays(diff(BASE, BASE.replace("public int add", "int add")))).containsExactly("REMOVED add(int,int) in Calculator");
        assertThat(displays(diff(BASE, BASE.replace("public int add", "public final int add")))).containsExactly("CHANGED add(int,int) in Calculator");
        assertThat(displays(diff(BASE, BASE.replace("public int add", "public static int add")))).containsExactly("CHANGED add(int,int) in Calculator");
        assertThat(displays(diff(BASE, BASE.replace("protected void hook", "public void hook")))).containsExactly("CHANGED hook() in Calculator");
    }

    @Test
    void addingACheckedExceptionToTheSignatureIsAChange() {
        String after = BASE.replace("public int add(int a, int b) {", "public int add(int a, int b) throws java.io.IOException {");

        assertThat(displays(diff(BASE, after))).containsExactly("CHANGED add(int,int) in Calculator");
    }

    @Test
    void genericsDoNotChangeAnOverloadsIdentityButDoChangeItsSignature() {
        String after = BASE.replace("public List<String> names(List<String> in)", "public List<Integer> names(List<Integer> in)");

        assertThat(displays(diff(BASE, after))).containsExactly("CHANGED names(List) in Calculator");
    }

    @Test
    void newApiAndChangesToHiddenMembersAreNotReported() {
        String after = BASE.replace("    private void internal() {\n    }\n", "    private void renamed() {\n    }\n")
                .replace("    void packagePrivateMethod() {\n    }\n", "")
                .replace("    private int hidden;\n", "")
                + "";
        after = after.replace("    public static class Nested {", "    public void brandNew() {\n    }\n\n    public static class Nested {");

        assertThat(diff(BASE, after)).isEmpty();
    }

    @Test
    void aRemovedTypeTakesItsMembersWithIt() {
        String after = BASE.replace("    public static class Nested {\n        public void run() {\n        }\n    }\n", "");

        assertThat(displays(diff(BASE, after))).containsExactly("REMOVED Calculator.Nested",
                "REMOVED run() in Calculator.Nested");
    }

    @Test
    void aDeletedFileRemovesEverything() {
        List<Difference> differences = ApiSignatures.compare(ApiSignatures.extract(BASE), ApiSignatures.extract(""));

        assertThat(differences).hasSize(10).allMatch(d -> d.kind() == Difference.Kind.REMOVED);
    }

    @Test
    void interfacesEnumsAndRecordsExposeTheirMembers() {
        String before = """
                package p;
                public interface Shape {
                    double area();
                    default String name() { return "s"; }
                    private void helper() {}
                }
                """;
        assertThat(ApiSignatures.extract(before).signatures().keySet())
                .containsExactlyInAnyOrder("type:Shape", "method:Shape#area()", "method:Shape#name()");

        String enumBefore = "package p;\npublic enum Color { RED, GREEN }\n";
        String enumAfter = "package p;\npublic enum Color { RED }\n";
        assertThat(displays(diff(enumBefore, enumAfter))).containsExactly("REMOVED GREEN in Color");

        String recordBefore = "package p;\npublic record Point(int x, int y) {}\n";
        String recordAfter = "package p;\npublic record Point(int x) {}\n";
        assertThat(displays(diff(recordBefore, recordAfter))).containsExactly("REMOVED y in Point");
    }

    @Test
    void changingTheSupertypesOfAClassIsAChange() {
        String before = "package p;\npublic class A extends B implements C {}\n";
        String after = "package p;\npublic class A extends D implements C {}\n";

        assertThat(displays(diff(before, after))).containsExactly("CHANGED A");
    }

    @Test
    void sourceThatDoesNotParseHasNoApi() {
        assertThat(ApiSignatures.extract("public class {").signatures()).isEmpty();
        assertThat(ApiSignatures.extract("   ").signatures()).isEmpty();
    }
}
