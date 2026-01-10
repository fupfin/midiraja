import numpy as np

sampleRate = 44100
frames = 44100
buffer_psg = np.zeros(frames)
buffer_scc = np.zeros(frames)

# DAC Table (Same as Java code)
dacTable = np.zeros(16)
for i in range(1, 16):
    dacTable[i] = 10.0 ** ((i - 15) * 1.5 / 20.0)
dacTable[0] = 0.0

# 1. PsgChip (Square wave) Simulation
phase16 = 0
phaseStep16 = int((440.0 * 65536.0) / sampleRate)
duty16 = 32767

for i in range(frames):
    phase16 = (phase16 + phaseStep16) & 0xFFFF
    bit = 1.0 if phase16 < duty16 else -1.0
    buffer_psg[i] = bit * dacTable[15] * 0.2

# 2. SccChip (Wavetable - String/Sine) Simulation
# Matching the WAVE_STRINGS initialization in Java
wave_strings = np.zeros(32)
for i in range(32):
    t = i * np.pi * 2.0 / 32.0
    wave_strings[i] = (np.sin(t) * 0.8 + np.sin(t * 3) * 0.2) * 127

phase = 0.0
phaseStep = (440.0 * 32.0) / sampleRate

for i in range(frames):
    phase += phaseStep
    if phase >= 32.0:
        phase -= 32.0
        
    index0 = int(phase)
    index1 = (index0 + 1) % 32
    frac = phase - index0
    
    s0 = wave_strings[index0] / 128.0
    s1 = wave_strings[index1] / 128.0
    
    sample = s0 + frac * (s1 - s0)
    
    buffer_scc[i] = sample * dacTable[15] * 0.2

# Calculate RMS Power and Peak
rms_psg = np.sqrt(np.mean(buffer_psg**2))
peak_psg = np.max(np.abs(buffer_psg))

rms_scc = np.sqrt(np.mean(buffer_scc**2))
peak_scc = np.max(np.abs(buffer_scc))

print("Volume Analysis (440Hz):")
print(f"PSG Square Wave - RMS: {rms_psg:.4f}, Peak: {peak_psg:.4f}")
print(f"SCC String Wave - RMS: {rms_scc:.4f}, Peak: {peak_scc:.4f}")
print(f"Volume Ratio (SCC / PSG): {rms_scc / rms_psg:.2f}x")

# FFT for fundamental magnitude
fft_psg = np.abs(np.fft.rfft(buffer_psg))
fft_scc = np.abs(np.fft.rfft(buffer_scc))

idx_440 = int(440.0 * frames / sampleRate)

print(f"\\nFundamental Magnitude (440Hz):")
print(f"PSG: {fft_psg[idx_440]:.1f}")
print(f"SCC: {fft_scc[idx_440]:.1f}")
print(f"Magnitude Ratio (SCC / PSG): {fft_scc[idx_440] / fft_psg[idx_440]:.2f}x")

