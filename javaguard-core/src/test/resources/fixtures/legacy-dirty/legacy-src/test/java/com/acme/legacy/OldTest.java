package com.acme.legacy;

import org.junit.Test;

public class OldTest {
    @Test
    public void readFirstByteDoesNotThrow() {
        new Old().readFirstByte("does-not-exist.bin");
    }
}
