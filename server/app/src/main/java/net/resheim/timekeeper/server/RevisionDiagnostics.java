package net.resheim.timekeeper.server;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.stream.Collectors;
import org.jboss.logging.Logger;

/** Identifies a revision stream without logging account IDs or cursor signing keys. */
final class RevisionDiagnostics {
    private static final Logger LOG = Logger.getLogger(RevisionDiagnostics.class);
    private static final String SERVER_PACKAGE = "net.resheim.timekeeper.server.";

    static final class Write {
        final long before;
        final String cursorKey;
        boolean changed;

        Write(long before, String cursorKey) {
            this.before = before;
            this.cursorKey = cursorKey;
        }

        void committed(long after) {
            if (after <= before || !LOG.isInfoEnabled()) return;
            String source = StackWalker.getInstance().walk(frames -> frames
                    .filter(frame -> frame.getClassName().startsWith(SERVER_PACKAGE)
                            && !frame.getClassName().startsWith(RevisionDiagnostics.class.getName())
                            && !frame.getClassName().contains("_Subclass")
                            && !frame.getClassName().contains("_ClientProxy")
                            && !frame.getClassName().contains("$$Lambda")
                            && !(frame.getClassName().equals(ServerStore.class.getName()) && frame.getMethodName().equals("transaction")))
                    .limit(4)
                    .map(frame -> frame.getClassName().substring(SERVER_PACKAGE.length()) + "." + frame.getMethodName())
                    .collect(Collectors.joining("<-")));
            LOG.infof("Account revision committed stream=%s previous_revision=%d current_revision=%d source=%s",
                    stream(cursorKey.toLowerCase(Locale.ROOT)), before, after, source.isEmpty() ? "unknown" : source);
        }
    }

    static boolean recordsWrites() {
        return LOG.isInfoEnabled();
    }

    record CursorConflict(String stream, String collection, long cursorRevision, long currentRevision) {
        void log(String requestId) {
            LOG.warnf("Pagination cursor expired request_id=%s code=CURSOR_EXPIRED stream=%s collection=%s cursor_revision=%d current_revision=%d",
                    requestId, stream, collection, cursorRevision, currentRevision);
        }
    }

    static CursorConflict cursorConflict(byte[] key, String collection, long before, long after) {
        return new CursorConflict(stream(HexFormat.of().formatHex(key)), collection, before, after);
    }

    private static String stream(String key) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("dagsverk:revision-diagnostics:v1".getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(key.getBytes(StandardCharsets.US_ASCII)), 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private RevisionDiagnostics() {}
}
