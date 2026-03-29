/*
 * Copyright (c) 2026, Park, Sungchul All rights reserved.
 *
 * This source code is licensed under the BSD-style license found in the LICENSE file in the root
 * directory of this source tree.
 */

package com.fupfin.midiraja.ui;

import static java.lang.Math.*;

/**
 * Responsive VU meter display for 16 MIDI channels.
 */
public class ChannelActivityPanel implements Panel
{
    private static final String[] GM_INSTRUMENTS = {
        // Piano (0-7)
        "Grand Piano", "Bright Piano", "El.Grand Piano", "Honky-tonk",
        "El.Piano 1", "El.Piano 2", "Harpsichord", "Clavinet",
        // Chromatic Percussion (8-15)
        "Celesta", "Glockenspiel", "Music Box", "Vibraphone",
        "Marimba", "Xylophone", "Tubular Bells", "Dulcimer",
        // Organ (16-23)
        "Drawbar Organ", "Percuss.Organ", "Rock Organ", "Church Organ",
        "Reed Organ", "Accordion", "Harmonica", "Tango Accord.",
        // Guitar (24-31)
        "Nylon Guitar", "Steel Guitar", "Jazz Guitar", "Clean Guitar",
        "Muted Guitar", "Overdrive Gtr", "Distort.Gtr", "Guitar Harmo.",
        // Bass (32-39)
        "Acoustic Bass", "Finger Bass", "Pick Bass", "Fretless Bass",
        "Slap Bass 1", "Slap Bass 2", "Synth Bass 1", "Synth Bass 2",
        // Strings (40-47)
        "Violin", "Viola", "Cello", "Contrabass",
        "Tremolo Str.", "Pizzicato Str", "Orch.Harp", "Timpani",
        // Ensemble (48-55)
        "String Ens.1", "String Ens.2", "Synth Str.1", "Synth Str.2",
        "Choir Aahs", "Voice Oohs", "Synth Voice", "Orchestra Hit",
        // Brass (56-63)
        "Trumpet", "Trombone", "Tuba", "Muted Trumpet",
        "French Horn", "Brass Section", "Synth Brass 1", "Synth Brass 2",
        // Reed (64-71)
        "Soprano Sax", "Alto Sax", "Tenor Sax", "Baritone Sax",
        "Oboe", "English Horn", "Bassoon", "Clarinet",
        // Pipe (72-79)
        "Piccolo", "Flute", "Recorder", "Pan Flute",
        "Blown Bottle", "Shakuhachi", "Whistle", "Ocarina",
        // Synth Lead (80-87)
        "Square Lead", "Saw Lead", "Calliope Lead", "Chiff Lead",
        "Charang Lead", "Voice Lead", "Fifths Lead", "Bass+Lead",
        // Synth Pad (88-95)
        "New Age Pad", "Warm Pad", "Polysynth Pad", "Choir Pad",
        "Bowed Pad", "Metallic Pad", "Halo Pad", "Sweep Pad",
        // Synth FX (96-103)
        "FX Rain", "FX Soundtrack", "FX Crystal", "FX Atmosphere",
        "FX Brightness", "FX Goblins", "FX Echoes", "FX Sci-fi",
        // Ethnic (104-111)
        "Sitar", "Banjo", "Shamisen", "Koto",
        "Kalimba", "Bag Pipe", "Fiddle", "Shanai",
        // Percussive (112-119)
        "Tinkle Bell", "Agogo", "Steel Drums", "Woodblock",
        "Taiko Drum", "Melodic Tom", "Synth Drum", "Reverse Cym.",
        // Sound Effects (120-127)
        "Fret Noise", "Breath Noise", "Seashore", "Bird Tweet",
        "Telephone", "Helicopter", "Applause", "Gunshot"
    };

    private LayoutConstraints constraints = new LayoutConstraints(80, 16, false, false);
    private final double[] channelLevels = new double[16];
    private final int[] channelPrograms = new int[16];

    @Override
    public void onLayoutUpdated(LayoutConstraints bounds)
    {
        this.constraints = bounds;
    }

    @Override
    public void onPlaybackStateChanged()
    {}

    @Override
    public void onTick(long currentMicroseconds)
    {}

    @Override
    public void onTempoChanged(float bpm)
    {}

    @Override
    public void onChannelActivity(int channel, int velocity)
    {
        if (channel >= 0 && channel < 16)
        {
            channelLevels[channel] = max(channelLevels[channel], velocity / 127.0);
        }
    }

    public void updatePrograms(int[] programs)
    {
        System.arraycopy(programs, 0, channelPrograms, 0, 16);
    }

    private String getChannelName(int ch)
    {
        if (ch == 9) return "Drums";
        int prog = channelPrograms[ch];
        if (prog >= 0 && prog < GM_INSTRUMENTS.length) return GM_INSTRUMENTS[prog];
        return "Unknown";
    }

    @Override
    public void render(ScreenBuffer buffer)
    {
        if (constraints.height() <= 0) return;

        for (int i = 0; i < 16; i++)
        {
            channelLevels[i] = max(0, channelLevels[i] - 0.05);
        }

        int w = constraints.width();
        int h = constraints.height();

        // Determine optimal number of columns based on available space
        int numCols;
        if (h >= 16 && w < 80)
        {
            numCols = 1;
        }
        else if (h >= 8 && w >= 60)
        {
            numCols = 2;
        }
        else if (h >= 4 && w >= 40)
        {
            numCols = 4;
        }
        else if (h >= 16)
        {
            numCols = 1;
        }
        else
        {
            numCols = 4;
        }

        int numRows = (int) ceil(16.0 / numCols);
        int rowsToDraw = min(numRows, h);
        int colWidth = w / numCols;

        for (int r = 0; r < rowsToDraw; r++)
        {
            StringBuilder rowSb = new StringBuilder();
            for (int c = 0; c < numCols; c++)
            {
                int ch = r + (c * numRows);
                if (ch >= 16) break;

                String cell;
                if (numCols == 4)
                {
                    // Format: "C01:[███··]"
                    // CXX: (4 static) + brackets from ProgressBar (2 static) = 6 static
                    int maxMeter = max(2, colWidth - 6);
                    int meterLen = (int) (channelLevels[ch] * maxMeter);

                    String meter = ProgressBar.render(meterLen, maxMeter,
                            ProgressBar.Style.DOTTED_BACKGROUND, true);
                    cell = String.format("C%02d:%s", ch + 1, meter);

                    int visibleLen = 4 + 2 + maxMeter;
                    cell += " ".repeat(max(0, colWidth - visibleLen));
                }
                else
                {
                    String instName = getChannelName(ch);
                    if (instName.length() > 11)
                    {
                        instName = instName.substring(0, 11);
                    }

                    // Format: "CH 01 (Piano ): [%s]"
                    // "CH 01 " (6) + "(Piano )" (13) + ": " (2) + brackets (2) = 23 static
                    int staticLen = 23;
                    int maxMeter = max(2, colWidth - staticLen);
                    int meterLen = (int) (channelLevels[ch] * maxMeter);

                    String meter = ProgressBar.render(meterLen, maxMeter,
                            ProgressBar.Style.DOTTED_BACKGROUND, true);

                    cell = String.format("CH %02d %-13s: %s", ch + 1, "(" + instName + ")", meter);

                    int visibleLen = staticLen + maxMeter;
                    cell += " ".repeat(max(0, colWidth - visibleLen));
                }
                rowSb.append(cell);
            }
            buffer.append(truncate(rowSb.toString(), w)).append("\n");
        }
    }
}
