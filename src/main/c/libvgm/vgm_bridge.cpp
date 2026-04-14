/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

/*
 * vgm_bridge.cpp — thin extern "C" wrapper around libvgm's PlayerA.
 *
 * Exposes nine functions consumed by FFMLibvgmBridge via Java Panama FFM:
 *   void*   vgm_create(int sample_rate)
 *   int     vgm_open_file(void* ctx, const char* path)
 *   int     vgm_open_data(void* ctx, const uint8_t* data, size_t len)
 *   int     vgm_render(void* ctx, int frames, short* buf)
 *   int     vgm_is_done(void* ctx)
 *   int64_t vgm_get_duration_us(void* ctx)
 *   void    vgm_set_speed(void* ctx, double speed)
 *   void    vgm_seek_us(void* ctx, int64_t target_us)
 *   void    vgm_close(void* ctx)
 */

#include <cstdlib>
#include <cstring>
#include <memory>

#include "player/playerA.hpp"
#include "player/vgmplayer.hpp"
#include "player/s98player.hpp"
#include "player/droplayer.hpp"
#include "player/gymplayer.hpp"
#include "utils/DataLoader.h"
#include "utils/MemoryLoader.h"
#include "utils/FileLoader.h"

// ── Context struct ─────────────────────────────────────────────────────────────

struct VgmContext
{
    PlayerA player;
    DATA_LOADER* loader = nullptr;
    bool done = false;
    int sampleRate;

    VgmContext()
    {
        player.RegisterPlayerEngine(new VGMPlayer());
        player.RegisterPlayerEngine(new S98Player());
        player.RegisterPlayerEngine(new DROPlayer());
        player.RegisterPlayerEngine(new GYMPlayer());
    }

    ~VgmContext()
    {
        player.Stop();
        if (loader != nullptr)
        {
            DataLoader_Deinit(loader);
            loader = nullptr;
        }
    }
};

// ── Helper ────────────────────────────────────────────────────────────────────

static int start_player(VgmContext* ctx, DATA_LOADER* ldr)
{
    if (ldr == nullptr)
        return -1;

    // Deinit any previous loader
    if (ctx->loader != nullptr)
    {
        ctx->player.Stop();
        DataLoader_Deinit(ctx->loader);
        ctx->loader = nullptr;
    }

    ctx->loader = ldr;
    ctx->done = false;

    UINT8 rc = DataLoader_Load(ctx->loader);
    if (rc != 0)
        return (int) rc;

    rc = ctx->player.LoadFile(ctx->loader);
    if (rc != 0)
        return (int) rc;

    ctx->player.SetOutputSettings(
        (UINT32) ctx->sampleRate,
        /* channels  */ 2,
        /* bitDepth  */ 16,
        /* bufferLen */ 4096);  // must be >= FRAMES_PER_RENDER (512) in Java

    // Play through once and stop. Without this, VGM files with loop points repeat
    // indefinitely (libvgm default loopCount = 2), preventing isDone() from firing
    // and blocking playlist advancement.
    ctx->player.SetLoopCount(1);

    ctx->player.Start();
    return 0;
}

// ── Public C API ──────────────────────────────────────────────────────────────

extern "C"
{

void* vgm_create(int sample_rate)
{
    auto* ctx = new (std::nothrow) VgmContext();
    if (ctx == nullptr)
        return nullptr;
    ctx->sampleRate = sample_rate;
    return ctx;
}

int vgm_open_file(void* handle, const char* path)
{
    if (handle == nullptr || path == nullptr)
        return -1;
    auto* ctx = static_cast<VgmContext*>(handle);
    DATA_LOADER* ldr = FileLoader_Init(path);
    return start_player(ctx, ldr);
}

int vgm_open_data(void* handle, const uint8_t* data, size_t len)
{
    if (handle == nullptr || data == nullptr || len == 0)
        return -1;
    auto* ctx = static_cast<VgmContext*>(handle);
    // MemoryLoader takes ownership via copy — copy bytes to a malloc buffer
    auto* buf = static_cast<uint8_t*>(std::malloc(len));
    if (buf == nullptr)
        return -1;
    std::memcpy(buf, data, len);
    DATA_LOADER* ldr = MemoryLoader_Init(buf, (UINT32) len);
    if (ldr == nullptr)
    {
        std::free(buf);
        return -1;
    }
    return start_player(ctx, ldr);
}

/*
 * vgm_render — renders audio into `buf` (interleaved stereo s16).
 *
 * `numSamples` is the total count of s16 values in `buf` (frames × 2 channels),
 * matching the adl_generate/adl_generateFormat convention used by AbstractFFMBridge
 * so that generateInto() can be reused without a custom descriptor.
 */
int vgm_render(void* handle, int numSamples, short* buf)
{
    if (handle == nullptr || buf == nullptr || numSamples <= 0)
        return 0;
    auto* ctx = static_cast<VgmContext*>(handle);
    if (ctx->done)
        return 0;

    UINT32 byteCount = (UINT32)(numSamples * (int)sizeof(short));
    UINT32 rendered = ctx->player.Render(byteCount, buf);
    if (rendered < byteCount)
    {
        // Zero-fill remainder so caller always gets a full buffer
        std::memset(reinterpret_cast<uint8_t*>(buf) + rendered, 0, byteCount - rendered);
    }
    if (ctx->player.GetState() & PLAYSTATE_END)
        ctx->done = true;

    return (int)(rendered / (int)sizeof(short));
}

int vgm_is_done(void* handle)
{
    if (handle == nullptr)
        return 1;
    auto* ctx = static_cast<VgmContext*>(handle);
    return ctx->done ? 1 : 0;
}

/**
 * Returns the total VGM duration in microseconds, or 0 if no file is loaded.
 * Flags=0: total including intro; flags=1: loop length only.
 */
int64_t vgm_get_duration_us(void* handle)
{
    if (handle == nullptr)
        return 0;
    auto* ctx = static_cast<VgmContext*>(handle);
    double secs = ctx->player.GetTotalTime(0);
    return (int64_t)(secs * 1e6);
}

/**
 * Sets the playback speed multiplier (1.0 = normal, 2.0 = double speed).
 */
void vgm_set_speed(void* handle, double speed)
{
    if (handle == nullptr)
        return;
    auto* ctx = static_cast<VgmContext*>(handle);
    ctx->player.SetPlaybackSpeed(speed);
}

/**
 * Seeks to the given position in microseconds.
 * Clears the done flag so playback can resume after a backward seek.
 */
void vgm_seek_us(void* handle, int64_t target_us)
{
    if (handle == nullptr)
        return;
    auto* ctx = static_cast<VgmContext*>(handle);
    if (target_us < 0)
        target_us = 0;
    UINT32 sample = (UINT32)((target_us * (int64_t) ctx->sampleRate) / 1000000LL);
    ctx->done = false;
    ctx->player.Seek(PLAYPOS_SAMPLE, sample);
}

void vgm_close(void* handle)
{
    if (handle == nullptr)
        return;
    delete static_cast<VgmContext*>(handle);
}

} // extern "C"
