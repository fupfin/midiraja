# Third-Party Notices 📜

Midiraja (midra) incorporates several open-source libraries. We are grateful to the authors and contributors of these projects. 

Below are the details of the third-party components and their respective licenses.

---

## 1. picocli
*   **Project**: [https://picocli.info/](https://picocli.info/)
*   **License**: Apache License 2.0
*   **Copyright**: Copyright 2017 Remko Popma

---

## 2. Java Native Access (JNA)
*   **Project**: [https://github.com/java-native-access/jna](https://github.com/java-native-access/jna)
*   **License**: Apache License 2.0 (Dual-licensed under LGPL 2.1)
*   **Copyright**: Copyright (c) 2007-2024 JNA Contributors

---

## 3. JLine
*   **Project**: [https://jline.github.io/](https://jline.github.io/)
*   **License**: BSD 3-Clause License
*   **Copyright**: Copyright (c) 2002-2023, the original author or authors.

---

## 4. FreePats

*   **Project**: [https://freepats.zenvoid.org/](https://freepats.zenvoid.org/)
*   **License**: GNU General Public License v2 (GPL v2)
*   **Copyright**: Copyright (c) The FreePats Project contributors
*   **Note**: The `.pat` patch files bundled in Midiraja's `patch` (`gus`) engine are from the FreePats project and are distributed under the GPL v2. Midiraja includes these files as a separate, independently licensed component ("mere aggregation"). The full GPL v2 license text is included in this distribution at `share/midra/freepats/LICENSE`. The upstream source is available at [https://freepats.zenvoid.org/](https://freepats.zenvoid.org/).

---

## 5. stb_vorbis

*   **Project**: [https://github.com/nothings/stb](https://github.com/nothings/stb)
*   **License**: MIT License / Public Domain (choose either)
*   **Copyright**: Copyright (c) 2017 Sean Barrett
*   **Note**: `stb_vorbis.c` is bundled in the `soundfont` engine (`libtsf`) to decode Ogg Vorbis-compressed samples in SF3 SoundFont files. Without it, SF3 files produce noise instead of music.

---

## 6. MuseScore General SoundFont

*   **Project**: [https://musescore.org/](https://musescore.org/)
*   **License**: MIT License
*   **Copyright**: Copyright (c) 2020 S. Christian Collins and MuseScore contributors
*   **Note**: The MuseScore General `.sf3` SoundFont file bundled with Midiraja is derived from the *MuseScore General* soundfont and is provided under the MIT License. Source and full details are available at [https://ftp.osuosl.org/pub/musescore/soundfont/MuseScore_General/](https://ftp.osuosl.org/pub/musescore/soundfont/MuseScore_General/).

---

## License Texts

### GNU General Public License v2
(Used by FreePats `.pat` files)

The full text of the GPL v2 is included in this distribution at `share/midra/freepats/LICENSE`.
A canonical copy is also available at [https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt](https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt).

---

### Apache License 2.0
(Used by picocli and JNA)

A copy of the Apache License 2.0 is provided below:
[http://www.apache.org/licenses/LICENSE-2.0.txt](http://www.apache.org/licenses/LICENSE-2.0.txt)

### BSD 3-Clause License
(Used by JLine)

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
