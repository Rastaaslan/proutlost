package fr.proutlost.worldprep.gametest;

import java.util.function.Consumer;
import java.util.function.Supplier;

/** One scheduled poll. Failure terminates through GameTestInfo.fail, never a sequence predicate. */
public final class PipelineTestPoll {
    public static boolean step(Supplier<String> status,Runnable verified,Runnable succeed,Consumer<Exception> fail) {
        try {
            String value=status.get();
            if(value.startsWith("PAUSED"))throw new IllegalStateException("Actual pipeline paused: "+value);
            if(!value.startsWith("QUALIFIED_NOT_PUBLISHED"))return false;
            verified.run();succeed.run();return true;
        } catch(Exception failure){fail.accept(failure);return true;}
    }
    private PipelineTestPoll(){}
}
