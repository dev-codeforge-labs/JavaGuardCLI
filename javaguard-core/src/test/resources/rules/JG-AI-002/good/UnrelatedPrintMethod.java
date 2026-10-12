class UnrelatedPrintMethod {
    void run(Receipt receipt) {
        receipt.printStackTrace(false, true);
    }

    interface Receipt {
        void printStackTrace(boolean verbose, boolean color);
    }
}
