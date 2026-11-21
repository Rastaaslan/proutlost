package fr.proutlost.director.api.model;
public final class NetworkContracts {
 private NetworkContracts() {}
 public static boolean matches(PerceptionKind k,PerceptionParameters p){return switch(k){case SOUND -> p instanceof SoundParameters;case PARTICLES -> p instanceof ParticleParameters;case APPARITION -> p instanceof ApparitionParameters;case LOCAL_WEATHER -> p instanceof WeatherParameters;case GHOST -> p instanceof GhostParameters;case SYMBOL -> p instanceof SymbolParameters;};}
 public static boolean current(PerceptionHeader h,SessionReset session,DimensionId dimension){return h.protocolVersion()==session.protocolVersion()&&h.worldId().equals(session.worldId())&&h.bootEpoch().equals(session.bootEpoch())&&h.connectionEpoch().equals(session.connectionEpoch())&&h.dimension().equals(dimension);}
}
