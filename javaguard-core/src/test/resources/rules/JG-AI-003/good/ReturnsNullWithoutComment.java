class ReturnsNullWithoutComment {
    Object lookup(java.util.Map<String, Object> cache, String key) {
        return cache.get(key) == null ? null : cache.get(key);
    }
}
