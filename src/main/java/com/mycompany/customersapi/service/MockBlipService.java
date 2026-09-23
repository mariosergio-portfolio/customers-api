package com.mycompany.customersapi.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Generates a mock audio pronunciation for a customer name.
 *
 * Each letter maps to a distinct frequency; the result is a raw PCM WAV file
 * produced entirely in-memory with no external dependencies.
 *
 * TODO: replace with a real TTS engine (e.g. Google Cloud TTS, AWS Polly,
 *       OpenAI TTS) once the synthesis pipeline is in place.
 */
@Service
@Slf4j
public class MockBlipService {

    private static final int SAMPLE_RATE      = 22_050;   // Hz
    private static final double LETTER_DURATION = 0.13;   // seconds per letter
    private static final double PAUSE_DURATION  = 0.04;   // silence between letters
    private static final double AMPLITUDE       = 0.35;   // 0..1

    // ── frequencies (Hz) assigned to each letter a-z ────────────────────────
    private static final double[] LETTER_FREQ = {
            261.63,  // a – C4
            293.66,  // b – D4
            329.63,  // c – E4
            349.23,  // d – F4
            392.00,  // e – G4
            440.00,  // f – A4
            493.88,  // g – B4
            523.25,  // h – C5
            587.33,  // i – D5
            659.25,  // j – E5
            698.46,  // k – F5
            783.99,  // l – G5
            880.00,  // m – A5
            987.77,  // n – B5
            1046.50,  // o – C6
            1174.66,  // p – D6
            1318.51,  // q – E6
            1396.91,  // r – F6
            1567.98,  // s – G6
            1760.00,  // t – A6
            1975.53,  // u – B6
            2093.00,  // v – C7
            2349.32,  // w – D7
            2637.02,  // x – E7
            2793.83,  // y – F7
            3135.96,  // z – G7
    };

    /**
     * Generates a WAV audio clip that "pronounces" the given name by playing
     * a sine-wave tone for each alphabetic character, with short silences
     * between letters and a 120ms padding at the end.
     *
     * @param name the customer name to pronounce
     * @return WAV file bytes (audio/wav)
     */
    public byte[] generateWav(String name) {
        log.debug("Generating mock pronunciation WAV for name='{}'", name);

        // Collect all PCM samples
        ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        String normalized = name == null ? "unknown" : name.toLowerCase();

        for (char ch : normalized.toCharArray()) {
            if (Character.isLetter(ch)) {
                int idx = Math.min(ch - 'a', LETTER_FREQ.length - 1);
                double freq = LETTER_FREQ[idx];
                appendTone(pcm, freq, LETTER_DURATION);
                appendSilence(pcm, PAUSE_DURATION);
            } else if (ch == ' ') {
                // word gap — slightly longer silence
                appendSilence(pcm, PAUSE_DURATION * 3);
            }
        }
        // trailing silence
        appendSilence(pcm, 0.12);

        byte[] pcmBytes = pcm.toByteArray();
        return buildWav(pcmBytes);
    }

    // ── PCM helpers ──────────────────────────────────────────────────────────

    private void appendTone(ByteArrayOutputStream out, double freqHz, double durationSec) {
        int samples = (int) (SAMPLE_RATE * durationSec);
        for (int i = 0; i < samples; i++) {
            // sine with linear fade-in/out envelope (first and last 10%)
            double t        = (double) i / SAMPLE_RATE;
            double envelope = envelope(i, samples);
            double sample   = AMPLITUDE * envelope * Math.sin(2.0 * Math.PI * freqHz * t);
            writeInt16Le(out, (short) (sample * Short.MAX_VALUE));
        }
    }

    private void appendSilence(ByteArrayOutputStream out, double durationSec) {
        int samples = (int) (SAMPLE_RATE * durationSec);
        for (int i = 0; i < samples; i++) {
            writeInt16Le(out, (short) 0);
        }
    }

    /** Simple trapezoidal envelope: fade in/out over the first/last 10% of samples. */
    private double envelope(int i, int total) {
        int fadeLen = Math.max(1, total / 10);
        if (i < fadeLen)             return (double) i / fadeLen;
        if (i > total - fadeLen)     return (double) (total - i) / fadeLen;
        return 1.0;
    }

    // ── WAV header builder ───────────────────────────────────────────────────

    /**
     * Wraps raw 16-bit mono PCM bytes in a standard RIFF/WAV header.
     * Output is always: 16-bit, mono, {@value #SAMPLE_RATE} Hz.
     */
    private byte[] buildWav(byte[] pcmData) {
        int channels       = 1;
        int bitsPerSample  = 16;
        int byteRate       = SAMPLE_RATE * channels * bitsPerSample / 8;
        int blockAlign     = channels * bitsPerSample / 8;
        int dataChunkSize  = pcmData.length;
        int riffChunkSize  = 36 + dataChunkSize;

        try {
            ByteArrayOutputStream wav = new ByteArrayOutputStream(44 + dataChunkSize);

            // RIFF header
            wav.write("RIFF".getBytes());
            writeInt32Le(wav, riffChunkSize);
            wav.write("WAVE".getBytes());

            // fmt  sub-chunk
            wav.write("fmt ".getBytes());
            writeInt32Le(wav, 16);               // sub-chunk size (PCM)
            writeInt16Le(wav, (short) 1);        // AudioFormat = PCM
            writeInt16Le(wav, (short) channels);
            writeInt32Le(wav, SAMPLE_RATE);
            writeInt32Le(wav, byteRate);
            writeInt16Le(wav, (short) blockAlign);
            writeInt16Le(wav, (short) bitsPerSample);

            // data sub-chunk
            wav.write("data".getBytes());
            writeInt32Le(wav, dataChunkSize);
            wav.write(pcmData);

            return wav.toByteArray();
        } catch (IOException e) {
            // ByteArrayOutputStream never throws IOException
            throw new IllegalStateException("Unexpected IO error building WAV", e);
        }
    }

    private void writeInt32Le(ByteArrayOutputStream out, int value) {
        ByteBuffer buf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(value);
        out.writeBytes(buf.array());
    }

    private void writeInt16Le(ByteArrayOutputStream out, short value) {
        ByteBuffer buf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort(value);
        out.writeBytes(buf.array());
    }
}
