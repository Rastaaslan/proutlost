package fr.proutlost.director.api.model;
import java.security.*;
import java.util.*;
/** Immutable captured bytes. Hash covers exact codec bytes, not Java serialization. */
public final class BlobContent {
 public static final int MAX_BYTES=16*1024*1024;
 public static final long MAX_BATCH_BYTES=64L*1024*1024;
 private final BlobRef reference;private final byte[] bytes;
 public BlobContent(BlobRef reference,byte[] bytes){
  this.reference=Objects.requireNonNull(reference);Objects.requireNonNull(bytes);
  Checks.require(bytes.length<=MAX_BYTES&&reference.bytes()==bytes.length,"blob length");
  this.bytes=bytes.clone();Checks.require(reference.hash().equals(hash(this.bytes)),"blob digest");
 }
 public BlobRef reference(){return reference;}
 public byte[] bytes(){return bytes.clone();}
 public static BlobContent capture(ResourceId codec,int version,byte[] bytes){Objects.requireNonNull(bytes);Checks.require(bytes.length<=MAX_BYTES,"blob length");byte[] copy=bytes.clone();return new BlobContent(new BlobRef(hash(copy),copy.length,version,codec),copy);}
 public static Fingerprint hash(byte[] bytes){try{return new Fingerprint(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));}catch(NoSuchAlgorithmException e){throw new AssertionError(e);}}
 @Override public boolean equals(Object other){return other instanceof BlobContent b&&reference.equals(b.reference)&&Arrays.equals(bytes,b.bytes);}
 @Override public int hashCode(){return 31*reference.hashCode()+Arrays.hashCode(bytes);}
}
