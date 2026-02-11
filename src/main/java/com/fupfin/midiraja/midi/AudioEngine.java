package com.fupfin.midiraja.midi;

/**
 * Abstraction for an audio output engine.
 * Decouples MIDI synthesis providers from the native FFM/JNI audio drivers (DIP principle).
 */
public interface AudioEngine extends AutoCloseable
{
    /**
     * Initializes the audio device with the specified parameters.
     * @param sampleRate Sampling rate in Hz (e.g., 44100).
     * @param channels Number of audio channels (1 for Mono, 2 for Stereo).
     * @param bufferSize Size of the internal audio buffer in frames.
     */
    void init(int sampleRate, int channels, int bufferSize) throws Exception;
    /**
     * Pushes a buffer of PCM samples to the audio device.
     * @param pcm Array of short PCM samples.
     */
    int push(short[] pcm);
    int push(short[] pcm, int offset, int length);
    int getBufferCapacityFrames();
    int getQueuedFrames();
    int getDeviceLatencyFrames();
    void flush();
    @Override void close();
}
