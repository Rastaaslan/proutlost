package fr.proutlost.quest.qa;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.integration.quest.DirectorCommittedFactEvent;
import fr.proutlost.quest.choice.CollectiveVote;
import fr.proutlost.quest.core.*;
import fr.proutlost.quest.perception.PerceptionSession;
import fr.proutlost.quest.runtime.*;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import org.slf4j.LoggerFactory;
import java.nio.file.*;
import java.util.*;

/** Isolated TEST_ONLY harness over the existing engines. No campaign data or synthetic Director facts. */
public final class NeutralLabRuntime {
    private static final String DIRECTOR_TYPE = "proutlost:safe_micro_observed";
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(NeutralLabRuntime.class);
    private NeutralLabRuntime() {}
    public static void install() {
        NeoForge.EVENT_BUS.addListener(NeutralLabRuntime::register);
        NeoForge.EVENT_BUS.addListener(NeutralLabRuntime::started);
        NeoForge.EVENT_BUS.addListener(PerceptionDelivery::tick);
        NeoForge.EVENT_BUS.addListener(PerceptionDelivery::stopped);
        if (ModList.get().isLoaded("proutlost_director"))
            NeoForge.EVENT_BUS.addListener(NeutralLabRuntime::director);
    }
    private static void guard(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("QA server thread required");
        if (!Boolean.getBoolean("proutlost.quest.neutralLab") || server.getPort() != 25579
            || !server.getPlayerList().isUsingWhitelist() || QuestConfig.ENABLED.get() || QuestConfig.TEST_COMMANDS.get())
            throw new IllegalStateException("Neutral QA requires TEST_ONLY, port 25579, whitelist and disabled campaign");
        try {
            String configured = System.getProperty("proutlost.quest.neutralLabRoot", "");
            Path requested = Path.of(configured);
            Path actual = server.getWorldPath(LevelResource.ROOT).toRealPath();
            if (configured.isBlank() || !requested.isAbsolute() || !requested.toRealPath().equals(actual))
                throw new IllegalStateException("Neutral QA world path mismatch");
            Path marker = actual.resolve("TEST_ONLY");
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)
                || !Files.readString(marker).trim().equals("PROUTLOST_NEUTRAL_QA_V1")
                || Files.exists(actual.resolve("data/proutlost_quest_v1.dat")))
                throw new IllegalStateException("Neutral QA marker missing or campaign state present");
        } catch (java.io.IOException e) { throw new IllegalStateException("Neutral QA path unavailable", e); }
    }
    public static void started(ServerStartedEvent event) { guard(event.getServer()); }
    private static NeutralLabSavedData data(MinecraftServer server) {
        guard(server);
        return server.overworld().getDataStorage().computeIfAbsent(NeutralLabSavedData.FACTORY, NeutralLabSavedData.FILE_ID);
    }
    /** The only Director ingress. Stable committed identity and all scopes are retained. */
    private static void director(DirectorCommittedFactEvent event) {
        var fact = event.fact();
        if (!fact.factType().value().equals(DIRECTOR_TYPE)) return;
        if (!fact.durable() || fact.evidenceStatus() != EvidenceStatus.CONFIRMED)
            throw new IllegalArgumentException("Unconfirmed neutral Director fact");
        var d = data(event.server()); long now = System.currentTimeMillis();
        // The exact generic type filter above takes canonicalType's immediate
        // non-quest_fact return path; Campaign.load is never reached here.
        var translated = QuestDirectorFacts.translate(fact, now);
        // safe_micro is a SOLO observation: two separate addressed facts are needed.
        if (translated.playerScope().size() != 1 || !d.participants.containsAll(translated.playerScope()) || fact.eventId().isEmpty()) {
            LOG.info("QA_DIRECTOR_FACT_IGNORED fact={} reason=UNBOUND_SOLO_EVENT", fact.factId().value()); return;
        }
        var prior = d.core.factById(translated.factId());
        if (prior.isPresent()) {
            var old = prior.orElseThrow();
            var replay = new WorldFact(translated.factId(), translated.factType(), translated.sourceSystem(),
                translated.playerScope(), translated.groupScope(), translated.regionId(), translated.locationId(),
                old.timestamp(), translated.payload(), translated.persistence(), translated.narrativeVisibility());
            if (!old.equals(replay)) throw new IllegalArgumentException("Neutral Director replay conflict");
            LOG.info("QA_DIRECTOR_REPLAY fact={} duplicate=true", translated.factId()); return;
        }
        d.core.accept(translated); d.core.evaluate(now); d.setDirty();
        LOG.info("QA_DIRECTOR_FACT fact={} accepted={} privateScopes={}", translated.factId(),
            d.core.factById(translated.factId()).isPresent(), translated.playerScope().size());
    }
    public static void register(RegisterCommandsEvent event) {
        var root = Commands.literal("qa").requires(s -> Boolean.getBoolean("proutlost.quest.neutralLab") && s.hasPermission(4));
        for (String action : List.of("status", "journal", "scene", "recall", "effects", "dossier", "open", "close", "finish"))
            root.then(Commands.literal(action).executes(c -> run(c, action)));
        root.then(Commands.literal("start").then(Commands.argument("other", EntityArgument.player()).executes(c -> run(c, "start"))));
        root.then(Commands.literal("record").then(Commands.argument("fragment", EvidenceArguments.type()).executes(c -> run(c, "record"))));
        root.then(Commands.literal("share").then(Commands.argument("fragment", EvidenceArguments.type())
            .then(Commands.argument("recipient", EntityArgument.player()).executes(c -> run(c, "share")))));
        root.then(Commands.literal("compare").then(Commands.argument("first", EvidenceArguments.type())
            .then(Commands.argument("second", EvidenceArguments.type()).executes(c -> run(c, "compare")))));
        root.then(Commands.literal("confirm").then(Commands.argument("option", StringArgumentType.word()).executes(c -> run(c, "confirm"))));
        root.then(Commands.literal("reset").then(Commands.literal("TEST_ONLY").executes(c -> run(c, "reset"))));
        event.getDispatcher().register(Commands.literal("proutlost").then(root));
    }
    private static void tell(ServerPlayer p, String message) { p.sendSystemMessage(Component.literal(message)); }
    private static List<ServerPlayer> pair(MinecraftServer server, NeutralLabSavedData d) {
        if (d.participants.size() != 2) throw new IllegalStateException("Deux participants QA requis");
        var players = d.participants.stream().sorted().map(server.getPlayerList()::getPlayer).toList();
        if (players.stream().anyMatch(p -> p == null || p.isSpectator())) throw new IllegalStateException("Les deux participants doivent être présents");
        if (players.get(0).serverLevel() != players.get(1).serverLevel() || players.get(0).distanceToSqr(players.get(1)) > 144)
            throw new IllegalStateException("Rejoignez-vous à moins de 12 blocs");
        return players;
    }
    private static PerceptionSession.Fragment latest(NeutralLabSavedData d, UUID player) {
        if (d.scene == null) throw new IllegalStateException("Jouez la scène QA");
        var values = d.scene.forPlayer(player);
        if (values.isEmpty()) throw new IllegalStateException("Aucune perception personnelle");
        return values.getLast();
    }
    private static void deliver(ServerPlayer p, PerceptionSession.Fragment f) {
        if (f.family() == PerceptionSession.Family.SOUND) PerceptionDelivery.schedule(p, f.sequence());
        tell(p, "QA privé " + f.family() + " : " + String.join(" · ", f.sequence())
            + "\nCollecte volontaire : /proutlost qa record " + EvidenceArguments.quoted(f.id()));
    }
    private static int run(CommandContext<CommandSourceStack> c, String action)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p = c.getSource().getPlayerOrException();
        try {
            var d = data(p.server); long now = System.currentTimeMillis();
            if (!Set.of("start", "reset").contains(action) && !d.participants.contains(p.getUUID()))
                throw new SecurityException("Participant QA requis");
            switch (action) {
                case "start" -> {
                    if (!d.participants.isEmpty()) throw new IllegalStateException("Session déjà démarrée ; journal ou reset TEST_ONLY");
                    var other = EntityArgument.getPlayer(c, "other");
                    if (p == other || p.isSpectator() || other.isSpectator() || p.serverLevel() != other.serverLevel() || p.distanceToSqr(other) > 144)
                        throw new IllegalStateException("Deux joueurs distincts proches requis");
                    d.participants.add(p.getUUID()); d.participants.add(other.getUUID());
                    var fixture=NeutralLabCampaign.load();d.scene = new PerceptionSession("QA_POV_" + d.session, fixture.sounds(), fixture.symbols());
                    for (var actor : pair(p.server, d)) { d.core.active(actor.getUUID(), now); tell(actor, "QA-00 démarré. /proutlost qa journal"); }
                    d.emit("QA_STARTED", p.getUUID(), now);
                }
                case "journal" -> {
                    for (var page : d.core.journal(p.getUUID())) {
                        tell(p, page.id() + " [" + page.state() + "] " + (page.summary().isBlank() ? page.action() : page.summary()));
                        for (var e : page.evidence()) tell(p, EvidenceArguments.quoted(e.observation().id()) + " : " + e.observation().text());
                    }
                    if (!d.outcome.isBlank()) tell(p, "Conclusion QA persistante : " + d.outcome);
                }
                case "status" -> tell(p, "TEST_ONLY fixture="+NeutralLabCampaign.load().sha256()+" session=" + d.session + " ; trial=" + (d.scene == null ? 0 : d.scene.trial())
                    + " ; QA-03=" + d.core.state("QA-03", p.getUUID()) + " ; vote=" + (d.vote == null ? "NONE" : d.vote.phase())
                    + ". Les rendus et l'audio exigent une observation réelle.");
                case "scene" -> {
                    d.requireAvailable("QA-01A", p.getUUID()); var positions = new LinkedHashMap<UUID,PerceptionSession.Position>();
                    for (var actor : pair(p.server, d)) positions.put(actor.getUUID(), new PerceptionSession.Position(
                        actor.level().dimension().location().toString(), actor.getX(), actor.getY(), actor.getZ()));
                    d.scene.play(positions, now);
                    for (var actor : pair(p.server, d)) deliver(actor, latest(d, actor.getUUID()));
                }
                case "recall" -> deliver(p, latest(d, p.getUUID()));
                case "record" -> {
                    var f = latest(d, p.getUUID());
                    if (!f.id().equals(StringArgumentType.getString(c, "fragment"))) throw new SecurityException("Fragment privé, expiré ou d'une autre session");
                    d.scene.record(p.getUUID(), f.id(), d.core); d.core.evaluate(now);
                    tell(p, "Relevé QA personnel conservé. /proutlost qa share " + EvidenceArguments.quoted(f.id()) + " <autre_joueur>");
                }
                case "share" -> {
                    var recipient = EntityArgument.getPlayer(c, "recipient"); String id = StringArgumentType.getString(c, "fragment");
                    if (recipient == p || !d.participants.contains(recipient.getUUID()) || !latest(d, p.getUUID()).id().equals(id))
                        throw new SecurityException("Destinataire ou fragment QA invalide");
                    d.core.share(p.getUUID(), id, Set.of(recipient.getUUID())); d.core.evaluate(now);
                    tell(p, "Seul ce relevé a été partagé."); tell(recipient, "Relevé QA partagé volontairement ; consultez journal.");
                }
                case "compare" -> {
                    d.requireAvailable("QA-02", p.getUUID());
                    var a = d.core.evidence(p.getUUID(), StringArgumentType.getString(c, "first")).orElseThrow();
                    var b = d.core.evidence(p.getUUID(), StringArgumentType.getString(c, "second")).orElseThrow();
                    if (a.witness().equals(b.witness()) || a.type().equals(b.type()) || !a.origin().equals(b.origin())
                        || !a.id().equals(latest(d, a.witness()).id()) || !b.id().equals(latest(d, b.witness()).id())
                        || !Set.of("SOUND_RECORDED", "SYMBOLS_RECORDED").containsAll(Set.of(a.type(), b.type())))
                        throw new IllegalStateException("Deux perceptions complémentaires accessibles du même essai requises");
                    d.emit("QA_JOINED", p.getUUID(), now); tell(p, "QA-02 : comparaison valide.");
                }
                case "effects" -> {
                    d.requireAvailable("QA-03", p.getUUID()); var actors = pair(p.server, d);
                    if (p.serverLevel() != p.server.overworld()) throw new IllegalStateException("Galerie météo dans l'Overworld QA uniquement");
                    for (var actor : actors) {
                        actor.serverLevel().sendParticles(actor, ParticleTypes.END_ROD, true,
                            actor.getX(), actor.getEyeY(), actor.getZ() + 2, 24, .5, .5, .5, .02);
                        PerceptionDelivery.schedule(actor, List.of("grave", "moyen", "aigu"));
                        tell(actor, "GALERIE QA : particules et trois notes. La météo réelle se teste par le déclenchement Director régional du protocole QA. Aucun rendu ni audio humain accepté automatiquement.");
                    }
                    LOG.info("QA_VANILLA_EFFECTS_DISPATCHED session={} participants=2", d.session);
                }
                case "dossier" -> {
                    d.requireAvailable("QA-04", p.getUUID());
                    if (d.vote == null) d.vote = new CollectiveVote("QA-04:" + d.session,
                        new CollectiveVote.Rules(5000, 15000, 1), new CollectiveVote.Dossier(
                            NeutralLabCampaign.load().options(), "Conclusion factice OPTION_A ou OPTION_B", "Aucun effet sur la vraie campagne", "Deux consultations et deux votes requis"), now);
                    var dossier = d.vote.consult(p.getUUID()); tell(p, String.join(" / ", dossier.options())
                        + "\n" + dossier.knownConsequences() + "\nDiscussion 5 s ; scrutin 15 s. " + d.vote.audit());
                }
                case "open" -> {
                    pair(p.server, d);
                    if (d.vote == null || !d.participants.stream().allMatch(d.vote::hasConsulted)
                        || d.participants.stream().anyMatch(actor -> d.core.state("QA-03", actor) != QuestCore.State.RESOLVED))
                        throw new IllegalStateException("Deux faits Director personnels et deux consultations requis");
                    d.vote.open(Set.copyOf(d.participants), now); tell(p, "Scrutin QA ouvert pour 15 s.");
                }
                case "confirm" -> {
                    if (d.vote == null || !d.vote.eligible().equals(d.participants)) throw new IllegalStateException("Électorat QA invalide");
                    d.vote.confirm(p.getUUID(), StringArgumentType.getString(c, "option"), now); tell(p, "Vote QA confirmé une seule fois.");
                }
                case "close" -> {
                    if (d.vote == null) throw new IllegalStateException("Aucun scrutin QA");
                    if (d.vote.close(now) == CollectiveVote.Phase.DECIDED) { d.outcome = d.vote.result(); d.emit("QA_VOTED", p.getUUID(), now); }
                    tell(p, "Vote QA : " + d.vote.phase() + (d.outcome.isBlank() ? "" : " ; résultat " + d.outcome));
                }
                case "finish" -> { d.requireAvailable("QA-05", p.getUUID()); d.emit("QA_COMPLETE", p.getUUID(), now); tell(p, "QA-05 : conclusion fictive persistante " + d.outcome); }
                case "reset" -> { d.reset(); tell(p, "Seul l'état du laboratoire neutre QA a été réinitialisé."); }
                default -> throw new IllegalArgumentException("Action QA inconnue");
            }
            d.setDirty(); return 1;
        } catch (IllegalArgumentException | IllegalStateException | SecurityException | NoSuchElementException | NullPointerException e) {
            c.getSource().sendFailure(Component.literal("QA refusé : " + Objects.toString(e.getMessage(), "préconditions manquantes"))); return 0;
        }
    }
}
