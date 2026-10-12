package com.devmanchego.javaguard.change;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Session file ledger (specification &sect;9.4), keyed by project-relative
 * path with {@code '/'} separators. Serialized as
 * {@code state/<session>/ledger.json}.
 *
 * @param version   schema version of this file
 * @param sessionId agent session id
 * @param files     touched files, in first-touch order
 */
public record Ledger(int version, String sessionId, Map<String, LedgerEntry> files) {

    public static final int CURRENT_VERSION = 1;

    public Ledger {
        Objects.requireNonNull(sessionId, "sessionId");
        files = files == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(files));
    }

    public static Ledger empty(String sessionId) {
        return new Ledger(CURRENT_VERSION, sessionId, Map.of());
    }

    Ledger with(String relativePath, LedgerEntry entry) {
        Map<String, LedgerEntry> copy = new LinkedHashMap<>(files);
        copy.put(relativePath, entry);
        return new Ledger(version, sessionId, copy);
    }
}
