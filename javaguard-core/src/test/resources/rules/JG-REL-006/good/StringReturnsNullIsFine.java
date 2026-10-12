class StringReturnsNullIsFine {
    String maybe(boolean has) {
        if (!has) {
            return null;
        }
        return "x";
    }
}
