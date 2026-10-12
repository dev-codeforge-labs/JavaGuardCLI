package com.acme.moduleb;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BarTest {
    @Test
    void doublesFooValue() {
        assertEquals(84, new Bar().doubled());
    }
}
