package fr.proutlost.director.adapter.neoforge.server.effects;

import java.io.*;
import java.util.*;

/** Provider state only. This data never grants permission to apply a mutation. */
public record WeatherCoreRegionalState(Kind kind,UUID weather,UUID event,UUID operation,
        UUID owner,UUID mutation,long ownerEpoch,long overrideEpoch,long deadline,Region region) {
    public enum Kind { ABSENT, ACTIVE, RETIRED }
    public record Region(double centerX,double centerZ,float radius) {
        public Region {
            if(!Double.isFinite(centerX)||!Double.isFinite(centerZ)||!Float.isFinite(radius)
                ||radius<=0||radius>512||Math.abs(centerX)>29_999_999||Math.abs(centerZ)>29_999_999)
                throw new IllegalArgumentException("bounded finite regional weather geometry");
        }
        public float intensityAt(double x,double z) {
            double distance=Math.hypot(x-centerX,z-centerZ);if(distance>=radius)return 0;
            double fade=Math.min(radius,32);return distance<=radius-fade?1:(float)((radius-distance)/fade);
        }
    }
    public WeatherCoreRegionalState {
        Objects.requireNonNull(kind);
        if(kind==Kind.ABSENT) {
            if(weather!=null||event!=null||operation!=null||owner!=null||mutation!=null||region!=null
                ||ownerEpoch!=0||overrideEpoch!=0||deadline!=0)throw new IllegalArgumentException("absence with metadata");
        }else {
            Objects.requireNonNull(weather);Objects.requireNonNull(event);Objects.requireNonNull(operation);Objects.requireNonNull(owner);Objects.requireNonNull(mutation);Objects.requireNonNull(region);
            if(ownerEpoch<0||overrideEpoch<0||deadline<0)throw new IllegalArgumentException("regional weather authority/clock");
        }
    }
    public static WeatherCoreRegionalState absent(){return new WeatherCoreRegionalState(Kind.ABSENT,null,null,null,null,null,0,0,0,null);}
    public WeatherCoreRegionalState retired(){
        if(kind==Kind.ABSENT)return this;
        return new WeatherCoreRegionalState(Kind.RETIRED,weather,event,operation,owner,mutation,ownerEpoch,overrideEpoch,deadline,region);
    }
    public int remainingAt(long nativeTick) {
        if(kind!=Kind.ACTIVE||nativeTick<0||nativeTick>=deadline)throw new IllegalStateException("regional weather not live");
        long left=Math.subtractExact(deadline,nativeTick);if(left>72000)throw new IllegalStateException("regional weather TTL bound");
        return Math.toIntExact(left);
    }
    public byte[] bytes() {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            out.writeInt(1);out.writeByte(kind.ordinal());
            if(kind!=Kind.ABSENT){
                for(var id:List.of(weather,event,operation,owner,mutation)){out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
                out.writeLong(ownerEpoch);out.writeLong(overrideEpoch);out.writeLong(deadline);
                out.writeDouble(region.centerX());out.writeDouble(region.centerZ());out.writeFloat(region.radius());
            }
            out.flush();return bytes.toByteArray();
        }catch(IOException impossible){throw new UncheckedIOException(impossible);}
    }
    public static WeatherCoreRegionalState read(byte[] bytes) {
        if(bytes==null||bytes.length>256)throw new IllegalArgumentException("regional weather payload size");
        try {
            var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=1)throw new IllegalArgumentException("regional weather payload version");
            int ordinal=in.readUnsignedByte();if(ordinal>=Kind.values().length)throw new IllegalArgumentException("regional weather payload kind");
            var kind=Kind.values()[ordinal];WeatherCoreRegionalState state;
            if(kind==Kind.ABSENT)state=absent();
            else {
                var ids=new ArrayList<UUID>();for(int n=0;n<5;n++)ids.add(new UUID(in.readLong(),in.readLong()));
                state=new WeatherCoreRegionalState(kind,ids.get(0),ids.get(1),ids.get(2),ids.get(3),ids.get(4),in.readLong(),in.readLong(),in.readLong(),
                    new Region(in.readDouble(),in.readDouble(),in.readFloat()));
            }
            if(in.available()!=0||!Arrays.equals(bytes,state.bytes()))throw new IllegalArgumentException("noncanonical regional weather payload");return state;
        }catch(IOException e){throw new IllegalArgumentException("truncated regional weather payload",e);}
    }
}
