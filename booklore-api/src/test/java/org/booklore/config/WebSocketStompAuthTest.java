package org.booklore.config;

import jakarta.servlet.ServletRegistration;
import org.booklore.config.security.JwtUtils;
import org.booklore.config.security.interceptor.WebSocketAuthInterceptor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.autoconfigure.WebSocketTomcatWebServerFactoryCustomizer;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The STOMP gate on /ws end to end: a real Tomcat, the real {@link WebSocketConfig} and
 * {@link WebSocketAuthInterceptor}, and raw STOMP text frames from a plain JDK websocket client, so every frame byte
 * is the test's own. /ws is reachable without the forward-auth proxy, so this gate is all that stands between an
 * anonymous client and the users' queues and the broadcast topics.
 * <p>
 * Only {@link JwtUtils} is stubbed: {@code valid-<name>} is a valid token for {@code <name>}.
 * <p>
 * A rejected frame closes the session with a STOMP ERROR, and an accepted SUBSCRIBE shows up in the broker's
 * subscription registry, so each negative case first waits for one of the two before anything is broadcast. That
 * way the old interceptor fails these tests every time rather than by timing.
 */
class WebSocketStompAuthTest {

    private static final String TOKEN_PREFIX = "valid-";
    private static final String LIBRARY_HEALTH = "/topic/library-health";
    private static final String BOOK_ADD = "/queue/book-add";
    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    /** How long a client must stay without a frame it should never get, after a client that should got it. */
    private static final Duration SETTLE = Duration.ofMillis(500);

    private static WebServer webServer;
    private static AnnotationConfigWebApplicationContext context;
    private static HttpClient httpClient;
    private static SimpMessagingTemplate messagingTemplate;
    private static SimpUserRegistry userRegistry;
    private static SimpleBrokerMessageHandler broker;

    private final List<StompClient> clients = new ArrayList<>();
    private String alice;
    private String bob;

    @Configuration
    @Import({WebSocketConfig.class, WebSocketAuthInterceptor.class})
    static class StompConfig {
        @Bean
        JwtUtils jwtUtils() {
            JwtUtils jwtUtils = mock(JwtUtils.class);
            when(jwtUtils.validateToken(anyString()))
                    .thenAnswer(inv -> inv.<String>getArgument(0).startsWith(TOKEN_PREFIX));
            when(jwtUtils.extractUsername(anyString()))
                    .thenAnswer(inv -> inv.<String>getArgument(0).substring(TOKEN_PREFIX.length()));
            return jwtUtils;
        }
    }

    @BeforeAll
    static void startServer() {
        TomcatServletWebServerFactory factory = new TomcatServletWebServerFactory(0);
        // What the application's auto-configuration does, so Tomcat sets up its websocket server container.
        new WebSocketTomcatWebServerFactoryCustomizer().customize(factory);
        context = new AnnotationConfigWebApplicationContext();
        context.register(StompConfig.class);
        webServer = factory.getWebServer(servletContext -> {
            ServletRegistration.Dynamic dispatcher = servletContext.addServlet("dispatcher", new DispatcherServlet(context));
            dispatcher.addMapping("/");
            dispatcher.setLoadOnStartup(1);
        });
        webServer.start();
        messagingTemplate = context.getBean(SimpMessagingTemplate.class);
        userRegistry = context.getBean(SimpUserRegistry.class);
        broker = context.getBean(SimpleBrokerMessageHandler.class);
        httpClient = HttpClient.newHttpClient();
    }

    @AfterAll
    static void stopServer() {
        httpClient.close();
        webServer.stop();
        context.close();
    }

    @BeforeEach
    void setUp() {
        // Fresh names per test, so a session from an earlier test never answers for this one's user.
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        alice = "alice-" + suffix;
        bob = "bob-" + suffix;
    }

    @AfterEach
    void closeClients() {
        clients.forEach(StompClient::close);
    }

    // --- a. the legitimate path ---

    @Test
    void authenticatedClient_receivesMessagesSentToItsUser() {
        StompClient client = connect(alice);
        String sessionId = sessionIdOf(alice);
        client.send(subscribe("sub-0", "/user/queue/book-add"));
        awaitSubscriber(BOOK_ADD + "-user" + sessionId, sessionId);

        messagingTemplate.convertAndSendToUser(alice, BOOK_ADD, "book-42");

        assertThat(client.awaitMessageWithBody("book-42"))
                .contains("destination:/user/queue/book-add\n", "subscription:sub-0\n");
    }

    @Test
    void authenticatedClient_receivesBroadcastTopic() {
        StompClient client = connect(alice);
        String sessionId = sessionIdOf(alice);
        client.send(subscribe("sub-0", LIBRARY_HEALTH));
        awaitSubscriber(LIBRARY_HEALTH, sessionId);

        messagingTemplate.convertAndSend(LIBRARY_HEALTH, "health-a");

        assertThat(client.awaitMessageWithBody("health-a"))
                .contains("destination:" + LIBRARY_HEALTH + "\n", "subscription:sub-0\n");
    }

