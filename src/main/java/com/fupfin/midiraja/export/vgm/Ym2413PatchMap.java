/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.export.vgm;

/**
 * Maps GM program numbers (0–127) to YM2413 built-in patch numbers (1–15).
 *
 * <p>
 * YM2413 built-in patches:
 * <pre>
 *  1=Violin   2=Guitar      3=Piano      4=Flute      5=Clarinet
 *  6=Oboe     7=Trumpet     8=Organ      9=Horn      10=Synthesizer
 * 11=Harpsichord  12=Vibraphone  13=Synth Bass  14=Acoustic Bass  15=Electric Guitar
 * </pre>
 */
final class Ym2413PatchMap
{
    // @formatter:off
    static final int[] GM_TO_YM2413 = {
        //  0  Acoustic Grand Piano       →  3  Piano
        //  1  Bright Acoustic Piano      →  3  Piano
        //  2  Electric Grand Piano       →  3  Piano
        //  3  Honky-tonk Piano           →  3  Piano
        //  4  Electric Piano 1 (Rhodes)  →  3  Piano
        //  5  Electric Piano 2           →  3  Piano
        //  6  Harpsichord                → 11  Harpsichord
        //  7  Clavinet                   → 11  Harpsichord
        3, 3, 3, 3, 3, 3, 11, 11,

        //  8  Celesta          → 12  Vibraphone
        //  9  Glockenspiel     → 12  Vibraphone
        // 10  Music Box        →  3  Piano
        // 11  Vibraphone       → 12  Vibraphone
        // 12  Marimba          → 12  Vibraphone
        // 13  Xylophone        → 12  Vibraphone
        // 14  Tubular Bells    → 11  Harpsichord
        // 15  Dulcimer         → 11  Harpsichord
        12, 12, 3, 12, 12, 12, 11, 11,

        // 16-23  Organ  →  8  Organ
        8, 8, 8, 8, 8, 8, 8, 8,

        // 24  Nylon Guitar         →  2  Guitar
        // 25  Steel Guitar         →  2  Guitar
        // 26  Jazz Guitar          → 15  Electric Guitar
        // 27  Clean Electric       → 15  Electric Guitar
        // 28  Muted Electric       → 15  Electric Guitar
        // 29  Overdrive Guitar     → 15  Electric Guitar
        // 30  Distortion Guitar    → 15  Electric Guitar
        // 31  Guitar Harmonics     →  2  Guitar
        2, 2, 15, 15, 15, 15, 15, 2,

        // 32  Acoustic Bass        → 14  Acoustic Bass
        // 33  Electric Bass finger → 15  Electric Guitar
        // 34  Electric Bass pick   → 15  Electric Guitar
        // 35  Fretless Bass        → 14  Acoustic Bass
        // 36  Slap Bass 1          → 15  Electric Guitar
        // 37  Slap Bass 2          → 15  Electric Guitar
        // 38  Synth Bass 1         → 13  Synth Bass
        // 39  Synth Bass 2         → 13  Synth Bass
        14, 15, 15, 14, 15, 15, 13, 13,

        // 40  Violin               →  1  Violin
        // 41  Viola                →  1  Violin
        // 42  Cello                →  1  Violin
        // 43  Contrabass           → 14  Acoustic Bass
        // 44  Tremolo Strings      →  1  Violin
        // 45  Pizzicato Strings    →  2  Guitar (plucked)
        // 46  Orchestral Harp      → 11  Harpsichord
        // 47  Timpani              →  8  Organ (sustained)
        1, 1, 1, 14, 1, 2, 11, 8,

        // 48  String Ensemble 1    →  1  Violin
        // 49  String Ensemble 2    →  1  Violin
        // 50  Synth Strings 1      → 10  Synthesizer
        // 51  Synth Strings 2      → 10  Synthesizer
        // 52  Choir Aahs           →  8  Organ
        // 53  Voice Oohs           →  8  Organ
        // 54  Synth Voice          → 10  Synthesizer
        // 55  Orchestra Hit        →  3  Piano
        1, 1, 10, 10, 8, 8, 10, 3,

        // 56  Trumpet              →  7  Trumpet
        // 57  Trombone             →  7  Trumpet
        // 58  Tuba                 →  9  Horn
        // 59  Muted Trumpet        →  7  Trumpet
        // 60  French Horn          →  9  Horn
        // 61  Brass Section        →  7  Trumpet
        // 62  Synth Brass 1        →  7  Trumpet
        // 63  Synth Brass 2        →  7  Trumpet
        7, 7, 9, 7, 9, 7, 7, 7,

        // 64  Soprano Sax          →  6  Oboe
        // 65  Alto Sax             →  6  Oboe
        // 66  Tenor Sax            →  6  Oboe
        // 67  Baritone Sax         →  5  Clarinet
        // 68  Oboe                 →  6  Oboe
        // 69  English Horn         →  6  Oboe
        // 70  Bassoon              →  5  Clarinet
        // 71  Clarinet             →  5  Clarinet
        6, 6, 6, 5, 6, 6, 5, 5,

        // 72  Piccolo              →  4  Flute
        // 73  Flute                →  4  Flute
        // 74  Recorder             →  4  Flute
        // 75  Pan Flute            →  4  Flute
        // 76  Blown Bottle         →  4  Flute
        // 77  Shakuhachi           →  5  Clarinet
        // 78  Whistle              →  4  Flute
        // 79  Ocarina              →  5  Clarinet
        4, 4, 4, 4, 4, 5, 4, 5,

        // 80  Square Lead          →  5  Clarinet (bright square)
        // 81  Sawtooth Lead        →  1  Violin (sawtooth)
        // 82-87  other leads       → 10  Synthesizer
        5, 1, 10, 10, 10, 10, 10, 10,

        // 88-95  Synth Pad         → 10  Synthesizer
        10, 10, 10, 10, 10, 10, 10, 10,

        // 96-103  Synth FX         → 10  Synthesizer
        10, 10, 10, 10, 10, 10, 10, 10,

        // 104  Sitar               →  2  Guitar
        // 105  Banjo               →  2  Guitar
        // 106  Shamisen            →  2  Guitar
        // 107  Koto                → 11  Harpsichord
        // 108  Kalimba             → 12  Vibraphone
        // 109  Bag Pipe            →  5  Clarinet
        // 110  Fiddle              →  1  Violin
        // 111  Shanai              →  6  Oboe
        2, 2, 2, 11, 12, 5, 1, 6,

        // 112  Tinkle Bell         → 12  Vibraphone
        // 113  Agogo               → 12  Vibraphone
        // 114  Steel Drums         → 12  Vibraphone
        // 115  Woodblock           → 11  Harpsichord
        // 116  Taiko Drum          →  8  Organ
        // 117  Melodic Tom         →  8  Organ
        // 118  Synth Drum          → 10  Synthesizer
        // 119  Reverse Cymbal      → 10  Synthesizer
        12, 12, 12, 11, 8, 8, 10, 10,

        // 120-127  Sound FX        → 10  Synthesizer
        10, 10, 10, 10, 10, 10, 10, 10,
    };
    // @formatter:on

    private Ym2413PatchMap()
    {
    }

    static int lookup(int gmProgram)
    {
        if (gmProgram < 0 || gmProgram >= GM_TO_YM2413.length)
            return 1;
        return GM_TO_YM2413[gmProgram];
    }
}
