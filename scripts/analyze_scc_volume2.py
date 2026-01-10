import numpy as np

# Re-run for Piano wave and Brass wave to see if they are also quiet
wave_piano = np.zeros(32)
wave_brass = np.zeros(32)
for i in range(32):
    wave_piano[i] = (255.0 * (31 - i) / 32.0 - 128.0)
    wave_brass[i] = (255.0 * i / 32.0 - 128.0)

print(f"Piano Wave - Peak: {np.max(wave_piano)}, Min: {np.min(wave_piano)}")
print(f"Brass Wave - Peak: {np.max(wave_brass)}, Min: {np.min(wave_brass)}")
print(f"Piano Wave - RMS: {np.sqrt(np.mean((wave_piano/128.0)**2)):.4f}")
print(f"Square Wave - RMS: 1.0000")
