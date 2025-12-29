package com.midiraja.midi;

public interface AudioEngine extends AutoCloseable {
    void init(int sampleRate, int channels, int bufferSize) throws Exception;
    void push(short[] pcm);
    int getQueuedFrames();
    int getDeviceLatencyFrames();
    void flush();
    @Override void close();
}