    // --- b. a STOMP frame (which Spring takes as a CONNECT) without a token ---

    @Test
    void stompFrameWithoutToken_isRejected_andReceivesNoBroadcast() {
        StompClient subscriber = subscribedToLibraryHealth(alice);
        Set<String> subscribersBefore = subscribersOf(LIBRARY_HEALTH);

        StompClient anonymous = open();
        anonymous.send(frame("STOMP", "accept-version:1.2", "host:localhost", "heart-beat:0,0"));
        await().atMost(TIMEOUT).until(() -> anonymous.isClosed() || anonymous.hasFrame("CONNECTED"));
        anonymous.trySend(subscribe("sub-0", LIBRARY_HEALTH));
        awaitRejectedOrSubscribed(anonymous, LIBRARY_HEALTH, subscribersBefore);

        messagingTemplate.convertAndSend(LIBRARY_HEALTH, "health-b");
        subscriber.awaitMessageWithBody("health-b");
        settle();

        assertThat(anonymous.frames()).as("frames to the anonymous client").noneMatch(f -> f.startsWith("MESSAGE"));
        assertThat(anonymous.frames()).as("frames to the anonymous client")
                .noneMatch(f -> f.startsWith("CONNECTED"))
                .anyMatch(f -> f.startsWith("ERROR"));
    }

    // --- c. a SUBSCRIBE without any CONNECT ---

    @Test
    void subscribeWithoutConnect_receivesNoBroadcast() {
        StompClient subscriber = subscribedToLibraryHealth(alice);
        Set<String> subscribersBefore = subscribersOf(LIBRARY_HEALTH);

        StompClient anonymous = open();
        anonymous.send(subscribe("sub-0", LIBRARY_HEALTH));
        awaitRejectedOrSubscribed(anonymous, LIBRARY_HEALTH, subscribersBefore);

        messagingTemplate.convertAndSend(LIBRARY_HEALTH, "health-c");
        subscriber.awaitMessageWithBody("health-c");
        settle();

        assertThat(anonymous.frames()).as("frames to the anonymous client").noneMatch(f -> f.startsWith("MESSAGE"));
        assertThat(anonymous.frames()).as("frames to the anonymous client").anyMatch(f -> f.startsWith("ERROR"));
    }

    // --- d. an authenticated client subscribing to another session's resolved queue ---

    @Test
    void authenticatedClient_cannotSubscribeToAnotherSessionsQueue() {
        StompClient victim = connect(bob);
        String victimSession = sessionIdOf(bob);
        victim.send(subscribe("sub-0", "/user/queue/book-add"));
        // The broker destination /user/queue/book-add resolves to for the victim's session.
        String victimQueue = BOOK_ADD + "-user" + victimSession;
        awaitSubscriber(victimQueue, victimSession);

        StompClient attacker = connect(alice);
        String attackerSession = sessionIdOf(alice);
        attacker.send(subscribe("sub-0", victimQueue));
        await().atMost(TIMEOUT).until(() -> attacker.isClosed() || subscribersOf(victimQueue).contains(attackerSession));

        messagingTemplate.convertAndSendToUser(bob, BOOK_ADD, "book-for-bob");
        victim.awaitMessageWithBody("book-for-bob");
        settle();

        assertThat(attacker.frames()).as("frames to the attacker").noneMatch(f -> f.startsWith("MESSAGE"));
        assertThat(attacker.frames()).as("frames to the attacker").anyMatch(f -> f.startsWith("ERROR"));
    }

    // --- e. an authenticated client sending to a broadcast topic ---

    @Test
    void authenticatedClient_cannotSendToBroadcastTopic() {
        StompClient victim = subscribedToLibraryHealth(bob);
        StompClient attacker = connect(alice);

        attacker.send(frameWithBody("SEND", "forged-health",
                "destination:" + LIBRARY_HEALTH, "content-type:text/plain"));
        await().atMost(TIMEOUT).until(() -> attacker.isClosed() || victim.hasMessageWithBody("forged-health"));

        messagingTemplate.convertAndSend(LIBRARY_HEALTH, "genuine-health");
        victim.awaitMessageWithBody("genuine-health");
        settle();

        assertThat(victim.frames()).as("frames to the subscribed client")
                .noneMatch(f -> f.startsWith("MESSAGE") && f.endsWith("\n\nforged-health\0"));
        assertThat(attacker.frames()).as("frames to the attacker").anyMatch(f -> f.startsWith("ERROR"));
    }

    // --- helpers ---

