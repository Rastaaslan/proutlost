package fr.proutlost.director.integration.quest;

/** Dedicated, fixture-free fail-closed J08 binding recovery regression. */
public final class J08BindingRestoreSafetyTests {
    private J08BindingRestoreSafetyTests() {}
    public static void main(String[] args) throws Exception {
        int before=J08Tests.assertions;
        J08Tests.questBindingRestoreGuard();
        System.out.println("PASS J08 binding recovery assertions="+(J08Tests.assertions-before));
    }
}
