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