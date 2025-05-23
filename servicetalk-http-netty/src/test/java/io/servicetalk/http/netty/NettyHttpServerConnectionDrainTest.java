/*
 * Copyright © 2019, 2021 Apple Inc. and the ServiceTalk project authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.servicetalk.http.netty;

import io.servicetalk.concurrent.api.Completable;
import io.servicetalk.concurrent.api.Publisher;
import io.servicetalk.concurrent.api.Single;
import io.servicetalk.http.api.BlockingHttpClient;
import io.servicetalk.http.api.HttpResponse;
import io.servicetalk.http.api.HttpServerBuilder;
import io.servicetalk.http.api.HttpServiceContext;
import io.servicetalk.http.api.StreamingHttpClient;
import io.servicetalk.http.api.StreamingHttpRequest;
import io.servicetalk.http.api.StreamingHttpResponse;
import io.servicetalk.http.api.StreamingHttpResponseFactory;
import io.servicetalk.http.api.StreamingHttpService;
import io.servicetalk.http.api.StreamingHttpServiceFilter;
import io.servicetalk.http.api.StreamingHttpServiceFilterFactory;
import io.servicetalk.transport.api.ExecutionContext;
import io.servicetalk.transport.api.ServerContext;
import io.servicetalk.transport.netty.internal.AddressUtils;

import io.netty.util.CharsetUtil;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.SocketAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.annotation.Nullable;

import static io.servicetalk.concurrent.api.Publisher.from;
import static io.servicetalk.concurrent.api.Single.succeeded;
import static io.servicetalk.concurrent.internal.DeliberateException.DELIBERATE_EXCEPTION;
import static io.servicetalk.concurrent.internal.FutureUtils.awaitTermination;
import static io.servicetalk.concurrent.internal.TestTimeoutConstants.CI;
import static io.servicetalk.http.api.HttpSerializers.appSerializerUtf8FixLen;
import static io.servicetalk.http.api.HttpSerializers.textSerializerUtf8;
import static io.servicetalk.transport.netty.internal.AddressUtils.serverHostAndPort;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyHttpServerConnectionDrainTest {
    private static final String LARGE_TEXT;

    static {
        int capacity = 1_000_000;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        StringBuilder sb = new StringBuilder(capacity);
        for (int i = 0; i < capacity; i++) {
            sb.append((char) rnd.nextInt(32, 128)); // ASCII
        }
        LARGE_TEXT = sb.toString();
    }

    @Test
    void requestIsAutoDrainedWhenUserFailsToConsume() throws Exception {
        BlockingHttpClient client = null;
        try (ServerContext serverContext = server(true, respondOkWithoutReadingRequest())) {
            client = HttpClients.forSingleAddress(serverHostAndPort(serverContext))
                    .buildBlocking();
            postLargePayloadAndAssertResponseOk(client);
        } finally {
            closeClient(client);
        }
    }

    @Test
    void requestIsAutoDrainedWhenUserFailsToConsumeAndResponseBodyFails() throws Exception {
        AtomicBoolean requestDrained = new AtomicBoolean();
        StreamingHttpServiceFilterFactory filter = (service) -> new StreamingHttpServiceFilter(service) {
            @Override
            public Single<StreamingHttpResponse> handle(HttpServiceContext ctx, StreamingHttpRequest request,
                                                        StreamingHttpResponseFactory responseFactory) {
                request.transformMessageBody(body -> body.afterFinally(() -> requestDrained.set(true)));
                return delegate().handle(ctx, request, responseFactory);
            }
        };
        try (ServerContext serverContext = server(true, respondFailsWithoutReadingRequest(), filter)) {
            try (BlockingHttpClient client = HttpClients.forSingleAddress(serverHostAndPort(serverContext))
                    .buildBlocking()) {
                assertThrows(Exception.class, () -> client.request(client.get("/foo")));
            }
        }
        assertTrue(requestDrained.get());
    }

    @Test
    void requestIsDrainedByUserWithDrainingDisabled() throws Exception {
        try (ServerContext serverContext = server(false, (ctx, request, responseFactory) ->
                request.messageBody().ignoreElements() // User consumes payload (ignoring)
                        .concat(succeeded(responseFactory.ok().payloadBody(from("OK"), appSerializerUtf8FixLen()))));
             BlockingHttpClient client = HttpClients.forSingleAddress(serverHostAndPort(serverContext))
                     .buildBlocking()) {

            postLargePayloadAndAssertResponseOk(client);
        }
    }

    @Test
    void requestIsConsumedByUserWithDrainingEnabled() throws Exception {
        AtomicReference<String> resultRef = new AtomicReference<>();
        try (ServerContext serverContext = server(true, (ctx, request, responseFactory) ->
                request.payloadBody()
                        // User consumes payload and stores for assert ensuring auto-draining doesn't break it
                        .collect(() -> new StringBuilder(LARGE_TEXT.length()),
                                (sb, b) -> sb.append(b.toString(CharsetUtil.US_ASCII)))
                        .map(StringBuilder::toString)
                        .whenOnSuccess(resultRef::set)
                        .toCompletable()
                        .concat(succeeded(responseFactory.ok().payloadBody(from("OK"), appSerializerUtf8FixLen()))));

             BlockingHttpClient client = HttpClients.forSingleAddress(serverHostAndPort(serverContext))
                     .buildBlocking()) {

            postLargePayloadAndAssertResponseOk(client);
            assertThat(resultRef.get(), equalTo(LARGE_TEXT));
        }
    }

    @Disabled("https://github.com/apple/servicetalk/issues/981")
    @Test
    void requestTimesOutWithoutAutoDrainingOrUserConsuming() throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        StreamingHttpClient client = null;
        try (ServerContext serverContext = server(false, respondOkWithoutReadingRequest(latch::countDown))) {

            client = HttpClients.forSingleAddress(serverHostAndPort(serverContext)).buildStreaming();

            client.request(client.post("/").payloadBody(from(LARGE_TEXT), appSerializerUtf8FixLen()))
                    // Subscribe to send the request, don't care about the response since graceful close of the server
                    // will hang until the request is consumed, thus we expect the timeout to hit
                    .ignoreElement().subscribe();

            assertThrows(TimeoutException.class, latch::await); // Wait till the request is received
            // before initiating graceful close of the server
        } finally {
            closeClient(client);
        }
    }

    private static void closeClient(@Nullable final AutoCloseable client) throws Exception {
        if (client != null) {
            client.close();
        }
    }

    private static void postLargePayloadAndAssertResponseOk(final BlockingHttpClient client) throws Exception {
        HttpResponse response = client.request(client.post("/").payloadBody(LARGE_TEXT, textSerializerUtf8()));
        assertThat(response.toStreamingResponse().payloadBody(appSerializerUtf8FixLen())
                        .collect(StringBuilder::new, StringBuilder::append).toFuture().get().toString(), equalTo("OK"));
    }

    private static StreamingHttpService respondOkWithoutReadingRequest(Runnable onRequest) {
        return (ctx, request, responseFactory) -> {
            onRequest.run();
            return succeeded(responseFactory.ok().payloadBody(from("OK"), appSerializerUtf8FixLen()));
        };
    }

    private static StreamingHttpService respondOkWithoutReadingRequest() {
        return respondOkWithoutReadingRequest(() -> { });
    }

    private static StreamingHttpService respondFailsWithoutReadingRequest() {
        return (ctx, request, responseFactory) -> {
            return succeeded(responseFactory.ok().payloadBody(from("OK"), appSerializerUtf8FixLen())
                    .transformPayloadBody(body -> body.concat(Publisher.failed(DELIBERATE_EXCEPTION))));
        };
    }

    private static ServerContext server(boolean autoDrain, StreamingHttpService handler,
                                        StreamingHttpServiceFilterFactory... filters) throws Exception {
        HttpServerBuilder httpServerBuilder = HttpServers.forAddress(AddressUtils.localAddress(0));
        if (!autoDrain) {
            httpServerBuilder = httpServerBuilder.drainRequestPayloadBody(false);
        }
        for (StreamingHttpServiceFilterFactory filter : filters) {
            httpServerBuilder.appendServiceFilter(filter);
        }
        ServerContext serverContext = httpServerBuilder
                .listenStreamingAndAwait(handler);
        return new ServerContext() {
            @Override
            public SocketAddress listenAddress() {
                return serverContext.listenAddress();
            }

            @Override
            public ExecutionContext<?> executionContext() {
                return serverContext.executionContext();
            }

            @Override
            public void acceptConnections(final boolean accept) {
                serverContext.acceptConnections(accept);
            }

            @Override
            public Completable onClose() {
                return serverContext.onClose();
            }

            @Override
            public Completable onClosing() {
                return serverContext.onClosing();
            }

            @Override
            public Completable closeAsync() {
                return serverContext.closeAsync();
            }

            @Override
            public Completable closeAsyncGracefully() {
                return serverContext.closeAsyncGracefully();
            }

            @Override
            public void close() {
                // Without draining the request is expected to hang, don't wait too long unless on CI
                int timeoutSeconds = CI ? 15 : 1;
                awaitTermination(serverContext.closeAsyncGracefully()
                        .timeout(timeoutSeconds, SECONDS)
                        .onErrorResume(t -> serverContext.closeAsync().concat(Completable.failed(t))).toFuture());
            }
        };
    }
}