    private StompClient open() {
        StompClient client = new StompClient();
        client.webSocket = httpClient.newWebSocketBuilder()
                .subprotocols("v12.stomp")
                .buildAsync(URI.create("ws://localhost:" + webServer.getPort() + "/ws"), client)
                .orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .join();
        clients.add(client);
        return client;
    }

    /** A CONNECT the way the UI sends it: a bearer token in the Authorization header. */
    private StompClient connect(String username) {
        StompClient client = open();
        client.send(frame("CONNECT", "accept-version:1.2", "host:localhost", "heart-beat:0,0",
                "Authorization:Bearer " + TOKEN_PREFIX + username));
        await().atMost(TIMEOUT).until(() -> client.hasFrame("CONNECTED") || client.isClosed());
        assertThat(client.frames()).as("frames to " + username).anyMatch(f -> f.startsWith("CONNECTED"));
        return client;
    }

    private StompClient subscribedToLibraryHealth(String username) {
        StompClient client = connect(username);
        String sessionId = sessionIdOf(username);
        client.send(subscribe("sub-0", LIBRARY_HEALTH));
        awaitSubscriber(LIBRARY_HEALTH, sessionId);
        return client;
    }

    /** The server-side id of the user's only session; the user destinations resolve to queues named after it. */
    private static String sessionIdOf(String username) {
        await().atMost(TIMEOUT).until(() -> userRegistry.getUser(username) != null);
        SimpUser user = userRegistry.getUser(username);
        assertThat(user.getSessions()).hasSize(1);
        SimpSession session = user.getSessions().iterator().next();
        return session.getId();
    }

    /** The ids of the sessions the broker would deliver a message to the destination to. */
    private static Set<String> subscribersOf(String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        accessor.setDestination(destination);
        Message<byte[]> probe = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        return new HashSet<>(broker.getSubscriptionRegistry().findSubscriptions(probe).keySet());
    }

    private static void awaitSubscriber(String destination, String sessionId) {
        await().atMost(TIMEOUT).until(() -> subscribersOf(destination).contains(sessionId));
    }

    /** Until the server has decided on the client's SUBSCRIBE: either closed the session, or registered a subscriber. */
    private static void awaitRejectedOrSubscribed(StompClient client, String destination, Set<String> subscribersBefore) {
        await().atMost(TIMEOUT).until(() -> client.isClosed() || !subscribersBefore.containsAll(subscribersOf(destination)));
    }

    private static void settle() {
        await().pollDelay(SETTLE).atMost(SETTLE.multipliedBy(2)).until(() -> true);
    }

    private static String subscribe(String id, String destination) {
        return frame("SUBSCRIBE", "id:" + id, "destination:" + destination);
    }

    private static String frame(String command, String... headers) {
        return frameWithBody(command, "", headers);
    }

    private static String frameWithBody(String command, String body, String... headers) {
        StringBuilder frame = new StringBuilder(command).append('\n');
        for (String header : headers) {
            frame.append(header).append('\n');
        }
        return frame.append('\n').append(body).append('\0').toString();
    }

    /** A websocket that records every STOMP frame the server sends, heartbeats aside. */
    private static final class StompClient implements WebSocket.Listener {

        private final List<String> frames = new CopyOnWriteArrayList<>();
        private final StringBuilder partial = new StringBuilder();
        private final CompletableFuture<Void> closed = new CompletableFuture<>();
        private WebSocket webSocket;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String frame = partial.toString();
                partial.setLength(0);
                if (!frame.isBlank()) {
                    frames.add(frame);
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.complete(null);
        }

        void send(String frame) {
            webSocket.sendText(frame, true).orTimeout(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
        }

        /** Sends unless the server already closed the session; a rejected client may be gone by now. */
        void trySend(String frame) {
            try {
                send(frame);
            } catch (RuntimeException ignored) {
                // closed by the server
            }
        }

        List<String> frames() {
            return List.copyOf(frames);
        }

        boolean isClosed() {
            return closed.isDone();
        }

        boolean hasFrame(String command) {
            return frames.stream().anyMatch(f -> f.startsWith(command + "\n"));
        }

        boolean hasMessageWithBody(String body) {
            return frames.stream().anyMatch(f -> f.startsWith("MESSAGE\n") && f.endsWith("\n\n" + body + "\0"));
        }

        String awaitMessageWithBody(String body) {
            await().atMost(TIMEOUT).until(() -> hasMessageWithBody(body));
            return frames.stream()
                    .filter(f -> f.startsWith("MESSAGE\n") && f.endsWith("\n\n" + body + "\0"))
                    .findFirst()
                    .orElseThrow();
        }

        void close() {
            try {
                if (!isClosed()) {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "").orTimeout(1, TimeUnit.SECONDS).join();
                }
            } catch (RuntimeException ignored) {
                // already closing
            } finally {
                webSocket.abort();
            }
        }
    }
}
