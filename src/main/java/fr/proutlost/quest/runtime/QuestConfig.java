package fr.proutlost.quest.runtime;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class QuestConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED, TEST_COMMANDS;
    public static final ModConfigSpec.IntValue MIN_CONTRIBUTORS, CIRCULATION_SECONDS, DISCUSSION_SECONDS, BALLOT_SECONDS, CAMP_ACTIVITY_SECONDS, CAMP_VISITS, SIGNAL_FIRE_COUNT, SCENE_INTERVAL_SECONDS, TRANSMISSION_INTERVAL_SECONDS, STABILIZATION_SECONDS, WORK_CONTRIBUTORS, WITNESS_ABSENCE_SECONDS;
    public static final ModConfigSpec.DoubleValue ACTIVE_RATIO, VOTE_PARTICIPATION, AMPLIFY_PERTURBATION_PROBABILITY;
    static {
        var b=new ModConfigSpec.Builder();
        ENABLED=b.comment("Enable isolated Quest Core; prepared locations and gameplay validation are still required.").define("enabled",false);
        TEST_COMMANDS=b.comment("Permit operator-only synthetic facts for testing. Never enable on a production world.").define("testCommands",false);
        MIN_CONTRIBUTORS=b.defineInRange("minimumContributors",2,1,1000);
        CIRCULATION_SECONDS=b.defineInRange("circulationSeconds",300,0,604800);
        ACTIVE_RATIO=b.defineInRange("activePopulationRatio",0.6,0.01,1.0);
        DISCUSSION_SECONDS=b.defineInRange("discussionSeconds",300,0,604800);
        BALLOT_SECONDS=b.defineInRange("ballotSeconds",180,1,604800);
        AMPLIFY_PERTURBATION_PROBABILITY=b.defineInRange("amplificationPerturbationProbability",0.5,0.0,1.0);
        VOTE_PARTICIPATION=b.defineInRange("voteParticipation",0.6,0.01,1.0);
        CAMP_ACTIVITY_SECONDS=b.defineInRange("campActivitySeconds",60,1,86400);
        CAMP_VISITS=b.defineInRange("campVisits",2,1,1000);
        SIGNAL_FIRE_COUNT=b.defineInRange("signalFireCount",3,3,16);
        SCENE_INTERVAL_SECONDS=b.defineInRange("sceneIntervalSeconds",10,0,86400);
        TRANSMISSION_INTERVAL_SECONDS=b.defineInRange("transmissionIntervalSeconds",10,0,86400);
        STABILIZATION_SECONDS=b.defineInRange("stabilizationSeconds",10,0,86400);
        WORK_CONTRIBUTORS=b.defineInRange("workContributors",2,1,1000);
        WITNESS_ABSENCE_SECONDS=b.defineInRange("witnessAbsenceSeconds",86400,1,604800);
        SPEC=b.build();
    }
    private QuestConfig() {}
}
