class PrivateWithUnrelatedAnnotation {
    @Deprecated
    private void old() {
    }

    private static final int X = 1;

    @SuppressWarnings("unused")
    private final void helper() {
    }
}
