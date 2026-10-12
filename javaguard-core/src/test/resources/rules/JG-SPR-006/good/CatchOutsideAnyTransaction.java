class CatchOutsideAnyTransaction {
    public void save() {
        try {
            write();
        } catch (RuntimeException e) {
            System.out.println("not transactional");
        }
    }

    private void write() {
    }
}
