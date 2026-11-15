package fr.proutlost.director.integration.ai;

import fr.proutlost.director.api.codec.ModelCodec;
import fr.proutlost.director.api.model.*;
import fr.proutlost.director.api.port.AiTransport;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import java.util.function.*;

/** Explicit opt-in HTTPS transport. Never follows redirects or logs credentials/response text.
 * Construct only on the transport executor, with a dedicated bounded HTTP executor.
 */
public final class HttpAiTransport implements AiTransport, AutoCloseable {
    private final URI endpoint;
    private final Supplier<Optional<String>> bearer;
    private final LongSupplier nanoTime;
    private final HttpClient client;
    private final Duration timeout;
    public HttpAiTransport(URI endpoint, Supplier<Optional<String>> bearer, LongSupplier nanoTime,
            Executor executor, Duration timeout) {
        this(validateEndpoint(endpoint,timeout),bearer,nanoTime,timeout,HttpClient.newBuilder().executor(executor).connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    /** Package-local injection seam; production still owns a dedicated no-redirect client. */
    HttpAiTransport(URI endpoint, Supplier<Optional<String>> bearer, LongSupplier nanoTime,
            Duration timeout, HttpClient client) {
        validateEndpoint(endpoint,timeout);
        this.endpoint = endpoint; this.bearer = Objects.requireNonNull(bearer);
        this.nanoTime = Objects.requireNonNull(nanoTime); this.timeout = timeout;
        this.client = Objects.requireNonNull(client);
        if(client.followRedirects()!=HttpClient.Redirect.NEVER)throw new IllegalArgumentException("AI redirects");
    }
    private static URI validateEndpoint(URI endpoint, Duration timeout) {
        Objects.requireNonNull(endpoint);
        if (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null ||
                endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null ||
                !"/v1/director/propose".equals(endpoint.getPath())) throw new IllegalArgumentException("AI endpoint");
        if (timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("timeout");
        return endpoint;
    }
    public CompletionStage<AiTransportResponse> propose(AiTransportRequest request) {
        long remaining = request.input().expiresMonotonicDeadline() - nanoTime.getAsLong();
        if (remaining <= 0) return CompletableFuture.failedFuture(new IllegalStateException("AI timeout"));
        byte[] body = new ModelCodec<>(AiInput.class).encode(request.input());
        if (body.length > 1048576) return CompletableFuture.failedFuture(new IllegalArgumentException("AI input bound"));
        var builder = HttpRequest.newBuilder(endpoint).timeout(Duration.ofNanos(Math.min(remaining, timeout.toNanos())))
                .header("Content-Type", "application/json; charset=utf-8").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        bearer.get().ifPresent(secret -> builder.header("Authorization", "Bearer " + secret));
        var exchange = client.sendAsync(builder.build(), info -> {
            boolean valid = info.statusCode() == 200 && info.headers().firstValue("Content-Type")
                    .map(v -> v.split(";", 2)[0].trim().equalsIgnoreCase("application/json")).orElse(false);
            return new BoundedBody(valid ? request.input().outputLimits().maximumBytes() : 0);
        });
        return track(exchange, nanoTime);
    }
    /** Retains the actual sendAsync handle; cancellation reaches the HTTP exchange. */
    static TransportOperation track(CompletableFuture<HttpResponse<byte[]>> exchange, LongSupplier nanoTime) {
        var result = new TransportOperation(() -> exchange.cancel(true));
        exchange.whenComplete((response, error) -> {
            if (error != null) result.completeExceptionally(new IllegalStateException("AI transport failed"));
            else result.complete(new AiTransportResponse(response.body(), nanoTime.getAsLong()));
        });
        return result;
    }
    public void close() { client.shutdownNow(); }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;
        BoundedBody(int limit) { this.limit = limit; }
        public CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (limit == 0) { subscription.cancel(); body.completeExceptionally(new IllegalArgumentException("AI HTTP response")); }
            else subscription.request(1);
        }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > limit - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(new IllegalArgumentException("AI output bound")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(new IllegalStateException("AI response failed")); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
