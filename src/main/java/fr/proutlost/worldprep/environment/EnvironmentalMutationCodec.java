package fr.proutlost.worldprep.environment;

import fr.proutlost.worldprep.pipeline.PassId;
import fr.proutlost.worldprep.storage.PagedPlanStore;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.UUID;

/** Lossless canonical codec used by both environmental plan and journal pages. */
public final class EnvironmentalMutationCodec {
    public static final PagedPlanStore.EntryCodec<EnvironmentalMutation> INSTANCE = new PagedPlanStore.EntryCodec<>() {
        @Override public void write(DataOutput out, EnvironmentalMutation value) throws IOException {
            out.writeUTF(value.pass().name());
            out.writeInt(value.x()); out.writeInt(value.y()); out.writeInt(value.z());
            out.writeUTF(value.beforeState()); out.writeUTF(value.afterState());
            nullable(out, value.beforeBlockEntityType()); nullable(out, value.beforeBlockEntityNbt());
            nullable(out, value.afterBlockEntityType()); nullable(out, value.afterBlockEntityNbt());
            out.writeBoolean(value.groupId() != null);
            if (value.groupId() != null) { out.writeLong(value.groupId().getMostSignificantBits()); out.writeLong(value.groupId().getLeastSignificantBits()); }
        }
        @Override public EnvironmentalMutation read(DataInput in) throws IOException {
            PassId pass;
            try { pass = PassId.valueOf(in.readUTF()); }
            catch (IllegalArgumentException e) { throw new IOException("Unknown persisted environmental pass", e); }
            int x=in.readInt(), y=in.readInt(), z=in.readInt(); String before=in.readUTF(), after=in.readUTF();
            String beforeType=nullable(in), beforeNbt=nullable(in), afterType=nullable(in), afterNbt=nullable(in);
            UUID group=in.readBoolean()?new UUID(in.readLong(),in.readLong()):null;
            try { return new EnvironmentalMutation(pass,x,y,z,before,after,beforeType,beforeNbt,afterType,afterNbt,group); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid persisted environmental mutation", e); }
        }
    };
    private static void nullable(DataOutput out,String value)throws IOException{out.writeBoolean(value!=null);if(value!=null)out.writeUTF(value);}
    private static String nullable(DataInput in)throws IOException{return in.readBoolean()?in.readUTF():null;}
    private EnvironmentalMutationCodec() {}
}
