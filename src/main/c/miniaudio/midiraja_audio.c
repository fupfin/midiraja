#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"

// A simple ring buffer structure to hold PCM data produced by Munt
// until miniaudio's data callback is ready to consume it.
typedef struct {
    short* buffer;
    int capacity;
    int head;
    int tail;
    int count;
    ma_mutex lock;
    ma_event spaceAvailableEvent;
} RingBuffer;

typedef struct {
    ma_device device;
    RingBuffer rb;
    int sampleRate;
    int channels;
} AudioEngineContext;

// This callback is fired by miniaudio when the OS audio driver needs more PCM data.
void data_callback(ma_device* pDevice, void* pOutput, const void* pInput, ma_uint32 frameCount) {
    AudioEngineContext* ctx = (AudioEngineContext*)pDevice->pUserData;
    short* out = (short*)pOutput;
    int numSamplesRequested = frameCount * ctx->channels;

    ma_mutex_lock(&ctx->rb.lock);
    
    // Copy data from ring buffer to output
    int samplesToRead = (ctx->rb.count < numSamplesRequested) ? ctx->rb.count : numSamplesRequested;
    
    for (int i = 0; i < samplesToRead; i++) {
        out[i] = ctx->rb.buffer[ctx->rb.tail];
        ctx->rb.tail = (ctx->rb.tail + 1) % ctx->rb.capacity;
    }
    ctx->rb.count -= samplesToRead;
    
    ma_mutex_unlock(&ctx->rb.lock);

    // Signal that space is available
    if (samplesToRead > 0) {
        ma_event_signal(&ctx->rb.spaceAvailableEvent);
    }

    // If we didn't have enough data in the ring buffer, pad the rest with silence (0)
    // to prevent audio glitches/stuttering.
    if (samplesToRead < numSamplesRequested) {
        for (int i = samplesToRead; i < numSamplesRequested; i++) {
            out[i] = 0;
        }
    }
}

// ---------------------------------------------------------
// EXPORTED C API FOR JAVA FFM
// ---------------------------------------------------------

#if defined(_WIN32)
#define EXPORT __declspec(dllexport)
#else
#define EXPORT __attribute__((visibility("default")))
#endif

