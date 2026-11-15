package fr.proutlost.director.adapter.neoforge.server.effects;

import java.nio.ByteBuffer;
import java.util.*;

/** Actual provider projection includes renderer authority. Metadata equality
 * after a cold load cannot masquerade as an already rearmed native post-state.
 */
public record WeatherCoreRegionalWitness(WeatherCoreRegionalState state,boolean renderArmed) {
    public WeatherCoreRegionalWitness {
        Objects.requireNonNull(state);
        if(renderArmed&&state.kind()!=WeatherCoreRegionalState.Kind.ACTIVE)
            throw new IllegalArgumentException("inactive regional weather armed");
    }
    public byte[] bytes(){var bytes=state.bytes();return ByteBuffer.allocate(9+bytes.length).putInt(2).put((byte)(renderArmed?1:0)).putInt(bytes.length).put(bytes).array();}
    public static WeatherCoreRegionalWitness read(byte[] bytes){
        if(bytes==null||bytes.length<9||bytes.length>512)throw new IllegalArgumentException("regional witness size");
        var buffer=ByteBuffer.wrap(bytes);if(buffer.getInt()!=2)throw new IllegalArgumentException("regional witness version");
        byte armed=buffer.get();if(armed<0||armed>1)throw new IllegalArgumentException("regional witness armed flag");
        int length=buffer.getInt();if(length!=buffer.remaining())throw new IllegalArgumentException("regional witness frame length");
        var state=new byte[length];buffer.get(state);var result=new WeatherCoreRegionalWitness(WeatherCoreRegionalState.read(state),armed==1);
        if(!Arrays.equals(bytes,result.bytes()))throw new IllegalArgumentException("noncanonical regional witness");return result;
    }
}
