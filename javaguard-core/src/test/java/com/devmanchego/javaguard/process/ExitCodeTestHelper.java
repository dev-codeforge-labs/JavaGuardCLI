package com.devmanchego.javaguard.process;

/** Test fixture: exits with the given code. */
public final class ExitCodeTestHelper {
    public static void main(String[] args) {
        System.exit(Integer.parseInt(args[0]));
    }
}
