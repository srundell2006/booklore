package org.booklore.config.security.interceptor;

import org.booklore.config.security.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import java.security.Principal;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * The STOMP-level gate on /ws: only a CONNECT or STOMP frame with a valid JWT authenticates a session, and an
 * authenticated session may subscribe only to its own queues and the broadcast topics, and send only to /app.
 * <p>
 * Messages are built the way {@code StompSubProtocolHandler} hands them to the inbound channel: a session id, the
 * session's user (if any) and a header accessor left mutable, so the interceptor can set the user on a CONNECT.
 */
@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    private static final String VALID_TOKEN = "valid-token";
    private static final String INVALID_TOKEN = "invalid-token";
    private static final Principal USER = new UsernamePasswordAuthenticationToken("alice", null, null);

    @Mock
    private JwtUtils jwtUtils;

    private final MessageChannel channel = mock(MessageChannel.class);
    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        lenient().when(jwtUtils.validateToken(anyString())).thenAnswer(inv -> VALID_TOKEN.equals(inv.getArgument(0)));
        lenient().when(jwtUtils.extractUsername(VALID_TOKEN)).thenReturn("alice");
        interceptor = new WebSocketAuthInterceptor(jwtUtils);
    }

    private static Message<byte[]> frame(StompCommand command, Consumer<StompHeaderAccessor> headers) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("session-1");
        headers.accept(accessor);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    private static Message<byte[]> connect(StompCommand command, String authorization) {
        return frame(command, accessor -> {
            accessor.setAcceptVersion("1.2");
            if (authorization != null) {
                accessor.addNativeHeader("Authorization", authorization);
            }
        });
    }

    private static Message<byte[]> withUser(StompCommand command, String destination) {
        return frame(command, accessor -> {
            accessor.setUser(USER);
            accessor.setSubscriptionId("sub-0");
            if (destination != null) {
                accessor.setDestination(destination);
            }
        });
    }

    // --- CONNECT and STOMP ---

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void connect_withValidToken_authenticatesTheSession(StompCommand command) {
        Message<byte[]> message = connect(command, "Bearer " + VALID_TOKEN);

        Message<?> result = interceptor.preSend(message, channel);

        assertThat(result).isSameAs(message);
        assertThat(SimpMessageHeaderAccessor.getUser(result.getHeaders()))
                .isNotNull()
                .extracting(Principal::getName).isEqualTo("alice");
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void connect_withoutAuthorization_isRejected(StompCommand command) {
        Message<byte[]> message = connect(command, null);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Missing Authorization header");
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void connect_withInvalidToken_isRejected(StompCommand command) {
        Message<byte[]> message = connect(command, "Bearer " + INVALID_TOKEN);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Authorization token");
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"CONNECT", "STOMP"})
    void connect_withEmptyBearerToken_isRejected(StompCommand command) {
        Message<byte[]> message = connect(command, "Bearer ");

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid Authorization token");
    }

    // --- frames of a session that never authenticated ---

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"SUBSCRIBE", "SEND", "UNSUBSCRIBE", "ACK"})
    void frameWithoutUser_isRejected(StompCommand command) {
        // Destinations an authenticated session could use, so only the missing user decides.
        String destination = switch (command) {
            case SUBSCRIBE -> "/user/queue/book-add";
            case SEND -> "/app/x";
            default -> null;
        };
        Message<byte[]> message = frame(command, accessor -> {
            accessor.setSubscriptionId("sub-0");
            if (destination != null) {
                accessor.setDestination(destination);
            }
        });

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Not authenticated");
    }

    // --- SUBSCRIBE of an authenticated session ---

    @ParameterizedTest
    @ValueSource(strings = {"/user/queue/book-add", "/topic/library-health"})
    void subscribe_withUser_toOwnQueueOrBroadcastTopic_isAllowed(String destination) {
        Message<byte[]> message = withUser(StompCommand.SUBSCRIBE, destination);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void subscribe_withUser_toAnotherSessionsResolvedQueue_isRejected() {
        // What /user/queue/book-add resolves to for another session; subscribing to it directly would read its events.
        Message<byte[]> message = withUser(StompCommand.SUBSCRIBE, "/queue/book-add-user" + UUID.randomUUID());

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Subscription not allowed");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"/queue/x", "/app/x", "/user"})
    void subscribe_withUser_toAnyOtherDestination_isRejected(String destination) {
        Message<byte[]> message = withUser(StompCommand.SUBSCRIBE, destination);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Subscription not allowed");
    }

    // --- SEND of an authenticated session ---

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"/topic/library-health", "/queue/x", "/user/x", "/user/bob/queue/book-add"})
    void send_withUser_outsideTheApplicationPrefix_isRejected(String destination) {
        Message<byte[]> message = withUser(StompCommand.SEND, destination);

        assertThatThrownBy(() -> interceptor.preSend(message, channel))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Send not allowed");
    }

    @Test
    void send_withUser_toTheApplicationPrefix_isAllowed() {
        Message<byte[]> message = withUser(StompCommand.SEND, "/app/x");

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @ParameterizedTest
    @EnumSource(value = StompCommand.class, names = {"UNSUBSCRIBE", "ACK"})
    void otherFrames_withUser_areAllowed(StompCommand command) {
        Message<byte[]> message = withUser(command, null);

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    // --- frames that pass without a user ---

    @Test
    void disconnect_withoutUser_passes() {
        Message<byte[]> message = frame(StompCommand.DISCONNECT, accessor -> { });

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }

    @Test
    void heartbeat_withoutUser_passes() {
        // What StompDecoder makes of a bare EOL: no command.
        StompHeaderAccessor accessor = StompHeaderAccessor.createForHeartbeat();
        accessor.setSessionId("session-1");
        accessor.setLeaveMutable(true);
        Message<byte[]> message = MessageBuilder.createMessage(new byte[]{'\n'}, accessor.getMessageHeaders());

        assertThat(interceptor.preSend(message, channel)).isSameAs(message);
    }
}
