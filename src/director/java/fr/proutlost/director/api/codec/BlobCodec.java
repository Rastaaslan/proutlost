package fr.proutlost.director.api.codec;
import fr.proutlost.director.api.model.*;
import java.io.*;
/** Versioned binary blob frame; supports captured data larger than the bounded JSON envelope. */
public final class BlobCodec {
 public static final int MAX_REFERENCE_BYTES=1024, HEADER_BYTES=8, MAX_FRAME_BYTES=HEADER_BYTES+MAX_REFERENCE_BYTES+BlobContent.MAX_BYTES;
 private BlobCodec() {}
 public static byte[] encode(BlobContent blob){try{var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(0x57444231);byte[] ref=new ModelCodec<>(BlobRef.class).encode(blob.reference());Checks.require(ref.length>0&&ref.length<=MAX_REFERENCE_BYTES,"blob reference length");Checks.require((long)HEADER_BYTES+ref.length+blob.reference().bytes()<=MAX_FRAME_BYTES,"blob frame bound");out.writeInt(ref.length);out.write(ref);out.write(blob.bytes());return bytes.toByteArray();}catch(IOException e){throw new AssertionError(e);}}
 public static BlobContent decode(byte[] frame){Checks.require(frame.length>=HEADER_BYTES&&frame.length<=MAX_FRAME_BYTES,"blob frame bound");try{var in=new DataInputStream(new ByteArrayInputStream(frame));Checks.require(in.readInt()==0x57444231,"blob version");int length=in.readInt();Checks.require(length>0&&length<=MAX_REFERENCE_BYTES&&length<=in.available(),"blob reference length");BlobRef ref=new ModelCodec<>(BlobRef.class).decode(in.readNBytes(length));Checks.require(ref.bytes()<=BlobContent.MAX_BYTES&&ref.bytes()==in.available(),"blob frame length");return new BlobContent(ref,in.readNBytes((int)ref.bytes()));}catch(IOException e){throw new IllegalArgumentException("truncated blob",e);}}
}
