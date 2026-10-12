package com.acme.simple;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppTest {
    @Test
    void greetsByName() {
        assertEquals("Hello, World!", new App().greet("World"));
    }
}
