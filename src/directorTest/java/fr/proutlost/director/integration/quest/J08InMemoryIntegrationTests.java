package fr.proutlost.director.integration.quest;

/** Pure Java J08 actor/inbox/AI gate, deliberately excludes file IO and native Minecraft. */
public final class J08InMemoryIntegrationTests {
    private J08InMemoryIntegrationTests() {}
    public static void main(String[] args) throws Exception {
        J08Tests.ai();
        J08Tests.quest();
        J08Tests.questPolicies101();
        J08Tests.questScheduledBindings();
        J08Tests.questKeyAndTransitionContracts();
        J08Tests.questTerminalCancellationReceipts();
        J08Tests.questConflict();
        J08Tests.questBindingRestoreGuard();
        J08Tests.questRestoreFailure();
        System.out.println("PASS J08 in-memory integration assertions="+J08Tests.assertions+
                " (TEST_ONLY store, no fsync/native/client qualification)");
    }
}
