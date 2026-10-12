@SuppressWarnings("unchecked")
class ClassSuppressesUnchecked {
    void run(java.util.List list) {
        java.util.List<String> strings = (java.util.List<String>) list;
    }
}
