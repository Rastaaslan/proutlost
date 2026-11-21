package fr.proutlost.director.adapter.neoforge.network;

import java.util.Objects;

/** Native J07 reply boundary. Delivery may fail after a partial packet queue;
 * a failed send must revoke its F8/J06 session before another request is read.
 * No ACK or gameplay permission is manufactured by this helper.
 */
public final class DirectorAdminReplyDispatch {
    private DirectorAdminReplyDispatch() {}

    /** Returns false only when outbound failed AND revocation completed.
     * A failing revoke is propagated: caller must revoke the entire server
     * session, never keep a potentially valid per-player authority.
     */
    public static boolean send(Runnable outbound, Runnable revoke) {
        Objects.requireNonNull(outbound);
        Objects.requireNonNull(revoke);
        try {
            outbound.run();
            return true;
        } catch (RuntimeException failedSend) {
            try {
                revoke.run();
            } catch (RuntimeException failedRevoke) {
                failedRevoke.addSuppressed(failedSend);
                throw new IllegalStateException("J07 reply failure with uncertain revocation",
                    failedRevoke);
            }
            return false;
        }
    }
}
