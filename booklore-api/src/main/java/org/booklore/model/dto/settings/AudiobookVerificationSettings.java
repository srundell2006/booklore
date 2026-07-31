package org.booklore.model.dto.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AudiobookVerificationSettings {

    /** Enable or disable automatic audiobook content verification. */
    @Builder.Default
    private boolean enabled = false;

    /** Base URL of the whisper-sidecar service, e.g. http://whisper-sidecar:8080 */
    private String whisperUrl;

    /** Number of seconds to transcribe from the start of each audiobook. */
    @Builder.Default
    private int excerptSeconds = 90;

    /**
     * Ollama model used for title/author extraction from the transcript.
     * Defaults to the global Ollama model when blank.
     */
    @Builder.Default
    private String ollamaModel = "llama3.1:8b";

    /**
     * Optional Ollama base URL override (e.g. http://ollama:11434).
     * Falls back to the application-level ollama.base-url when blank.
     */
    private String ollamaUrl;
}
