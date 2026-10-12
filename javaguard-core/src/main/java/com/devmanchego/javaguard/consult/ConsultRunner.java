package com.devmanchego.javaguard.consult;

import com.devmanchego.javaguard.model.AgentKind;

import java.io.IOException;
import java.nio.file.Path;

/** Starts a consultation without waiting for it, so the hook that asks for it returns within its budget. */
public interface ConsultRunner {

    /** What to consult about: the session and which agent to ask. */
    record Launch(Path projectRoot, String sessionId, AgentKind agent) {
    }

    void start(Launch launch) throws IOException;
}
