
#define MINIAUDIO_IMPLEMENTATION
#include "miniaudio.h"
#include <stdio.h>
#include <stdlib.h>

// Typedef for the Java Upcall callback function pointer
// Parameters:
//   void* userData - context pointer
//   short* pOutput - buffer to fill with PCM data
//   int numSamplesRequested - number of samples (frameCount * channels) needed
// Returns: number of samples actually provided (usually should match requested)
typedef int (*JavaAudioCallback)(void* userData, short* pOutput, int numSamplesRequested);

typedef struct
{
    ma_device device;
    int sampleRate;
    int channels;
    JavaAudioCallback javaCallback;
    void* javaUserData;
} AudioEngineContext;

// This callback is fired by miniaudio when the OS audio driver needs more PCM data.
void data_callback(ma_device* pDevice, void* pOutput, const void* pInput, ma_uint32 frameCount)
{
    AudioEngineContext* ctx = (AudioEngineContext*)pDevice->pUserData;
    short* out = (short*)pOutput;
    int numSamplesRequested = frameCount * ctx->channels;

    if (ctx->javaCallback != NULL)
    {
        // Call back into Java to get the audio data
        int samplesProvided = ctx->javaCallback(ctx->javaUserData, out, numSamplesRequested);

        // If Java didn't provide enough data, pad the rest with silence
        if (samplesProvided < numSamplesRequested)
        {
            for (int i = samplesProvided; i < numSamplesRequested; i++)
            {
                out[i] = 0;
            }
        }
    }
    else
    {
        // No callback registered, output silence
        for (int i = 0; i < numSamplesRequested; i++)
        {
            out[i] = 0;
        }
    }
}

#ifdef _WIN32
#define EXPORT __declspec(dllexport)
#else
#define EXPORT __attribute__((visibility("default")))
#endif


    // Initializes the audio engine.
    // javaCallbackPtr is the memory address of the Java upcall stub.
    EXPORT AudioEngineContext* midiraja_audio_init(int sampleRate, int channels, int bufferSizeInFrames, void* javaCallbackPtr, void* javaUserData)
    {
        AudioEngineContext* ctx = (AudioEngineContext*)malloc(sizeof(AudioEngineContext));
        if (!ctx) return NULL;

        ctx->sampleRate = sampleRate;
        ctx->channels = channels;
        ctx->javaCallback = (JavaAudioCallback)javaCallbackPtr;
        ctx->javaUserData = javaUserData;

        // Initialize Miniaudio Device
        ma_device_config deviceConfig = ma_device_config_init(ma_device_type_playback);
        deviceConfig.playback.format = ma_format_s16;  // Short (16-bit)
        deviceConfig.playback.channels = channels;
        deviceConfig.sampleRate = sampleRate;
        deviceConfig.dataCallback = data_callback;
        deviceConfig.pUserData = ctx;
        deviceConfig.periodSizeInFrames = 512; // Force small audio chunks (approx 16ms at 32kHz) to ensure smooth regular callbacks

        if (ma_device_init(NULL, &deviceConfig, &ctx->device) != MA_SUCCESS)
        {
            printf("[NativeAudio] Failed to init miniaudio device.\n");
            free(ctx);
            return NULL;
        }

        if (ma_device_start(&ctx->device) != MA_SUCCESS)
        {
            printf("[NativeAudio] Failed to start miniaudio device.\n");
            ma_device_uninit(&ctx->device);
            free(ctx);
            return NULL;
        }

        return ctx;
    }

    // Returns the total device-side latency in frames at ctx->sampleRate (32kHz).
    EXPORT int midiraja_audio_get_device_latency_frames(AudioEngineContext* ctx)
    {
        if (!ctx) return 0;
        
return 0;
    }

    EXPORT void midiraja_audio_close(AudioEngineContext* ctx)
    {
        if (!ctx) return;
        ma_device_uninit(&ctx->device);
        free(ctx);
    }
