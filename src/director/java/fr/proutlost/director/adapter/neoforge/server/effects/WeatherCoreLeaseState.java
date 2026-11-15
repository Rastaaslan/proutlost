package fr.proutlost.director.adapter.neoforge.server.effects;

import java.io.*;
import java.util.*;

/** Actual Core globals plus a sealed owned lease identity. Unowned globals use
 * absolute native deadlines. Owned globals use the requested duration; their
 * real apply clock is immutable provider SavedData and is verified on readback.
 * This is the same relative-post principle as a vanilla timer lease: the permit
 * seals duration before asynchronous fsync, not an invented future apply tick.
 */
public record WeatherCoreLeaseState(List<Global> globals,Optional<Lease> lease) {
    private static final Set<String> VANILLA=Set.of("minecraft:clear","minecraft:rain","minecraft:thunder");
    public record Global(String type,UUID uuid,long deadline) {
        public Global {
            if(!VANILLA.contains(type)||uuid==null||deadline<0)
                throw new IllegalArgumentException("unsupported or malformed WeatherCore global");
        }
        public int remainingAt(long tick) {
            long remaining=Math.subtractExact(deadline,tick);
            if(tick<0||remaining<=0||remaining>Integer.MAX_VALUE)
                throw new IllegalStateException("counterfactual weather entropy boundary (WeatherCore)");
            return Math.toIntExact(remaining);
        }
    }
    public record Lease(UUID event,UUID operation,UUID owner,long epoch,UUID mutation,
                        String type,UUID global,long duration) {
        public Lease {
            Objects.requireNonNull(event);Objects.requireNonNull(operation);Objects.requireNonNull(owner);
            Objects.requireNonNull(mutation);Objects.requireNonNull(global);
            if(epoch<0||!VANILLA.contains(type)||duration<=0||duration>72000)
                throw new IllegalArgumentException("WeatherCore owned lease");
        }
    }
    public WeatherCoreLeaseState(List<Global> globals){this(globals,Optional.empty());}
    public WeatherCoreLeaseState {
        Objects.requireNonNull(globals);Objects.requireNonNull(lease);
        if(globals.isEmpty()||globals.size()>3)throw new IllegalArgumentException("bounded initialized global weather required");
        var ids=new HashSet<UUID>();var types=new HashSet<String>();
        for(var g:globals)if(g==null||!ids.add(g.uuid())||!types.add(g.type()))throw new IllegalArgumentException("duplicate global weather identity/type");
        globals=List.copyOf(globals); // Order affects Core's native rain/thunder updates.
        if(lease.isPresent()){
            var l=lease.orElseThrow();
            if(globals.size()!=1||!globals.getFirst().type().equals(l.type())||!globals.getFirst().uuid().equals(l.global())||globals.getFirst().deadline()!=l.duration())
                throw new IllegalArgumentException("WeatherCore owned global substitution");
        }
    }
    public static WeatherCoreLeaseState owned(Lease lease){return new WeatherCoreLeaseState(List.of(new Global(lease.type(),lease.global(),lease.duration())),Optional.of(lease));}
    public void requireProjectable(long tick){if(lease.isEmpty())for(var g:globals)g.remainingAt(tick);}
    public byte[] bytes(){
        try{
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeInt(2);out.writeInt(globals.size());
            for(var g:globals){out.writeUTF(g.type());uuid(out,g.uuid());out.writeLong(g.deadline());}
            out.writeBoolean(lease.isPresent());
            if(lease.isPresent()){
                var l=lease.orElseThrow();uuid(out,l.event());uuid(out,l.operation());uuid(out,l.owner());out.writeLong(l.epoch());uuid(out,l.mutation());out.writeUTF(l.type());uuid(out,l.global());out.writeLong(l.duration());
            }
            out.flush();return bytes.toByteArray();
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    private static void uuid(DataOutputStream out,UUID id)throws IOException{out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
    public static WeatherCoreLeaseState read(byte[] bytes){
        if(bytes==null||bytes.length>2048)throw new IllegalArgumentException("WeatherCore snapshot size");
        try{
            var in=new DataInputStream(new ByteArrayInputStream(bytes));
            if(in.readInt()!=2)throw new IllegalArgumentException("WeatherCore snapshot version");
            int count=in.readInt();if(count<1||count>3)throw new IllegalArgumentException("WeatherCore snapshot count");
            var entries=new ArrayList<Global>();for(int n=0;n<count;n++)entries.add(new Global(in.readUTF(),uuid(in),in.readLong()));
            int present=in.readUnsignedByte();if(present>1)throw new IllegalArgumentException("lease flag");
            Optional<Lease> lease=present==0?Optional.empty():Optional.of(new Lease(uuid(in),uuid(in),uuid(in),in.readLong(),uuid(in),in.readUTF(),uuid(in),in.readLong()));
            if(in.available()!=0)throw new IllegalArgumentException("WeatherCore trailing bytes");
            var result=new WeatherCoreLeaseState(entries,lease);
            if(!Arrays.equals(bytes,result.bytes()))throw new IllegalArgumentException("noncanonical WeatherCore snapshot");return result;
        }catch(IOException e){throw new IllegalArgumentException("truncated WeatherCore snapshot",e);}
    }
}
