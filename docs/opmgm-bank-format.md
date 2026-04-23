# OPMGM Bank Format Specification

Version 1 — 2026-04-21

---

## Overview

The OPMGM bank format stores a set of General MIDI FM patches for the YM2151 (OPM) chip.
It is the binary exchange format between `scripts/gen_opm_bank_v4.py` (writer) and
`OpmBankReader.java` (reader).

The format is deliberately minimal: a fixed-size 128-melodic + 128-percussion layout with no
variable-length fields, making random access O(1) and parsing branch-free. All multi-byte
integer fields are **little-endian** unless otherwise noted.

Compared to WOPN2 (the OPN2/YM2612 equivalent), OPMGM adds the OPM-exclusive `dt2d2r` operator
field (DT2 in bits 7-6) and stores `noteOffset` as a signed byte rather than a signed 16-bit
word. There is no bank-grouping layer; patches are addressed directly by GM program number
(melodic) or GM note number (percussion).

---

## File Layout

```
┌─────────────────────────────────────────────┐
│ File header                    15 bytes      │
├─────────────────────────────────────────────┤
│ Melodic patches   [0..nMelodic-1]            │
│   nMelodic × 52 bytes                        │
├─────────────────────────────────────────────┤
│ Percussion patches [0..nPercussion-1]        │
│   nPercussion × 52 bytes                     │
└─────────────────────────────────────────────┘
```

The file has no alignment padding between sections.

---

## File Header (15 bytes)

| Offset | Size | Type  | Name          | Description                                    |
|-------:|-----:|-------|---------------|------------------------------------------------|
|      0 |   10 | bytes | `magic`       | ASCII `OPMGM-BNK` followed by NUL (`\0`)       |
|     10 |    1 | u8    | `version`     | Format version, currently `0x01`               |
|     11 |    2 | u16le | `nMelodic`    | Number of melodic patches (normally 128)       |
|     13 |    2 | u16le | `nPercussion` | Number of percussion patches (normally 128)    |

A reader must reject files where `magic` does not match exactly (all 10 bytes including the
trailing NUL).

---

## Patch Record (52 bytes)

Each melodic and percussion patch uses the same 52-byte layout.

| Offset | Size | Type   | Name         | Description                                                    |
|-------:|-----:|--------|--------------|----------------------------------------------------------------|
|      0 |   16 | bytes  | `name`       | Instrument name, NUL-padded; not NUL-terminated if exactly 16 bytes |
|     16 |    1 | i8     | `noteOffset` | Semitone transposition applied at playback (signed)            |
|     17 |    1 | u8     | `percKey`    | Percussion fixed-pitch key; 0 = use incoming MIDI note         |
|     18 |    1 | u8     | `fbalg`      | bits 5-3 = FB (feedback 0-7), bits 2-0 = ALG (algorithm 0-7)  |
|     19 |    1 | u8     | `lfosens`    | bits 6-4 = PMS (pitch mod sensitivity), bits 1-0 = AMS (amp mod sensitivity) |
|     20 |   32 | bytes  | `operators`  | Four operator records, 8 bytes each (see below)                |

Total: 16 + 1 + 1 + 1 + 1 + 4 × 8 = **52 bytes**.

### Operator slot order

OPM uses a non-intuitive slot order. The four `operators` entries are in OPM slot order:

| Index | OPM slot | Typical role in serial chain |
|------:|:--------:|------------------------------|
|     0 | Slot 1   | Modulator 1                  |
|     1 | Slot 3   | Modulator 2                  |
|     2 | Slot 2   | Modulator 3                  |
|     3 | Slot 4   | Carrier (always audible)     |

The carrier mask for each algorithm follows the standard OPM/OPN definition.

---

## Operator Record (8 bytes)

Maps directly to YM2151 register layout. For channel `ch` and slot offset `s`
(where `s = OP_OFFSETS[opIndex] + ch`, `OP_OFFSETS = {0, 8, 16, 24}`):

