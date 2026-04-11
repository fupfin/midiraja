/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

/*
 * vgm_bridge.cpp — thin extern "C" wrapper around libvgm's PlayerA.
 *
 * Exposes six functions consumed by FFMLibvgmBridge via Java Panama FFM:
 *   void* vgm_create(int sample_rate)
 *   int   vgm_open_file(void* ctx, const char* path)
 *   int   vgm_open_data(void* ctx, const uint8_t* data, size_t len)
 *   int   vgm_render(void* ctx, int frames, short* buf)
 *   int   vgm_is_done(void* ctx)
 *   void  vgm_close(void* ctx)
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

void vgm_close(void* handle)
{
    if (handle == nullptr)
        return;
    delete static_cast<VgmContext*>(handle);
}

} // extern "C"
