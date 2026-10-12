class NarrowCatchDoesLittleButIsNotBroad {
    int parse(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
