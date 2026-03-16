/*
 * TSF wrapper — compiles TinySoundFont as a shared library.
 *
 * stb_vorbis.c must be included before tsf.h so that TinySoundFont
 * detects STB_VORBIS_INCLUDE_STB_VORBIS_H and enables SF3 (Ogg Vorbis)
 * decoding.  Without it, SF3 samples are read as raw bytes → noise.
 */
#include "stb_vorbis.c"

#define TSF_IMPLEMENTATION
#include "tsf.h"
