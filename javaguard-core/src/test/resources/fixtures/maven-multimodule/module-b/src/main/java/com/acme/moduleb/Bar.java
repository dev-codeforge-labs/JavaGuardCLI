package com.acme.moduleb;

import com.acme.modulea.Foo;

public class Bar {
    public int doubled() {
        return new Foo().value() * 2;
    }
}