| Offset | Size | YM2151 reg  | Name     | Bits                                               |
|-------:|-----:|-------------|----------|----------------------------------------------------|
|      0 |    1 | `0x40 + s`  | `dt1mul` | bits 6-4 = DT1 (0-7), bits 3-0 = MUL (0-15)       |
|      1 |    1 | `0x60 + s`  | `tl`     | bits 6-0 = TL total level; 0 = loudest, 127 = silent |
|      2 |    1 | `0x80 + s`  | `ksatk`  | bits 7-6 = KS (0-3), bits 4-0 = AR (0-31)         |
|      3 |    1 | `0xA0 + s`  | `amd1r`  | bit 7 = AM-EN, bits 4-0 = D1R (0-31)              |
|      4 |    1 | `0xC0 + s`  | `dt2d2r` | bits 7-6 = DT2 (0-3), bits 4-0 = D2R (0-31)       |
|      5 |    1 | `0xE0 + s`  | `d1lrr`  | bits 7-4 = D1L (0-15), bits 3-0 = RR (0-15)       |
|      6 |    1 | —           | `ssgeg`  | SSG-EG (not used by OPM hardware; stored for completeness) |
|      7 |    1 | —           | `_pad`   | Reserved, must be 0                                |

**`dt2d2r` is the OPM-exclusive field absent from WOPN2.** DT2 adds a coarse frequency
offset: 0 = off, 1 ≈ +600 ¢, 2 ≈ +781 ¢, 3 ≈ +950 ¢. It is used for metallic, bell, and
brass timbres. Writers that convert from WOPN2 must set DT2 = 0.

---

## Field Details

### `noteOffset` (i8, offset 16)

Signed semitone shift applied to every note played with this patch. Stored as a raw byte;
values ≥ 128 are interpreted as negative (two's complement). Range: −128 to +127.

### `percKey` (u8, offset 17)

For percussion patches, the YM2151 is keyed at this fixed MIDI note number regardless of
the incoming MIDI note. If zero, the incoming MIDI note is used directly. For melodic
patches this field is 0 and ignored.

### `fbalg` (u8, offset 18)

```
  7   6   5   4   3   2   1   0
  ×   ×  FB2 FB1 FB0 AL2 AL1 AL0
```

- **FB** (bits 5-3): operator 1 self-feedback level; 0 = none, 7 = maximum.
- **ALG** (bits 2-0): FM algorithm 0-7, matching the standard OPM algorithm map.

### `lfosens` (u8, offset 19)

```
  7   6   5   4   3   2   1   0
  ×  PM2 PM1 PM0  ×   ×  AM1 AM0
```

- **PMS** (bits 6-4): pitch modulation sensitivity, 0-7.
- **AMS** (bits 1-0): amplitude modulation sensitivity, 0-3.

---

## Patch Addressing

Patches are stored contiguously with no index table.

- **Melodic patch for GM program P** (0-127): byte offset = `15 + P × 52`
- **Percussion patch for GM note N** (0-127): byte offset = `15 + nMelodic × 52 + N × 52`

When `nMelodic = nPercussion = 128` (the normal case):

```
Total file size = 15 + 128 × 52 + 128 × 52 = 13,327 bytes
```

---

## Differences from WOPN2

| Property            | WOPN2                            | OPMGM v1                           |
|---------------------|----------------------------------|------------------------------------|
| Target chip         | YM2612 / OPN2                    | YM2151 / OPM                       |
| Magic               | `WOPN2-BANK\0` / `WOPN2-B2NK\0` | `OPMGM-BNK\0`                      |
| Bank grouping       | Multiple named banks             | None — GM index is direct          |
| Instrument name     | 32 bytes                         | 16 bytes                           |
| `noteOffset`        | i16be (2 bytes)                  | i8 (1 byte)                        |
| Operator fields     | 7 bytes (no DT2)                 | 8 bytes (`dt2d2r` + pad)           |
| DT2 field           | Absent                           | bits 7-6 of `dt2d2r`               |
| SSG-EG              | Present (used by OPN)            | Present (unused by OPM hardware)   |
| Patch size          | 69 bytes                         | 52 bytes                           |
| Header endianness   | Big-endian (most fields)         | Little-endian                      |

---

## Version History

| Version | Changes                                                   |
|--------:|-----------------------------------------------------------|
|       1 | Initial format. `dt2d2r` field distinguishes OPM from WOPN2. |
