package fr.proutlost.director.config;
import java.math.BigDecimal;
import java.util.Objects;
public record ConfigKey<T extends Number>(String name, Class<T> type, BigDecimal testingDefault) {
 public ConfigKey { Objects.requireNonNull(name); Objects.requireNonNull(type);Objects.requireNonNull(testingDefault);if(type!=Long.class&&type!=Double.class)throw new IllegalArgumentException("numeric key type"); }
 public T convert(BigDecimal n) { Object result; if(type==Long.class)result=Long.valueOf(n.longValueExact());else {double d=n.doubleValue();if(!Double.isFinite(d))throw new IllegalArgumentException("finite config");result=Double.valueOf(d);}return type.cast(result); }
}
