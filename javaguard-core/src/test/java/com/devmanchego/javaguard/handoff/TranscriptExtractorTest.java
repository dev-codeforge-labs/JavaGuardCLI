package com.devmanchego.javaguard.handoff;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan Phase 13: transcripts of the three agents are read by shape and never throw. */
class TranscriptExtractorTest {

    @TempDir
    Path dir;

    private Path write(String name, String content) throws Exception {
        Path file = dir.resolve(name);
        Files.writeString(file, content);
        return file;
    }

    @Test
    void claudeJsonlYieldsAssistantTextAndToolCalls() throws Exception {
        Path file = write("claude.jsonl", """
                {"type":"user","message":{"role":"user","content":"fix the build"}}
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"I will edit Foo."},{"type":"tool_use","name":"Edit","input":{"file_path":"src/Foo.java","old_string":"a"}}]}}
                not json at all
                {"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"Still failing."}]}}
                """);

        var excerpts = TranscriptExtractor.lastAssistantMessages(Optional.of(file), 6);

        assertThat(excerpts.messages()).hasSize(2);
        assertThat(excerpts.messages().get(0)).contains("I will edit Foo.").contains("[tool call: Edit src/Foo.java]");
        assertThat(excerpts.messages().get(1)).isEqualTo("Still failing.");
    }

    @Test
    void codexJsonlYieldsAssistantMessagesAndFunctionCalls() throws Exception {
        Path file = write("codex.jsonl", """
                {"timestamp":"t","type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"go"}]}}
                {"timestamp":"t","type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Trying a fix."}]}}
                {"timestamp":"t","type":"response_item","payload":{"type":"function_call","name":"shell","arguments":"{\\"command\\":\\"mvn test\\"}"}}
                """);

        var excerpts = TranscriptExtractor.lastAssistantMessages(Optional.of(file), 6);

        assertThat(excerpts.messages()).containsExactly("Trying a fix.", "[tool call: shell mvn test]");
    }

    @Test
    void geminiJsonDocumentYieldsGeminiMessages() throws Exception {
        Path file = write("gemini.json", """
                {"sessionId":"x","messages":[
                  {"type":"user","content":"hello"},
                  {"type":"gemini","content":"Let me look.","toolCalls":[{"name":"read_file","args":{"path":"A.java"}}]},
                  {"type":"gemini","content":"Done?"}]}
                """);

        var excerpts = TranscriptExtractor.lastAssistantMessages(Optional.of(file), 6);

        assertThat(excerpts.messages()).hasSize(2);
        assertThat(excerpts.messages().get(0)).contains("Let me look.").contains("[tool call: read_file A.java]");
        assertThat(excerpts.messages().get(1)).isEqualTo("Done?");
    }

    @Test
    void onlyTheLastNMessagesAreKept() throws Exception {
        StringBuilder jsonl = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            jsonl.append("{\"type\":\"assistant\",\"message\":{\"role\":\"assistant\",\"content\":\"m").append(i).append("\"}}\n");
        }

        var excerpts = TranscriptExtractor.lastAssistantMessages(Optional.of(write("many.jsonl", jsonl.toString())), 2);

        assertThat(excerpts.messages()).containsExactly("m4", "m5");
    }

    @Test
    void missingOrUnrecognizedTranscriptsGiveANoteNotAnException() throws Exception {
        assertThat(TranscriptExtractor.lastAssistantMessages(Optional.empty(), 6).note()).isPresent();
        assertThat(TranscriptExtractor.lastAssistantMessages(Optional.of(dir.resolve("nope.jsonl")), 6).note().get())
                .contains("not found");
        var garbage = TranscriptExtractor.lastAssistantMessages(Optional.of(write("g.txt", "\u0000\u0001 [[[ {")), 6);
        assertThat(garbage.messages()).isEmpty();
        assertThat(garbage.note()).isPresent();
    }
}
