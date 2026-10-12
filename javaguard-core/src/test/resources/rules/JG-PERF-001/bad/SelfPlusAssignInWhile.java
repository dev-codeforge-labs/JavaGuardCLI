class SelfPlusAssignInWhile {
    String repeat(String s, int times) {
        String out = "";
        int i = 0;
        while (i < times) {
            out = out + s;
            i++;
        }
        return out;
    }
}
