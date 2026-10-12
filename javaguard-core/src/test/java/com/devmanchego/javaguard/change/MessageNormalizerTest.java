package com.devmanchego.javaguard.change;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The normalization table IS the specification of fingerprint identity
 * (specification section 13.1). Written before {@link MessageNormalizer}.
 *
 * <p>Changing an expected value here changes every stored fingerprint: loop
 * history (Phase 12) and baseline caches become incomparable. Treat an edit to
 * this table as a breaking change and bump {@link Fingerprints#VERSION}.
 */
class MessageNormalizerTest {

    static Stream<Arguments> table() {
        return Stream.of(
                // --- line / column numbers ------------------------------------------------
                Arguments.of("cannot find symbol at Foo.java:42", "cannot find symbol at Foo.java:<n>"),
                Arguments.of("Foo.java:42:15: warning", "Foo.java:<n>: warning"),
                Arguments.of("UserDao.java:[42,15] cannot find symbol", "UserDao.java:[<n>,<n>] cannot find symbol"),
                Arguments.of("error at line 12, column 7", "error at line <n>, column <n>"),
                Arguments.of("Line 3 col 9", "line <n> col <n>"),
                Arguments.of("at com.acme.Foo.bar(Foo.java:88)", "at com.acme.Foo.bar(Foo.java:<n>)"),

                // --- absolute paths (Windows, POSIX, file URIs); relative names kept ----------
                Arguments.of("C:\\work\\proj\\src\\Foo.java:42: error: x", "<path>:<n>: error: x"),
                Arguments.of("C:/work/proj/Foo.java", "<path>"),
                Arguments.of("/home/u/proj/src/Foo.java:[42,15] cannot find symbol", "<path>:[<n>,<n>] cannot find symbol"),
                Arguments.of("/usr/lib/jvm/java-21/bin/java failed", "<path> failed"),
                Arguments.of("file:///C:/proj/Foo.java not found", "<path> not found"),
                Arguments.of("cannot read src/main/java/Foo.java", "cannot read src/main/java/Foo.java"),
                Arguments.of("a / b and/or 1/2", "a / b and/or 1/2"),
                Arguments.of("see https://example.org/docs/page", "see https://example.org/docs/page"),

                // --- hex addresses; short hex literals kept ------------------------------------
                Arguments.of("java.lang.Object@1b6d3586 is not serializable", "java.lang.Object@<hex> is not serializable"),
                Arguments.of("pointer 0x00007ff6a1b2c3d4", "pointer 0x<hex>"),
                Arguments.of("expected mask 0x1F", "expected mask 0x1F"),

                // --- timestamps, times, durations -------------------------------------------------
                Arguments.of("started at 2026-09-28T10:15:02.123Z", "started at <timestamp>"),
                Arguments.of("started at 2026-09-28 10:15:02+02:00", "started at <timestamp>"),
                Arguments.of("10:15:02 WARN slow", "<time> WARN slow"),
                Arguments.of("timed out after 1500 ms", "timed out after <duration>"),
                Arguments.of("took 2.5s to start", "took <duration> to start"),
                Arguments.of("took PT1.5S", "took <duration>"),

                // --- synthetic and generated names -----------------------------------------------
                Arguments.of("at com.acme.Foo.lambda$process$3(Foo.java:88)", "at com.acme.Foo.lambda$process$<n>(Foo.java:<n>)"),
                Arguments.of("UserService$$EnhancerBySpringCGLIB$$a1b2c3d4.save", "UserService$$<proxy>.save"),
                Arguments.of("UserService$$SpringCGLIB$$0.save", "UserService$$<proxy>.save"),
                Arguments.of("com.acme.Foo$$Lambda$14/0x0000000800c02a00 cannot be cast", "com.acme.Foo$$Lambda<n> cannot be cast"),
                Arguments.of("com.acme.Foo$$Lambda/0x000001f2c4056a30 cannot be cast", "com.acme.Foo$$Lambda<n> cannot be cast"),
                Arguments.of("Foo$1.run failed", "Foo$<n>.run failed"),
                Arguments.of("Order$Status is not visible", "Order$Status is not visible"),

                // --- literals and identifiers are KEPT (they distinguish errors) ---------------
                Arguments.of("expected: <3> but was: <4>", "expected: <3> but was: <4>"),
                Arguments.of("cannot find symbol symbol: class List", "cannot find symbol symbol: class List"),
                Arguments.of("index 5 out of bounds for length 3", "index 5 out of bounds for length 3"),

                // --- whitespace --------------------------------------------------------------
                Arguments.of("  multiple   spaces\n\tand tabs ", "multiple spaces and tabs"),
                Arguments.of("cannot find symbol\n  symbol:   class List\n  location: class com.acme.Foo",
                        "cannot find symbol symbol: class List location: class com.acme.Foo"),
                Arguments.of("", ""));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("table")
    void normalizes(String input, String expected) {
        assertThat(MessageNormalizer.normalize(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "[{index}] idempotent: {0}")
    @MethodSource("table")
    void isIdempotent(String input, String ignored) {
        String once = MessageNormalizer.normalize(input);

        assertThat(MessageNormalizer.normalize(once)).isEqualTo(once);
    }
}