#ifdef __cplusplus
extern "C" {
#endif

EXPORT AudioEngineContext* midiraja_audio_init(int sampleRate, int channels, int bufferSizeInFrames) {
    AudioEngineContext* ctx = (AudioEngineContext*)malloc(sizeof(AudioEngineContext));
    if (!ctx) return NULL;

    ctx->sampleRate = sampleRate;
    ctx->channels = channels;
    
    // Initialize Ring Buffer (capable of holding bufferSizeInFrames)
    ctx->rb.capacity = bufferSizeInFrames * channels;
    ctx->rb.buffer = (short*)malloc(sizeof(short) * ctx->rb.capacity);
    ctx->rb.head = 0;
    ctx->rb.tail = 0;
    ctx->rb.count = 0;
    
    if (ma_mutex_init(&ctx->rb.lock) != MA_SUCCESS) {
        free(ctx->rb.buffer);
        free(ctx);
        return NULL;
    }
    
    if (ma_event_init(&ctx->rb.spaceAvailableEvent) != MA_SUCCESS) {
        ma_mutex_uninit(&ctx->rb.lock);
        free(ctx->rb.buffer);
        free(ctx);
        return NULL;
    }

    // Initialize Miniaudio Device
    ma_device_config deviceConfig = ma_device_config_init(ma_device_type_playback);
    deviceConfig.playback.format   = ma_format_s16; // Short (16-bit)
    deviceConfig.playback.channels = channels;
    deviceConfig.sampleRate        = sampleRate;
    deviceConfig.dataCallback      = data_callback;
    deviceConfig.pUserData         = ctx;

    if (ma_device_init(NULL, &deviceConfig, &ctx->device) != MA_SUCCESS) {
        printf("[NativeAudio] Failed to init miniaudio device.\n");
        ma_event_uninit(&ctx->rb.spaceAvailableEvent);
        ma_mutex_uninit(&ctx->rb.lock);
        free(ctx->rb.buffer);
        free(ctx);
        return NULL;
    }

    if (ma_device_start(&ctx->device) != MA_SUCCESS) {
        printf("[NativeAudio] Failed to start miniaudio device.\n");
        ma_device_uninit(&ctx->device);
        ma_event_uninit(&ctx->rb.spaceAvailableEvent);
        ma_mutex_uninit(&ctx->rb.lock);
        free(ctx->rb.buffer);
        free(ctx);
        return NULL;
    }
    
    // printf("[NativeAudio] Engine started: %d Hz, %d channels\n", sampleRate, channels);

    return ctx;
}

EXPORT int midiraja_audio_get_queued_frames(AudioEngineContext* ctx) {
    if (!ctx) return 0;
    int frames = 0;
    ma_mutex_lock(&ctx->rb.lock);
    frames = ctx->rb.count / ctx->channels;
    ma_mutex_unlock(&ctx->rb.lock);
    return frames;
}

// Returns the total device-side latency in frames at ctx->sampleRate (32kHz).
// Covers miniaudio's internal period buffer + CoreAudio hardware/stream/safety latency.
EXPORT int midiraja_audio_get_device_latency_frames(AudioEngineContext* ctx) {
    if (!ctx) return 0;

    // miniaudio internal pipeline: internalPeriodSizeInFrames is at the device's
    // internalSampleRate, not at ctx->sampleRate. Scale to ctx->sampleRate.
    int miniaudioFrames = 0;
    {
        double internalRate = (double)ctx->device.playback.internalSampleRate;
        if (internalRate > 0.0) {
            double rawFrames = (double)(ctx->device.playback.internalPeriodSizeInFrames *
                                        ctx->device.playback.internalPeriods);
            miniaudioFrames = (int)(rawFrames * ctx->sampleRate / internalRate);
        }
    }

#if defined(__APPLE__)
    // Also query CoreAudio hardware device latency + stream latency + safety offset.
    // All three are needed for complete end-to-end latency on macOS.
    AudioObjectID deviceID = ctx->device.coreaudio.deviceObjectIDPlayback;
    if (deviceID != 0) {
        UInt32 sz;

        // 1. Device hardware latency (frames at hw nominal rate)
        AudioObjectPropertyAddress addr = {
            kAudioDevicePropertyLatency,
            kAudioObjectPropertyScopeOutput,
            kAudioObjectPropertyElementMain
        };
        UInt32 devLatency = 0;
        sz = sizeof(devLatency);
        AudioObjectGetPropertyData(deviceID, &addr, 0, NULL, &sz, &devLatency);

        // 2. Safety offset: additional lead-time the OS requires to avoid underruns
        AudioObjectPropertyAddress safetyAddr = {
            kAudioDevicePropertySafetyOffset,
            kAudioObjectPropertyScopeOutput,
            kAudioObjectPropertyElementMain
        };
        UInt32 safetyOffset = 0;
        sz = sizeof(safetyOffset);
        AudioObjectGetPropertyData(deviceID, &safetyAddr, 0, NULL, &sz, &safetyOffset);
        devLatency += safetyOffset;

        // 3. Sum latency of all output streams
        AudioObjectPropertyAddress streamsAddr = {
            kAudioDevicePropertyStreams,
            kAudioObjectPropertyScopeOutput,
            kAudioObjectPropertyElementMain
        };
        UInt32 streamsSize = 0;
        if (AudioObjectGetPropertyDataSize(deviceID, &streamsAddr, 0, NULL, &streamsSize) == noErr
                && streamsSize > 0) {
            AudioStreamID* streams = (AudioStreamID*)malloc(streamsSize);
            if (streams) {
                if (AudioObjectGetPropertyData(deviceID, &streamsAddr, 0, NULL, &streamsSize, streams) == noErr) {
                    AudioObjectPropertyAddress streamLatAddr = {
                        kAudioStreamPropertyLatency,
                        kAudioObjectPropertyScopeOutput,
                        kAudioObjectPropertyElementMain
                    };
                    UInt32 streamLat = 0;
                    sz = sizeof(streamLat);
                    AudioObjectGetPropertyData(streams[0], &streamLatAddr, 0, NULL, &sz, &streamLat);
                    devLatency += streamLat;
                }
                free(streams);
            }
        }

        // devLatency is in frames at the hardware's nominal sample rate. Scale to ctx->sampleRate.
        Float64 hwRate = 0.0;
        AudioObjectPropertyAddress rateAddr = {
            kAudioDevicePropertyNominalSampleRate,
            kAudioObjectPropertyScopeOutput,
            kAudioObjectPropertyElementMain
        };
        sz = sizeof(hwRate);
        AudioObjectGetPropertyData(deviceID, &rateAddr, 0, NULL, &sz, &hwRate);
        if (hwRate > 0.0) {
            devLatency = (UInt32)((devLatency * ctx->sampleRate) / hwRate);
        }

        miniaudioFrames += (int)devLatency;
    }
#endif

    return miniaudioFrames;
}

EXPORT void midiraja_audio_push(AudioEngineContext* ctx, const short* data, int numSamples) {
    if (!ctx || !data || numSamples <= 0) return;

    while (1) {
        ma_mutex_lock(&ctx->rb.lock);
        
        int availableSpace = ctx->rb.capacity - ctx->rb.count;
        if (availableSpace >= numSamples) {
            // Write data to the ring buffer
            for (int i = 0; i < numSamples; i++) {
                ctx->rb.buffer[ctx->rb.head] = data[i];
                ctx->rb.head = (ctx->rb.head + 1) % ctx->rb.capacity;
                ctx->rb.count++;
            }
            ma_mutex_unlock(&ctx->rb.lock);
            break; // Done pushing
        }
        
        // Not enough space, wait for data_callback to consume
        ma_mutex_unlock(&ctx->rb.lock);
        ma_event_wait(&ctx->rb.spaceAvailableEvent);
    }
}

EXPORT void midiraja_audio_close(AudioEngineContext* ctx) {
    if (!ctx) return;
    
    ma_device_uninit(&ctx->device);
    ma_event_uninit(&ctx->rb.spaceAvailableEvent);
    ma_mutex_uninit(&ctx->rb.lock);
    free(ctx->rb.buffer);
    free(ctx);
}

#ifdef __cplusplus
}
#endif