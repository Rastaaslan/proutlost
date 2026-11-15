package fr.proutlost.director.integration.ai;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.net.ssl.*;

/** TEST_ONLY deterministic HTTP exchange driver. Exercises the production request builder,
 * BodyHandler, streaming BodySubscriber and cancellation handle; no sockets or paid service. */
public final class HttpAiTransportTests {
    private static int assertions;
    private static final URI ENDPOINT=URI.create("https://advisor.invalid/v1/director/propose");
    private static void check(boolean ok,String label){assertions++;if(!ok)throw new AssertionError(label);}
    private static <T> T await(CompletionStage<T> stage)throws Exception{return stage.toCompletableFuture().get(3,TimeUnit.SECONDS);}
    private static void failed(CompletionStage<?> stage,String label)throws Exception{
        try{await(stage);throw new AssertionError(label);}catch(ExecutionException expected){
            check(expected.getCause().getMessage().equals("AI transport failed"),label+" sanitized");
            check(expected.getCause().getCause()==null,label+" no remote detail/secret cause");
        }
    }
    private static AiTransportRequest request(long deadline,int limit){
        var hash=new Fingerprint("a".repeat(64));
        var input=new AiInput(1,new RequestId(UUID.randomUUID()),new Pseudonym("p_0123456789abcdef"),new DecisionId(UUID.randomUUID()),hash,hash,hash,
                deadline,List.of(),List.of(),1,new AiOutputLimits(limit,0,0,0,0,BudgetMath.zero()));
        return new AiTransportRequest(input,new ResourceId("test:http"));
    }
    public static int run()throws Exception{
        assertions=0;var clock=new AtomicLong(1000);var credentials=new AtomicInteger();
        var client=new Client();var request=request(clock.get()+100_000_000,8);
        try(var transport=new HttpAiTransport(ENDPOINT,()->{credentials.incrementAndGet();return Optional.of("test-only-secret");},clock::get,Duration.ofSeconds(2),client)){
            check(client.calls==0 && credentials.get()==0,"construction makes no call or secret lookup");
            var result=transport.propose(request);
            check(client.calls==1 && credentials.get()==1,"explicit propose sends once");
            check(client.request.uri().equals(ENDPOINT) && client.request.method().equals("POST"),"exact HTTPS endpoint and POST");
            check(client.request.headers().firstValue("Authorization").orElseThrow().equals("Bearer test-only-secret"),"bearer header");
            check(client.request.headers().firstValue("Accept").orElseThrow().equals("application/json"),"JSON accept");
            check(client.request.headers().firstValue("Content-Type").orElseThrow().equals("application/json; charset=utf-8"),"JSON content type");
            check(client.request.timeout().orElseThrow().equals(Duration.ofMillis(100)),"remaining input deadline caps HTTP timeout");
            byte[] sent=published(client.request.bodyPublisher().orElseThrow());
            check(Arrays.equals(sent,new ModelCodec<>(AiInput.class).encode(request.input())),"exact canonical AiInput body");
            check(new ModelCodec<>(AiInput.class).decode(sent).equals(request.input()),"request body decodes as actual input");
            check(!new String(sent,java.nio.charset.StandardCharsets.UTF_8).contains("test-only-secret"),"secret absent from body");
            client.start(200," Application/JSON ; charset=utf-8");
            check(client.stream.demand==1,"one initial streaming demand");
            client.chunk(new byte[]{1,2,3},new byte[]{4});
            check(!result.toCompletableFuture().isDone() && client.stream.demand==1,"partial body waits and replenishes one demand");
            client.chunk(new byte[]{5,6,7,8});clock.set(2000);client.finish();
            var response=await(result);
            check(Arrays.equals(response.body(),new byte[]{1,2,3,4,5,6,7,8}),"exact limit accepted across chunks/buffers");
            check(response.receivedMonotonicNanos()==2000,"completion timestamp supplied clock");
            check(!client.stream.cancelled,"valid response not cancelled");
        }
        check(client.closed,"close shuts down owned HTTP client");
        for(int status:List.of(201,204,301,302,400,401,429,500))reject(status,"application/json");
        for(String type:Arrays.asList(null,"text/plain","text/html","application/jsonp","application/problem+json"))reject(200,type);
        // Enforce the cumulative cap both within one onNext and across multiple onNext calls.
        for(boolean split:List.of(false,true)){
            var c=new Client();try(var t=new HttpAiTransport(ENDPOINT,Optional::empty,clock::get,Duration.ofSeconds(1),c)){
                var result=t.propose(request(clock.get()+2_000_000_000L,8));c.start(200,"application/json");
                if(split){c.chunk(new byte[5]);c.chunk(new byte[4]);}else c.chunk(new byte[5],new byte[4]);
                failed(result,"oversized streamed response");check(c.stream.cancelled,"oversized stream cancelled");
                check(c.stream.requests==(split?2:1),"no demand after bound violation");
            }
        }
        var c=new Client();try(var t=new HttpAiTransport(ENDPOINT,Optional::empty,clock::get,Duration.ofMillis(50),c)){
            var result=t.propose(request(clock.get()+1_000_000_000,8));
            check(c.request.timeout().orElseThrow().equals(Duration.ofMillis(50)),"configured HTTP timeout caps longer deadline");
            check(c.request.headers().firstValue("Authorization").isEmpty(),"no optional bearer header");
            c.exchange.completeExceptionally(new HttpTimeoutException("remote secret test-only-secret"));
            failed(result,"HTTP timeout delivered through propose");
            int calls=c.calls;
            try{await(t.propose(request(clock.get(),8)));throw new AssertionError("expired");}
            catch(ExecutionException expected){check(expected.getCause().getMessage().equals("AI timeout"),"expired input fails immediately");}
            check(c.calls==calls,"expired input never sends HTTP");
            var cancelled=t.propose(request(clock.get()+1_000_000_000,8));c.start(200,"application/json");
            check(cancelled instanceof TransportOperation,"propose exposes tracked original operation");
            ((TransportOperation)cancelled).requestCancellation();((TransportOperation)cancelled).requestCancellation();
            check(c.exchange.cancellations==1 && c.stream.cancelled,"propose cancellation reaches original HTTP exchange and body");
            failed(cancelled,"cancelled HTTP result");
            var broken=t.propose(request(clock.get()+1_000_000_000,8));c.start(200,"application/json");
            c.subscriber.onError(new IllegalStateException("private response text"));failed(broken,"stream failure");
        }
        System.out.println("PASS HTTP propose request serialization/headers/deadlines/status/type/stream bounds/timeout/cancellation (TEST_ONLY client, no network)");
        return assertions;
    }
    private static void reject(int status,String type)throws Exception{
        var c=new Client();try(var t=new HttpAiTransport(ENDPOINT,Optional::empty,()->100,Duration.ofSeconds(1),c)){
            var result=t.propose(request(1_000_000_100,8));c.start(status,type);
            failed(result,"HTTP rejection "+status+"/"+type);
            check(c.stream.cancelled && c.stream.requests==0,"invalid headers cancel before body demand");
            check(c.calls==1,"no retry/redirect on invalid response");
        }
    }
    private static byte[] published(HttpRequest.BodyPublisher publisher)throws Exception{
        var result=new CompletableFuture<byte[]>();var bytes=new ByteArrayOutputStream();
        publisher.subscribe(new Flow.Subscriber<ByteBuffer>(){
            public void onSubscribe(Flow.Subscription subscription){subscription.request(Long.MAX_VALUE);}
            public void onNext(ByteBuffer buffer){byte[] b=new byte[buffer.remaining()];buffer.get(b);bytes.writeBytes(b);}
            public void onError(Throwable failure){result.completeExceptionally(failure);}
            public void onComplete(){result.complete(bytes.toByteArray());}
        });return await(result);
    }
    private static final class Stream implements Flow.Subscription {
        boolean cancelled;long demand;int requests;
        public void request(long count){check(count==1 && !cancelled,"bounded subscriber demand");demand+=count;requests++;}
        public void cancel(){cancelled=true;}
    }
    private static final class Exchange extends CompletableFuture<HttpResponse<byte[]>>{
        int cancellations;Stream stream;
        public boolean cancel(boolean interrupt){cancellations++;if(stream!=null)stream.cancel();return super.cancel(interrupt);}
    }
    private static final class Client extends HttpClient {
        int calls;boolean closed;HttpRequest request;HttpResponse.BodyHandler<byte[]> handler;
        HttpResponse.BodySubscriber<byte[]> subscriber;Exchange exchange;Stream stream;
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h){
            calls++;request=r;handler=(HttpResponse.BodyHandler<byte[]>)(Object)h;exchange=new Exchange();
            return (CompletableFuture<HttpResponse<T>>)(Object)exchange;
        }
        void start(int status,String type){
            var headers=HttpHeaders.of(type==null?Map.of():Map.of("Content-Type",List.of(type)),(a,b)->true);
            subscriber=handler.apply(new HttpResponse.ResponseInfo(){
                public int statusCode(){return status;}public HttpHeaders headers(){return headers;}public Version version(){return Version.HTTP_1_1;}
            });
            var current=exchange;var sent=request;
            subscriber.getBody().whenComplete((body,error)->{
                if(error!=null)current.completeExceptionally(error);else current.complete(new Response(sent,status,headers,body));
            });
            stream=new Stream();exchange.stream=stream;subscriber.onSubscribe(stream);
        }
        void chunk(byte[]... arrays){
            check(!stream.cancelled && stream.demand>0,"fixture only emits requested body chunks");stream.demand--;
            subscriber.onNext(Arrays.stream(arrays).map(ByteBuffer::wrap).toList());
        }
        void finish(){subscriber.onComplete();}
        public <T> HttpResponse<T> send(HttpRequest r,HttpResponse.BodyHandler<T> h){throw new AssertionError("blocking send forbidden");}
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r,HttpResponse.BodyHandler<T> h,HttpResponse.PushPromiseHandler<T> p){throw new AssertionError("push forbidden");}
        public Optional<CookieHandler> cookieHandler(){return Optional.empty();}public Optional<Duration> connectTimeout(){return Optional.of(Duration.ofSeconds(1));}
        public Redirect followRedirects(){return Redirect.NEVER;}public Optional<ProxySelector> proxy(){return Optional.empty();}
        public SSLContext sslContext(){throw new AssertionError("no TLS fixture");}public SSLParameters sslParameters(){return new SSLParameters();}
        public Optional<Authenticator> authenticator(){return Optional.empty();}public Version version(){return Version.HTTP_1_1;}
        public Optional<Executor> executor(){return Optional.empty();}public void shutdownNow(){closed=true;if(exchange!=null&&!exchange.isDone())exchange.cancel(true);}
    }
    private record Response(HttpRequest request,int statusCode,HttpHeaders headers,byte[] body) implements HttpResponse<byte[]>{
        public Optional<HttpResponse<byte[]>> previousResponse(){return Optional.empty();}public Optional<SSLSession> sslSession(){return Optional.empty();}
        public URI uri(){return request.uri();}public HttpClient.Version version(){return HttpClient.Version.HTTP_1_1;}
    }
}
