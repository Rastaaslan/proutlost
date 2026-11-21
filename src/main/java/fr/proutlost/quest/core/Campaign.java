package fr.proutlost.quest.core;

import com.google.gson.Gson;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Campaign {
    private Campaign() {}
    public static List<QuestDefinition> load() {
        try (var in = Campaign.class.getResourceAsStream("/data/proutlost/quest/campaign.json")) {
            if (in == null) throw new IllegalStateException("Missing campaign");
            var defs = List.of(new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), QuestDefinition[].class));
            validate(defs); return defs;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }
    public static void validate(List<QuestDefinition> definitions) {
        var ids = new HashSet<String>();
        for (var q : definitions) if (!ids.add(q.questId())) throw new IllegalArgumentException("Duplicate quest");
        var visited = new HashSet<String>();
        while (visited.size() < ids.size()) {
            int before = visited.size();
            for (var q : definitions) {
                if (!ids.containsAll(q.prerequisites()) || !ids.containsAll(q.unlocks()))
                    throw new IllegalArgumentException("Unknown link: " + q.questId());
                for (var prerequisite : q.prerequisites()) {
                    var parent=definitions.stream().filter(p->p.questId().equals(prerequisite)).findFirst().orElseThrow();
                    if(!parent.visibilityScope().equals("SERVER")&&!parent.visibilityScope().equals(q.visibilityScope()))
                        throw new IllegalArgumentException("Ambiguous cross-scope prerequisite: "+q.questId());
                }
                if (visited.containsAll(q.prerequisites())) visited.add(q.questId());
            }
            if (before == visited.size()) throw new IllegalArgumentException("Cyclic campaign");
        }
    }
}
