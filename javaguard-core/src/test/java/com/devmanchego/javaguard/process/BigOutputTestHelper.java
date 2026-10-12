package com.devmanchego.javaguard.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Test fixture: prints {@code args[0]} numbered lines, to exercise output-bounding. */
public final class BigOutputTestHelper {

    public static void main(String[] args) throws IOException {
        int lines = Integer.parseInt(args[0]);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines; i++) {
            sb.append("LINE-").append(i).append('\n');
        }
        System.out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        System.out.flush();
    }
}
