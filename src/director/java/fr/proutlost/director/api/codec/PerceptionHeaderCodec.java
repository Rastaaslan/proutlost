package fr.proutlost.director.api.codec;
import fr.proutlost.director.api.model.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
/** Wire header: UUID binary, nonnegative canonical VarLong counters, bounded UTF-8 dimension. */
public final class PerceptionHeaderCodec {
 private PerceptionHeaderCodec() {}
 public static byte[] encode(PerceptionHeader h){try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);write(out,h.protocolVersion());uuid(out,h.worldId().value());write(out,h.bootEpoch().value());write(out,h.connectionEpoch().value());byte[] dimension=h.dimension().value().getBytes(StandardCharsets.UTF_8);Checks.require(dimension.length<=256,"dimension bound");write(out,dimension.length);out.write(dimension);uuid(out,h.instanceId().value());uuid(out,h.effectId().value());write(out,h.sequence().value());write(out,h.remainingTtlTicks().value());return bytes.toByteArray();}catch(IOException e){throw new AssertionError(e);}}
 public static PerceptionHeader decode(byte[] bytes){Checks.require(bytes.length<=512,"header bound");try{var in=new DataInputStream(new ByteArrayInputStream(bytes));int version=Math.toIntExact(read(in));WorldId world=new WorldId(uuid(in));Epoch boot=new Epoch(read(in)),connection=new Epoch(read(in));long length=read(in);Checks.require(length<=256&&length<=in.available(),"dimension bound");byte[] dim=in.readNBytes((int)length);String dimension=StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(dim)).toString();var h=new PerceptionHeader(version,world,boot,connection,new DimensionId(dimension),new EventInstanceId(uuid(in)),new EffectId(uuid(in)),new Sequence(read(in)),new DurationTicks(read(in)));Checks.require(in.available()==0,"header trailing bytes");return h;}catch(IOException e){throw new IllegalArgumentException("invalid header",e);}}
 private static void uuid(DataOutputStream out,UUID id)throws IOException{out.writeLong(id.getMostSignificantBits());out.writeLong(id.getLeastSignificantBits());}
 private static UUID uuid(DataInputStream in)throws IOException{return new UUID(in.readLong(),in.readLong());}
 private static void write(DataOutputStream out,long n)throws IOException{Checks.nonnegative(n);do{int b=(int)(n&127);n>>>=7;out.writeByte(n==0?b:b|128);}while(n!=0);}
 private static long read(DataInputStream in)throws IOException{long n=0;for(int i=0;i<9;i++){int b=in.readUnsignedByte();n|=(long)(b&127)<<(7*i);if((b&128)==0){Checks.require(i==0||(b&127)!=0,"overlong VarLong");return n;}}throw new IllegalArgumentException("VarLong overflow");}
}
