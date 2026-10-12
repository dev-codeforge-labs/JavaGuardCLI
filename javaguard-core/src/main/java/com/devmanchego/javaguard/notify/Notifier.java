package com.devmanchego.javaguard.notify;

/** Tells the user something (specification &sect;16). Fire-and-forget: implementations never throw and never block for long. */
public interface Notifier {

    Notifier NONE = notification -> { };

    void notify(Notification notification);
}
