package fr.proutlost.director.engine;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Enumerates only captured active scopes. Cartesian products are indexed, never materialized. */
public final class CandidateGenerator {
    public record Generated(CandidateId id, EventDefinition definition, DefinitionRef reference,
                            Optional<RegionId> region, Optional<UUID> group, RoleAssignments roles, ScopeSet scopes,
                            boolean completeRoles) {}
    public record Window(List<Generated> candidates, long partitions, long start, int examined) {
        public Window { candidates = List.copyOf(candidates); }
    }
    private record Audience(TargetScope type, Optional<RegionId> region, Optional<UUID> group, List<PlayerId> players, String key) {}

    public Window generate(DecisionInput input, DecisionRandom random) {
        int cap = Math.toIntExact(new EngineParameters(input.config()).ticks("scheduler.candidates_per_window"));
        if (cap < 1) throw new IllegalArgumentException("Empty candidate work budget");
        var context = input.context().context();
        List<EventDefinition> definitions = input.catalog().events().values().stream()
            .sorted(Comparator.comparing(d -> d.id().value())).toList();
        List<Audience> audiences = new ArrayList<>();
        context.regions().values().stream().filter(r -> r.region().status() == RegionStatus.ACTIVE)
            .sorted(Comparator.comparing(r -> r.region().regionId().value().toString())).forEach(r -> {
                RegionId region = r.region().regionId();
                List<PlayerId> present = sorted(r.presentPlayers());
                audiences.add(new Audience(TargetScope.REGION, Optional.of(region), Optional.empty(), present, "region/" + region.value()));
            });
        context.players().values().stream().sorted(Comparator.comparing(p -> p.playerId().value().toString())).forEach(p -> {
            var regions = p.regionIds().stream().filter(context.regions()::containsKey)
                .filter(id -> context.regions().get(id).region().status() == RegionStatus.ACTIVE)
                .sorted(Comparator.comparing(id -> id.value().toString())).toList();
            if (regions.isEmpty()) audiences.add(new Audience(TargetScope.SOLO, Optional.empty(), Optional.empty(), List.of(p.playerId()), "solo/" + p.playerId().value()));
            else for (var id : regions) audiences.add(new Audience(TargetScope.SOLO, Optional.of(id), Optional.empty(), List.of(p.playerId()), "solo/" + p.playerId().value() + "/" + id.value()));
        });
        context.groups().values().stream().sorted(Comparator.comparing(g -> g.groupId().toString())).forEach(g -> {
            for (var id : g.regions().stream().sorted(Comparator.comparing(r -> r.value().toString())).toList()) {
                var region = context.regions().get(id);
                if (region != null && region.region().status() == RegionStatus.ACTIVE)
                    audiences.add(new Audience(TargetScope.GROUP, Optional.of(id), Optional.of(g.groupId()), sorted(g.members()), "group/" + g.groupId() + "/" + id.value()));
            }
        });
        // Match the target index before constructing the virtual product. Empty target partitions
        // cannot consume all windows and starve a populated target type.
        List<List<Audience>> indexed = new ArrayList<>();
        Map<TargetScope, List<Audience>> byType = new EnumMap<>(TargetScope.class);
        for (var type : TargetScope.values()) byType.put(type, audiences.stream().filter(a -> a.type() == type).toList());
        long[] ends = new long[definitions.size()];
        long total = 0;
        for (var definition : definitions) {
            var eligibleType = byType.get(definition.target().scope());
            indexed.add(eligibleType);
            total = Math.addExact(total, eligibleType.size());
            ends[indexed.size() - 1] = total;
        }
        if (total == 0) return new Window(List.of(), 0, 0, 0);
        int count = (int) Math.min(cap, total);
        // Model one contiguous stream of partition visits. Use actual work (count), not
        // capacity, so a window larger than the catalogue does not skip traversal rounds.
        BigInteger partitionCount = BigInteger.valueOf(total);
        BigInteger windowPosition = BigInteger.valueOf(input.state().world().engineControl().revision().value())
            .multiply(BigInteger.valueOf(count));
        long start = windowPosition.mod(partitionCount).longValueExact();
        List<Generated> result = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) {
            BigInteger[] visit = windowPosition.add(BigInteger.valueOf(offset)).divideAndRemainder(partitionCount);
            long index = visit[1].longValueExact();
            int low = 0, high = ends.length - 1;
            while (low < high) {
                int middle = low + (high - low) / 2;
                if (ends[middle] <= index) low = middle + 1; else high = middle;
            }
            int definitionIndex = low;
            index -= definitionIndex == 0 ? 0 : ends[definitionIndex - 1];
            var definition = definitions.get(definitionIndex);
            var audience = indexed.get(definitionIndex).get((int) index);
            var assignments = roles(definition, audience, input, random, cap, visit[0].longValueExact());
            Set<ScopeRef> scopes = new HashSet<>();
            scopes.add(new ScopeRef(ScopeKind.WORLD, input.state().world().worldId().value()));
            audience.region().ifPresent(r -> scopes.add(new ScopeRef(ScopeKind.REGION, r.value())));
            assignments.roles().roles().values().forEach(players -> players.forEach(p -> scopes.add(new ScopeRef(ScopeKind.PLAYER, p.value()))));
            var ref = new DefinitionRef(definition.id(), definition.contentRevision(), RecordedAdvice.hash(new ModelCodec<>(EventDefinition.class).encode(definition)));
            String identity = definition.id().value() + "/" + ref.hash().value() + "/" + audience.key() + "/"
                + HexFormat.of().formatHex(new ModelCodec<>(RoleAssignments.class).encode(assignments.roles()));
            result.add(new Generated(new CandidateId(UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8))), definition,
                ref, audience.region(), audience.group(), assignments.roles(), new ScopeSet(input.state().world().worldId(), scopes), assignments.complete()));
        }
        return new Window(result, total, start, count);
    }

    private record Binding(RoleAssignments roles, boolean complete) {}
    private record Ranked(PlayerId player, double debt, double exposure, double tie) {}
    private static Binding roles(EventDefinition definition, Audience audience, DecisionInput input,
                                 DecisionRandom random, int cap, long partitionVisit) {
        var memories = new HashMap<PlayerId, PlayerOperationalMemory>();
        input.memory().operationalPlayers().forEach(m -> memories.put(m.playerId(), m));
        List<Ranked> players = new ArrayList<>();
        // Advance on successive visits to this partition, not global revisions. With a stable
        // index, the traversal quotient increases by one at each revisit, even across windows
        // straddling the catalogue boundary. Catalogue and audience periods cannot lock together.
        var available = audience.players();
        int size = available.size();
        int begin = size == 0 ? 0 : (int) (partitionVisit % size);
        for (int i = 0; i < Math.min(size, cap); i++) {
            PlayerId id = available.get((begin + i) % size);
            PlayerSnapshot snapshot = input.context().context().players().get(id);
            if (snapshot == null || snapshot.eligibility() != PlayerEligibility.ELIGIBLE) continue;
            if (audience.region().isPresent() && !snapshot.regionIds().contains(audience.region().orElseThrow())) continue;
            var memory = memories.get(id);
            double debt = memory == null ? new EngineParameters(input.config()).unit("score.neutral_feature") : memory.attentionDebt().value();
            double neutral = new EngineParameters(input.config()).unit("score.neutral_feature");
            double exposure = 1 - neutral;
            if (memory != null) {
                double halfLife = new EngineParameters(input.config()).positive("memory.event_half_life_ticks");
                double sum = memory.roleExposureRing().stream().filter(e -> definition.target().roles().containsKey(e.role()))
                    .mapToDouble(e -> e.intensity().value() * StrictMath.exp(-StrictMath.log(2)
                        * Math.max(0, input.state().world().simTick().value() - e.tick().value()) / halfLife)).sum();
                exposure = 1 - 1 / (1 + sum);
            }
            players.add(new Ranked(id, debt, exposure, random.next()));
        }
        players.sort(Comparator.comparingDouble(Ranked::exposure).thenComparing(Comparator.comparingDouble(Ranked::debt).reversed())
            .thenComparingDouble(Ranked::tie).thenComparing(p -> p.player().value().toString()));
        List<String> names = definition.target().roles().keySet().stream().sorted().toList();
        Map<String, List<PlayerId>> assignments = new TreeMap<>();
        if (names.size() > cap) return new Binding(new RoleAssignments(Map.of()), false);
        // A regional disaster can require several real witnesses. Always taking
        // only the minimum makes a two-player native request unreachable even
        // when its declared role permits six. Visit each legal cardinality after
        // a complete player rotation; the immutable engine revision already
        // records this traversal, so audits/replay need no hidden target state.
        // Other recipes and multi-role overlap/backtracking keep their policy.
        if (definition.target().scope() == TargetScope.REGION && definition.families().contains(Family.DISASTER)
            && names.size() == 1) {
            String name = names.getFirst();
            var role = definition.target().roles().get(name);
            int maximum = Math.min(role.max(), players.size());
            if (maximum > role.min()) {
                long round = partitionVisit / players.size();
                int cardinality = role.min() + (int) (round % (maximum - role.min() + 1));
                assignments.put(name, new ArrayList<>(players.stream().limit(cardinality).map(Ranked::player).toList()));
                return new Binding(new RoleAssignments(assignments), true);
            }
        }
        int[] work = {cap};
        boolean complete = assign(0, names, definition.target().roles(), players, assignments, work);
        return new Binding(new RoleAssignments(assignments), complete);
    }

    private static boolean assign(int slot, List<String> names, Map<String, Role> roles, List<Ranked> players,
                                  Map<String, List<PlayerId>> result, int[] work) {
        if (slot == names.size()) return true;
        String name = names.get(slot);
        result.put(name, new ArrayList<>());
        return fill(slot, 0, names, roles, players, result, work);
    }
    private static boolean fill(int slot, int from, List<String> names, Map<String, Role> roles, List<Ranked> players,
                                Map<String, List<PlayerId>> result, int[] work) {
        String name = names.get(slot);
        List<PlayerId> selected = result.get(name);
        if (selected.size() == roles.get(name).min()) {
            if (assign(slot + 1, names, roles, players, result, work)) return true;
            // Remove failed later-role assignments before exploring another binding.
            for (int j = slot + 1; j < names.size(); j++) result.remove(names.get(j));
            return false;
        }
        for (int i = from; i < players.size(); i++) {
            if (--work[0] < 0) return false;
            PlayerId player = players.get(i).player();
            boolean conflict = result.entrySet().stream().anyMatch(e -> !e.getKey().equals(name) && e.getValue().contains(player)
                && !(roles.get(name).overlap() && roles.get(e.getKey()).overlap()));
            if (conflict) continue;
            selected.add(player);
            if (fill(slot, i + 1, names, roles, players, result, work)) return true;
            selected.removeLast();
        }
        return false;
    }
    private static List<PlayerId> sorted(Set<PlayerId> players) {
        return players.stream().sorted(Comparator.comparing(p -> p.value().toString())).toList();
    }
}
