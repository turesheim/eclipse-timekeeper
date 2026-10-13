package net.resheim.eclipse.timekeeper.ui.client;

import net.resheim.eclipse.timekeeper.client.RemoteFailure;

/** A transport failure cannot prove that an ordinary create was not committed. */
public final class RemoteOutcomes {
    public static final String REVIEW_CREATE = "The save outcome is unknown. Cancel and refresh to review the records before creating again.";
    private RemoteOutcomes() { }
    public static boolean unknown(RuntimeException failure) {
        if (!(failure instanceof RemoteFailure remote)) return false;
        return switch (remote.kind()) {
            case OFFLINE, TIMEOUT, UNAVAILABLE, CANCELLED -> true;
            default -> false;
        };
    }
}
