package fr.proutlost.integration.atlas;

import java.util.*;
import java.util.function.*;

/** Bounded transient consent tokens. The canonical markers remain in Antique Atlas. */
final class AtlasShareGate {
    static final int MAX_MARKERS=32,MAX_PENDING=64;
    static final long TTL=1200,COOLDOWN=40;
    record Actor(UUID player,UUID session,UUID connection,int atlas,String dimension) {
        Actor { Objects.requireNonNull(player);Objects.requireNonNull(session);Objects.requireNonNull(connection);Objects.requireNonNull(dimension);require(atlas>=0,"Atlas initialise requis."); }
    }
    record Marker(int id,String type,String label,String dimension,int x,int z,boolean visibleAhead,boolean global) {
        Marker { Objects.requireNonNull(type);Objects.requireNonNull(label);Objects.requireNonNull(dimension); }
    }
    record Offer(UUID token,Actor source,Actor target,Marker marker,long expiresAt) {}
    private final Map<UUID,Offer> offers=new HashMap<>();
    private final Map<UUID,Long> cooldowns=new HashMap<>();
    static void require(boolean condition,String message){if(!condition)throw new IllegalArgumentException(message);}
    private void sweep(long now){
        offers.values().removeIf(o->o.expiresAt()<=now);
        cooldowns.values().removeIf(end->end<=now);
    }
    Offer offer(Actor source,Actor target,Marker marker,int targetCount,long now){
        sweep(now);
        require(!source.player().equals(target.player()),"Choisir un autre joueur.");
        require(source.session().equals(target.session())&&source.dimension().equals(target.dimension())
            &&source.dimension().equals(marker.dimension()),"Les deux joueurs doivent etre dans la meme dimension.");
        require(!marker.global(),"Choisir un marqueur personnel de cet atlas.");
        require(!marker.label().isBlank()&&marker.label().length()<=64
            &&marker.label().codePoints().noneMatch(Character::isISOControl)&&marker.type().length()<=128,
            "Le marqueur doit avoir un libelle de 1 a 64 caracteres.");
        require(Math.abs((long)marker.x())<=29999984&&Math.abs((long)marker.z())<=29999984,"Marqueur hors limites.");
        require(source.atlas()!=target.atlas(),"Ces atlas partagent deja le meme contenu.");
        require(targetCount>=0&&targetCount<MAX_MARKERS,"Atlas destinataire plein (32 marqueurs maximum).");
        require(!cooldowns.containsKey(source.player()),"Attendre deux secondes entre deux propositions.");
        require(offers.size()<MAX_PENDING&&cooldowns.size()<128,"Partage momentanement occupe.");
        require(offers.values().stream().noneMatch(o->o.target().player().equals(target.player())),
            "Ce joueur a deja une proposition en attente.");
        var offer=new Offer(UUID.randomUUID(),source,target,marker,Math.addExact(now,TTL));
        offers.put(offer.token(),offer);cooldowns.put(source.player(),Math.addExact(now,COOLDOWN));return offer;
    }
    Optional<Offer> peek(UUID token,long now){sweep(now);return Optional.ofNullable(offers.get(token));}
    Offer claim(UUID token,Actor currentSource,Actor currentTarget,Marker currentMarker,int targetCount,long now){
        sweep(now);var offer=offers.get(token);require(offer!=null,"Proposition absente ou expiree.");
        require(offer.source().equals(currentSource)&&offer.target().equals(currentTarget),
            "Joueur, session, dimension ou atlas modifies : refaire la proposition.");
        require(offer.marker().equals(currentMarker),"Le marqueur source a change : refaire la proposition.");
        require(targetCount>=0&&targetCount<MAX_MARKERS,"Atlas destinataire plein (32 marqueurs maximum).");
        // Consume before any canonical write. Unknown write/send outcomes never permit blind replay.
        offers.remove(token);return offer;
    }
    void disconnected(UUID player){offers.values().removeIf(o->o.source().player().equals(player)||o.target().player().equals(player));cooldowns.remove(player);}
    static <T> T deliver(Offer accepted,Supplier<T> canonicalWriter,BiConsumer<UUID,T> targetedSender){
        var marker=Objects.requireNonNull(canonicalWriter.get(),"Atlas marker creation returned no marker");
        targetedSender.accept(accepted.target().player(),marker);return marker;
    }
}
