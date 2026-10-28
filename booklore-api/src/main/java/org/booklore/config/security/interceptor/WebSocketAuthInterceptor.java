package org.booklore.config.security.interceptor;

import org.booklore.config.security.JwtUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Authenticates a STOMP session by the JWT in its CONNECT frame, and lets every later frame through only for a
 * session that did so.
 * <p>
 * The handshake on /ws is open, so this is the only check: a frame sent without a CONNECT, or a STOMP frame (which
 * Spring takes as a CONNECT), must not reach the broker unauthenticated. A client may subscribe only to its own
 * queues (/user/...) and the broadcast topics, and may not send to the broker's destinations itself.
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 99)
@RequiredArgsConstructor
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final String USER_DESTINATION_PREFIX = "/user/";
    private static final String TOPIC_PREFIX = "/topic/";
    private static final String APPLICATION_PREFIX = "/app/";

    private final JwtUtils jwtUtils;

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null || accessor.getCommand() == null) {
            return message;
        }

        StompCommand command = accessor.getCommand();
        if (StompCommand.CONNECT.equals(command) || StompCommand.STOMP.equals(command)) {
            List<String> authHeaders = accessor.getNativeHeader("Authorization");

            if (authHeaders == null || authHeaders.isEmpty()) {
                log.debug("WebSocket connection rejected: No Authorization header");
                throw new IllegalArgumentException("Missing Authorization header");
            }

            String token = authHeaders.getFirst().replace("Bearer ", "");
            Authentication auth = authenticateToken(token);

            if (auth == null) {
                log.debug("WebSocket connection rejected: Invalid token");
                throw new IllegalArgumentException("Invalid Authorization token");
            }

            accessor.setUser(auth);
            log.debug("WebSocket authentication successful for user: {}", auth.getName());
            return message;
        }

        if (StompCommand.DISCONNECT.equals(command)) {
            return message;
        }

        if (accessor.getUser() == null) {
            log.debug("WebSocket {} rejected: session is not authenticated", command);
            throw new IllegalArgumentException("Not authenticated");
        }

        String destination = accessor.getDestination();
        if (StompCommand.SUBSCRIBE.equals(command) && !isSubscribable(destination)) {
            log.debug("WebSocket subscription to {} rejected for user: {}", destination, accessor.getUser().getName());
            throw new IllegalArgumentException("Subscription not allowed");
        }
        if (StompCommand.SEND.equals(command) && (destination == null || !destination.startsWith(APPLICATION_PREFIX))) {
            log.debug("WebSocket send to {} rejected for user: {}", destination, accessor.getUser().getName());
            throw new IllegalArgumentException("Send not allowed");
        }

        return message;
    }

    private boolean isSubscribable(String destination) {
        return destination != null
                && (destination.startsWith(USER_DESTINATION_PREFIX) || destination.startsWith(TOPIC_PREFIX));
    }

    private Authentication authenticateToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }
        try {
            if (jwtUtils.validateToken(token)) {
                String username = jwtUtils.extractUsername(token);
                if (username != null && !username.trim().isEmpty()) {
                    return new UsernamePasswordAuthenticationToken(username, null, null);
                }
            }
        } catch (Exception e) {
            log.debug("Token authentication failed", e);
        }
        return null;
    }
}
