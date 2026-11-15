package fr.proutlost.director.adapter.neoforge.server.effects;

import java.util.*;

/** Pure authority fence for the finite provider cursor. The actual UUID, type,
 * timer and game time are independently read from the live Core manager.
 */
public final class WeatherCoreExpiryPolicy {
    public enum Decision { KEEP, EXPIRE_OWN, YIELD }
    private WeatherCoreExpiryPolicy(){}
    public static Decision decide(UUID expected,UUID actual,String expectedType,String actualType,
            long born,long duration,long now,long remaining){
        Objects.requireNonNull(expected);Objects.requireNonNull(expectedType);
        if(duration<=0||duration>72000||born<0||now<born||!expected.equals(actual)||!expectedType.equals(actualType))return Decision.YIELD;
        long end;
        try{end=Math.addExact(born,duration);}catch(ArithmeticException overflow){return Decision.YIELD;}
        if(now>end)return Decision.YIELD;
        long expectedRemaining=end-now+1;
        // Captures may be on either side of the level's native clock advance.
        // The cursor normalizes this bounded one-tick phase before Core decrements.
        if(remaining<expectedRemaining-1||remaining>expectedRemaining+1)return Decision.YIELD;
        return now==end?Decision.EXPIRE_OWN:Decision.KEEP;
    }
}
