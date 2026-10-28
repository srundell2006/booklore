package org.booklore.service.metadata.parser;

import com.sun.net.httpserver.HttpServer;
import org.booklore.model.dto.Book;
import org.booklore.model.dto.BookMetadata;
import org.booklore.model.dto.request.FetchMetadataRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reasoning models such as qwen3 write a long hidden chain of thought before every answer unless
 * the request turns it off, which made each metadata call 15-20 times slower than it needs to be.
 */
class OllamaThinkSwitchTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void metadataRequest_turnsThinkingOff() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"publisher\\\":\\\"Tor\\\"}\"}}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();

        ObjectMapper objectMapper = new ObjectMapper();
        OllamaMetadataParser parser = new OllamaMetadataParser(objectMapper);
        parser.configure("http://127.0.0.1:" + server.getAddress().getPort() + "/", "qwen3.5:9b");

        Book book = Book.builder().id(7L).metadata(BookMetadata.builder().title("The Fifth Season").build()).build();
        BookMetadata result = parser.fetchTopMetadata(book, FetchMetadataRequest.builder().build());

        assertThat(result).isNotNull();
        assertThat(result.getPublisher()).isEqualTo("Tor");
        JsonNode body = objectMapper.readTree(requestBody.get());
        assertThat(body.get("think").isBoolean()).isTrue();
        assertThat(body.get("think").asBoolean()).isFalse();
        assertThat(body.get("model").asText()).isEqualTo("qwen3.5:9b");
        assertThat(body.get("stream").asBoolean()).isFalse();
        assertThat(body.get("format").asText()).isEqualTo("json");
    }

    @Test
    void everyOllamaChatRequest_turnsThinkingOff() throws IOException {
        Path mainSources = Path.of("src/main/java");
        List<Path> callers;
        try (Stream<Path> files = Files.walk(mainSources)) {
            callers = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> read(p).contains("/api/chat"))
                    .toList();
        }

        assertThat(callers).as("classes that call Ollama's /api/chat").isNotEmpty();
        assertThat(callers).allSatisfy(caller -> assertThat(read(caller))
                .as("%s sends an Ollama chat request without \"think\": false", caller.getFileName())
                .contains(".put(\"think\", false)"));
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
