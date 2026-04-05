package com.fupfin.midiraja.dsp;

/** Constants for the DSP pipeline's internal headroom convention. */
public final class DspConstants
{
    /** Internal processing level: -12 dBFS (0.25 linear). */
    public static final float INTERNAL_LEVEL = 0.25f;

    /** Inverse of INTERNAL_LEVEL; used by MasterGainFilter to restore full scale. */
    public static final float INTERNAL_LEVEL_INV = 1.0f / INTERNAL_LEVEL; // 4.0f

    private DspConstants()
    {
    }
}
