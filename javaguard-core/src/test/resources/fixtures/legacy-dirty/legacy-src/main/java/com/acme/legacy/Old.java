package com.acme.legacy;

import java.io.FileInputStream;
import java.io.IOException;

public class Old {

    // Pre-existing issue: resource not closed, not touched by any session (spec section 9.3 scoping fixture).
    public void readFirstByte(String path) {
        try {
            FileInputStream in = new FileInputStream(path);
            in.read();
        } catch (IOException e) {
            // Pre-existing issue: swallowed exception.
        }
    }
}
