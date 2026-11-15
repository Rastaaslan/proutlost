package fr.proutlost.worldprep;
import fr.proutlost.worldprep.gametest.PipelineTestPoll;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class PipelineTestPollTest {
 @Test void pausedDriverTerminatesThroughFailureWithoutSuccess(){
  var failure=new AtomicReference<Exception>();var success=new AtomicInteger();
  assertTrue(PipelineTestPoll.step(()->"PAUSED: Quiescent file identity changed",()->fail("no audit"),success::incrementAndGet,failure::set));
  assertEquals(0,success.get());assertInstanceOf(IllegalStateException.class,failure.get());
 }
 @Test void pendingWaitsAndQualifiedRunsPhysicalChecksBeforeSuccess(){
  var count=new AtomicInteger();
  assertFalse(PipelineTestPoll.step(()->"RUNNING",()->fail("pending"),()->fail("pending"),e->fail(e)));
  assertTrue(PipelineTestPoll.step(()->"QUALIFIED_NOT_PUBLISHED",()->assertEquals(0,count.getAndIncrement()),()->assertEquals(1,count.getAndIncrement()),e->fail(e)));
  assertEquals(2,count.get());
 }
 @Test void statusAndDiskExceptionsArePreservedAsFailures(){
  var expected=new IllegalStateException("disk read failure");var received=new AtomicReference<Exception>();
  assertTrue(PipelineTestPoll.step(()->"QUALIFIED_NOT_PUBLISHED",()->{throw expected;},()->fail("no succeed"),received::set));assertSame(expected,received.get());
  received.set(null);assertTrue(PipelineTestPoll.step(()->{throw expected;},()->{},()->fail("no succeed"),received::set));assertSame(expected,received.get());
 }
}
