class ThreeConsecutiveCodeLines {
    void process(int x) {
        int total = 0;
        // int result = compute(x);
        // total = total + result;
        // System.out.println(total);
        total = total + x;
    }

    int compute(int x) {
        return x;
    }
}
