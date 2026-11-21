package fr.proutlost.director.admin.server;

import fr.proutlost.director.api.model.RequestId;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Actor-owned, fixed-size, non-forgetting admission filter for one connection session.
 * False positives defer explicitly; false negatives are impossible. Never rotate/clear
 * while that session is live: evicting a detailed result must not permit reapplication.
 * 128 KiB/session, six SHA-256-derived probes. No payloads or Minecraft references.
 * This is a conservative replay fence, not a durable result/audit index (owned by J05).
 */
final class AdminRequestHistory {
    private static final int BITS=1<<20;
    private final long[] words=new long[BITS/64];
    private final MessageDigest digest;
    AdminRequestHistory() {
        try {digest=MessageDigest.getInstance("SHA-256");}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private int[] indices(RequestId id) {
        var uuid=id.value();
        var bytes=digest.digest(ByteBuffer.allocate(16).putLong(uuid.getMostSignificantBits()).putLong(uuid.getLeastSignificantBits()).array());
        var input=ByteBuffer.wrap(bytes);int[] result=new int[6];
        for(int i=0;i<result.length;i++)result[i]=input.getInt()&(BITS-1);
        return result;
    }
    boolean seen(RequestId id) {
        for(int bit:indices(id))if((words[bit>>>6]&(1L<<(bit&63)))==0)return false;
        return true;
    }
    void remember(RequestId id) {for(int bit:indices(id))words[bit>>>6]|=1L<<(bit&63);}
}
