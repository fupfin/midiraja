/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.midi;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WavFileWriterTest
{
    @TempDir
    Path tempDir;

    // ── file creation ─────────────────────────────────────────────────────────

    @Test
    void new_file_is_44_bytes_placeholder_header() throws IOException
    {
        Path file = tempDir.resolve("test.wav");
        try (var writer = new WavFileWriter(file.toString(), 44100, 2))
        {
            // just close immediately
        }
        assertEquals(44, Files.size(file), "WAV file with no data must be exactly 44 bytes");
    }

    // ── magic bytes / header structure ────────────────────────────────────────

    @Test
    void header_contains_RIFF_and_WAVE_markers() throws IOException
    {
        Path file = tempDir.resolve("test.wav");
        try (var writer = new WavFileWriter(file.toString(), 44100, 2))
        {}
        byte[] bytes = Files.readAllBytes(file);

        assertEquals('R', bytes[0]);
        assertEquals('I', bytes[1]);
        assertEquals('F', bytes[2]);
        assertEquals('F', bytes[3]);
        assertEquals('W', bytes[8]);
        assertEquals('A', bytes[9]);
        assertEquals('V', bytes[10]);
        assertEquals('E', bytes[11]);
    }

    @Test
    void header_contains_fmt_and_data_chunk_markers() throws IOException
    {
        Path file = tempDir.resolve("test.wav");
        try (var writer = new WavFileWriter(file.toString(), 44100, 2))
        {}
        byte[] bytes = Files.readAllBytes(file);

        assertEquals('f', bytes[12]);
        assertEquals('m', bytes[13]);
        assertEquals('t', bytes[14]);
        assertEquals(' ', bytes[15]);
        assertEquals('d', bytes[36]);
        assertEquals('a', bytes[37]);
        assertEquals('t', bytes[38]);
        assertEquals('a', bytes[39]);
    }

    @Test
    void header_audio_format_is_1_pcm() throws IOException
    {
        Path file = tempDir.resolve("test.wav");
        try (var writer = new WavFileWriter(file.toString(), 44100, 2))
        {}
        byte[] bytes = Files.readAllBytes(file);
        short audioFormat = ByteBuffer.wrap(bytes, 20, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();

        assertEquals(1, audioFormat, "AudioFormat must be 1 (PCM)");
    }

    @Test
    void header_bits_per_sample_is_16() throws IOException
    {
        Path file = tempDir.resolve("test.wav");
        try (var writer = new WavFileWriter(file.toString(), 44100, 2))
        {}
        byte[] bytes = Files.readAllBytes(file);
        short bitsPerSample = ByteBuffer.wrap(bytes, 34, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();

        assertEquals(16, bitsPerSample);
    }

    // ── sample rate and channel count ─────────────────────────────────────────

    @Test
    void header_reflects_stereo_44100Hz() throws IOException
    {
        Path file = tempDir.resolve("stereo.wav");
        try (var writer = new WavFileWriter(file.toString(), 44100, 2))
        {}
        byte[] bytes = Files.readAllBytes(file);

        short channels = ByteBuffer.wrap(bytes, 22, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
        int sampleRate = ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int byteRate = ByteBuffer.wrap(bytes, 28, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

        assertEquals(2, channels);
        assertEquals(44100, sampleRate);
        assertEquals(44100 * 2 * 2, byteRate, "byteRate = sampleRate * channels * 2");
    }

    @Test
    void header_reflects_mono_22050Hz() throws IOException
    {
        Path file = tempDir.resolve("mono.wav");
        try (var writer = new WavFileWriter(file.toString(), 22050, 1))
        {}
        byte[] bytes = Files.readAllBytes(file);

        short channels = ByteBuffer.wrap(bytes, 22, 2).order(ByteOrder.LITTLE_ENDIAN).getShort();
        int sampleRate = ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

        assertEquals(1, channels);
        assertEquals(22050, sampleRate);
    }

    // ── data size after write() + close() ─────────────────────────────────────

    @Test
    void file_size_correct_after_writing_samples() throws IOException
    {
        Path file = tempDir.resolve("data.wav");
        short[] samples = new short[100]; // 100 shorts = 200 bytes
        try (var writer = new WavFileWriter(file.toString(), 44100, 2))
        {
            writer.write(samples);
        }
        // 44 header + 200 data bytes
        assertEquals(244, Files.size(file));
    }

    @Test
    void data_size_field_in_header_updated_on_close() throws IOException
    {
        Path file = tempDir.resolve("data.wav");
        short[] samples = new short[50]; // 50 shorts = 100 bytes
        try (var writer = new WavFileWriter(file.toString(), 44100, 2))
        {
            writer.write(samples);
        }
        byte[] bytes = Files.readAllBytes(file);
        int dataSize = ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

        assertEquals(100, dataSize, "dataSize field in header must be 100 bytes after close()");
    }

    @Test
    void empty_write_dataSize_is_zero() throws IOException
    {
        Path file = tempDir.resolve("empty_data.wav");
        try (var writer = new WavFileWriter(file.toString(), 44100, 2))
        {
            writer.write(new short[0]);
        }
        byte[] bytes = Files.readAllBytes(file);
        int dataSize = ByteBuffer.wrap(bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();

        assertEquals(0, dataSize);
    }

    // ── short → little-endian encoding ───────────────────────────────────────

    @Test
    void short_written_as_little_endian_bytes() throws IOException
    {
        Path file = tempDir.resolve("le.wav");
        // 0x0102 in little-endian: [0x02, 0x01]
        try (var writer = new WavFileWriter(file.toString(), 44100, 1))
        {
            writer.write(new short[] { 0x0102 });
        }
        byte[] bytes = Files.readAllBytes(file);

        assertEquals((byte) 0x02, bytes[44], "LSB first (little-endian)");
        assertEquals((byte) 0x01, bytes[45], "MSB second");
    }

    @Test
    void negative_short_written_correctly() throws IOException
    {
        Path file = tempDir.resolve("neg.wav");
        // -1 as short = 0xFFFF → little-endian: [0xFF, 0xFF]
        try (var writer = new WavFileWriter(file.toString(), 44100, 1))
        {
            writer.write(new short[] { -1 });
        }
        byte[] bytes = Files.readAllBytes(file);

        assertEquals((byte) 0xFF, bytes[44]);
        assertEquals((byte) 0xFF, bytes[45]);
    }

    @Test
    void max_short_32767_written_correctly() throws IOException
    {
        Path file = tempDir.resolve("max.wav");
        // 0x7FFF → little-endian: [0xFF, 0x7F]
        try (var writer = new WavFileWriter(file.toString(), 44100, 1))
        {
            writer.write(new short[] { Short.MAX_VALUE });
        }
        byte[] bytes = Files.readAllBytes(file);

        assertEquals((byte) 0xFF, bytes[44]);
        assertEquals((byte) 0x7F, bytes[45]);
    }
}
