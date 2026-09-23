package com.planet.customersapi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.polly.PollyClient;
import software.amazon.awssdk.services.polly.model.*;

import jakarta.annotation.PostConstruct;
import java.io.IOException;

/**
 * Synthesizes customer-name audio via AWS Polly (Neural TTS).
 *
 * Credentials are resolved in priority order:
 *   1. Explicit access-key / secret-key in application.yml  (dev convenience)
 *   2. AWS default chain (env vars, ~/.aws/credentials, IAM role) when the
 *      keys are left blank — preferred for production / ECS deployments.
 *
 * The response is returned as raw MP3 bytes (audio/mpeg).
 */
@Service
@Slf4j
public class PronounceService {

    @Value("${aws.polly.region:us-east-1}")
    private String region;

    @Value("${aws.polly.voice-id:Joanna}")
    private String voiceId;

    @Value("${aws.polly.access-key:}")
    private String accessKey;

    @Value("${aws.polly.secret-key:}")
    private String secretKey;

    private PollyClient pollyClient;

    @PostConstruct
    void init() {
        var builder = PollyClient.builder()
                .region(Region.of(region));

        if (accessKey != null && !accessKey.isBlank()
                && secretKey != null && !secretKey.isBlank()) {
            log.info("AWS Polly: using static credentials from configuration");
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKey, secretKey)));
        } else {
            log.info("AWS Polly: using default credentials chain (env / profile / IAM role)");
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }

        pollyClient = builder.build();
        log.info("AWS Polly client initialised — region={}, voice={}", region, voiceId);
    }

    /**
     * Calls AWS Polly to synthesize the given name and returns the MP3 bytes.
     *
     * @param name         customer name to pronounce
     * @param languageCode BCP-47 language code accepted by Polly (e.g. "en-US", "pt-BR")
     * @return MP3 audio bytes (audio/mpeg)
     * @throws PronounceException if Polly returns an error or the stream cannot be read
     */
    public byte[] synthesize(String name, String languageCode) {
        String text = (name == null || name.isBlank()) ? "unknown" : name.trim();
        String lang = (languageCode == null || languageCode.isBlank()) ? "en-US" : languageCode.trim();
        log.debug("Calling AWS Polly: voice={}, language={}, text='{}'", voiceId, lang, text);

        SynthesizeSpeechRequest request = SynthesizeSpeechRequest.builder()
                .text(text)
                .voiceId(VoiceId.fromValue(voiceId))
                .languageCode(LanguageCode.fromValue(lang))
                .outputFormat(OutputFormat.MP3)
                .engine("neural")
                .build();

        try (ResponseInputStream<SynthesizeSpeechResponse> stream =
                     pollyClient.synthesizeSpeech(request)) {

            byte[] audio = stream.readAllBytes();
            log.debug("AWS Polly returned {} bytes for name='{}'", audio.length, text);
            return audio;

        } catch (PollyException e) {
            log.error("AWS Polly error synthesizing '{}': {}", text, e.getMessage());
            throw new PronounceException("Polly synthesis failed: " + e.getMessage(), e);
        } catch (IOException e) {
            log.error("Failed to read Polly audio stream for '{}': {}", text, e.getMessage());
            throw new PronounceException("Failed to read Polly audio stream", e);
        }
    }

    // ── exception ────────────────────────────────────────────────────────────

    public static class PronounceException extends RuntimeException {
        public PronounceException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
