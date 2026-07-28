package org.booklore.service.conversion;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.booklore.exception.ApiError;
import org.booklore.model.dto.settings.EbookConversionSettings;
import org.booklore.model.entity.BookEntity;
import org.booklore.repository.BookRepository;
import org.booklore.service.appsettings.AppSettingService;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * Converts a book to another format via the ebook-converter sidecar.
 *
 * Ebooks are small (typically a few MB), so unlike the audiobook merge this
 * uses a plain multipart upload/download rather than a shared staging folder.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EbookConversionService {

    private final BookRepository bookRepository;
    private final AppSettingService appSettingService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public EbookConversionSettings getSettings() {
        EbookConversionSettings settings = appSettingService.getAppSettings().getEbookConversionSettings();
        return settings != null ? settings : EbookConversionSettings.builder().build();
    }

    public boolean testConnection(EbookConversionSettings settings) {
        try {
            HttpResponse<String> response = httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl(settings) + "/health"))
                            .timeout(Duration.ofSeconds(15))
                            .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                log.info("ebook-converter health: {}", truncate(response.body()));
                return true;
            }
            log.warn("ebook-converter health check failed: HTTP {} - {}",
                    response.statusCode(), truncate(response.body()));
            return false;
        } catch (Exception e) {
            log.warn("ebook-converter health check failed: {}: {}",
                    e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /**
     * Converts the book's primary file and writes the result alongside it.
     *
     * @return the path of the converted file
     */
    public Path convertBook(long bookId, String targetFormat) {
        EbookConversionSettings settings = getSettings();
        if (!settings.isEnabled()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException("Ebook conversion is not enabled");
        }

        BookEntity book = bookRepository.findById(bookId)
                .orElseThrow(() -> ApiError.BOOK_NOT_FOUND.createException(bookId));
        Path source = book.getFullFilePath();
        if (source == null || !Files.isRegularFile(source)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "Book has no single source file to convert");
        }

        String target = (targetFormat == null || targetFormat.isBlank()
                ? settings.getDefaultTargetFormat() : targetFormat)
                .toLowerCase(Locale.ROOT).replace(".", "");
        String sourceName = source.getFileName().toString();
        String sourceExt = extensionOf(sourceName);
        if (target.equals(sourceExt)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "Book is already in " + target + " format");
        }

        Path destination = source.resolveSibling(stripExtension(sourceName) + "." + target);
        if (Files.exists(destination)) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "A " + target + " version already exists: " + destination.getFileName());
        }

        log.info("Converting book {} ({} -> {})", bookId, sourceExt, target);
        byte[] converted = requestConversion(settings, source, sourceName, target);

        Path temp = null;
        try {
            temp = Files.createTempFile("booklore-convert-", "." + target);
            Files.write(temp, converted);
            Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            temp = null;
            log.info("Converted book {} -> {} ({} bytes)", bookId, destination, converted.length);
            return destination;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to write converted file: " + e.getMessage(), e);
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (Exception ignored) {
                    // best effort
                }
            }
        }
    }

    private byte[] requestConversion(EbookConversionSettings settings, Path source,
                                     String sourceName, String target) {
        String boundary = "----BookLore" + UUID.randomUUID().toString().replace("-", "");
        try {
            byte[] body = buildMultipartBody(boundary, source, sourceName, target);
            HttpResponse<byte[]> response = httpClient.send(HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl(settings) + "/convert"))
                            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                            .timeout(Duration.ofMinutes(Math.max(1, settings.getTimeoutMinutes())))
                            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                String detail = new String(response.body(), StandardCharsets.UTF_8);
                throw new IllegalStateException("Conversion failed (HTTP "
                        + response.statusCode() + "): " + truncate(detail));
            }
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Conversion interrupted", e);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Conversion request failed: " + e.getMessage(), e);
        }
    }

    private byte[] buildMultipartBody(String boundary, Path source, String sourceName, String target)
            throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeAscii(out, "--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"file\"; filename=\""
                + sourceName.replace("\"", "'") + "\"\r\n");
        writeAscii(out, "Content-Type: application/octet-stream\r\n\r\n");
        out.write(Files.readAllBytes(source));
        writeAscii(out, "\r\n--" + boundary + "\r\n");
        writeAscii(out, "Content-Disposition: form-data; name=\"target\"\r\n\r\n");
        writeAscii(out, target);
        writeAscii(out, "\r\n--" + boundary + "--\r\n");
        return out.toByteArray();
    }

    private void writeAscii(OutputStream out, String value) throws java.io.IOException {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private String baseUrl(EbookConversionSettings settings) {
        String url = settings.getServiceUrl();
        if (url == null || url.isBlank()) {
            throw ApiError.GENERIC_BAD_REQUEST.createException(
                    "Ebook conversion service URL is not configured");
        }
        url = url.trim();
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private String truncate(String value) {
        if (value == null) return "";
        return value.length() > 400 ? value.substring(0, 400) : value;
    }
}
