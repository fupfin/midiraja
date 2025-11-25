# The Quest for Authentic RealSound: A DSP Engineering Log

이 문서는 1980년대 후반 Access Software가 개발한 전설적인 **RealSound (PC Speaker PWM Audio)** 기술을 현대의 44.1kHz 디지털 오디오 환경에서 수학적으로 완벽하게 복원하기 위한 디지털 신호 처리(DSP) 엔지니어링 과정을 기록한 기술 백서입니다.

---

## 1. 하드웨어적 배경: RealSound의 원리 (The Hardware Context)

오리지널 IBM PC 내장 스피커는 인가된 전압에 따라 **0 (꺼짐)** 또는 **1 (켜짐)**의 상태만 가질 수 있는 순수한 1-Bit 물리 디바이스였습니다. 이 장치로 아날로그 파형(PCM)을 재생하기 위해 RealSound는 **Intel 8253 PIT (Programmable Interval Timer)**의 Timer 2 채널을 활용했습니다.

*   **Carrier Frequency:** PIT의 기본 클럭인 $1.193182 \text{ MHz}$를 64로 나누어, 약 **$18,643 \text{ Hz}$**의 고정 주기(Carrier)를 생성했습니다.
*   **Resolution:** 한 주기(64 클럭) 내에서 스피커가 켜져 있는 시간의 비율(Duty Cycle)을 $0 \sim 63$ 사이의 값으로 조절했습니다. 이는 물리적으로 **6-Bit ($2^6 = 64$) 해상도의 DAC**를 에뮬레이션하는 것과 같습니다.
*   **Demodulation (물리적 복원):** 2.25인치의 뻣뻣한 종이 콘(Paper cone) 스피커는 $18.6 \text{ kHz}$라는 고속 스위칭 주파수를 물리적으로 따라갈(vibrate) 관성이 부족했습니다. 따라서 스피커 콘 자체 거대한 **Low-Pass Filter (LPF)** 역할을 수행하여, 고주파 스위칭을 걸러내고 평균 전압(아날로그 신호)에 대응하는 위치에 머무르게 되었습니다.

우리의 목표는 이 **18.6kHz PWM 생성기**와 **아날로그 종이 스피커의 물리적 LPF**를 Java의 44.1kHz DSP 환경에서 에일리어싱(Aliasing) 없이 수학적으로 재현하는 것입니다.

---

## 2. 시행착오 1: First-Order Delta-Sigma (PDM)의 함정

초기 구현에서는 PWM 대신 오디오 공학에서 널리 쓰이는 **1차 델타-시그마 모듈레이터 (Pulse Density Modulation, PDM)**를 채택했습니다.

### 수학적 모델:
$$ y[n] = \text{sgn}(x[n] + e[n-1]) $$
$$ e[n] = x[n] + e[n-1] - y[n] $$

### 문제점: Quantization Noise & Idle Tones
PDM은 양자화 오차($e[n]$)를 누적하여 다음 프레임으로 넘기기 때문에(Noise Shaping), 수학적으로는 오리지널 PWM보다 더 정교하게 저주파 대역을 보존합니다. 하지만 이 피드백 루프는 필연적으로 막대한 양의 **고주파 백색 소음(High-frequency White Noise)**을 발생시킵니다. 
우리의 샘플 레이트가 44.1kHz에 불과했기 때문에, 이 노이즈가 가청 주파수 대역에 넓게 퍼지며 **'모래알이 구르는 듯한 지글거림(Sizzle)'**으로 들렸습니다.

또한, 입력 $x[n]$이 정확히 $0.0$일 때, 에러 누적기가 $+1$과 $-1$을 완벽하게 교차 출력하며 나이퀴스트 주파수($22.05 \text{ kHz}$)의 **Limit Cycle (Idle Tone)**을 형성하는 치명적인 결함이 발견되어 이 방식을 폐기했습니다.

---

## 3. 시행착오 2: Carrier PWM과 Nyquist Aliasing

원래의 하드웨어와 완벽히 동일하게 18.6kHz의 톱니파(Sawtooth Carrier)를 생성하고, 입력 신호와 교차(Intersect)시키는 **True Carrier PWM** 방식을 도입했습니다.

### 수학적 모델:
$$ c[n] = (c[n-1] + \Delta f) \bmod 2.0 - 1.0 \quad \text{where} \quad \Delta f = \frac{18600}{44100} \times 2 $$
$$ y[n] = \begin{cases} 1.0, & \text{if } x[n] > c[n] \\ -1.0, & \text{otherwise} \end{cases} $$

### 문제점: In-band Nyquist Fold-over
이 방식은 아날로그 회로에서는 완벽히 작동하지만, 이산 시간(Discrete-time) 도메인에서는 끔찍한 **에일리어싱(Aliasing)**을 발생시킵니다. 
$18.6 \text{ kHz}$의 사각파(Square wave)는 무한한 기수 배음(Odd Harmonics)을 갖습니다.
*   3차 배음: $18.6 \text{ kHz} \times 3 = 55.8 \text{ kHz}$
*   5차 배음: $18.6 \text{ kHz} \times 5 = 93.0 \text{ kHz}$

이 배음들은 44.1kHz 환경의 나이퀴스트 한계($22.05 \text{ kHz}$)를 초과하므로 가청 주파수 대역으로 강제 반사(Fold-over)됩니다.
$$ 55.8 \text{ kHz} \rightarrow 55.8 - 44.1 = \mathbf{11.7 \text{ kHz}} $$
$$ 93.0 \text{ kHz} \rightarrow 93.0 - (44.1 \times 2) = \mathbf{4.8 \text{ kHz}} $$

이 현상으로 인해 원래 음악에는 존재하지 않는 $4.8 \text{ kHz}$, $11.7 \text{ kHz}$의 불협화음이 발생했으며, 사용자에게는 **'금속을 긁는 쇳소리(Ring Modulation/Radio Static)'**로 인식되었습니다.

---

## 4. 최종 아키텍처: Oversampled FIR & Inter-stage Reconstruction

이러한 수학적 한계를 극복하기 위해, 3단계(3-Stage)로 분리된 오디오 렌더링 파이프라인을 최종 설계했습니다.

### Stage 1: Noise-Shaped Bitcrusher (N-bit Quantization)
단순 절삭(Truncation) 방식의 양자화는 심각한 고조파 왜곡(Harmonic Distortion)을 유발합니다. 이를 막기 위해 **First-order Leaky Delta-Sigma**를 양자화 단계에만 독립적으로 적용했습니다.
$$ \text{target} = x[n] + (E_{q}[n-1] \times 0.95) $$
$$ y_q[n] = \frac{\text{round}(\text{target} \times Q_{steps})}{Q_{steps}} \quad \text{where} \quad Q_{steps} = 2^{N-1} - 1 $$
이 과정은 사용자가 설정한 $N$ 비트 해상도(예: 6비트, 8비트)에서 발생하는 끔찍한 쇳소리를 부드러운 아날로그 테이프 히스(Hiss) 노이즈로 변환합니다.

### Stage 1.5: Inter-stage DAC Reconstruction Filter
낮은 비트 양자화로 인해 발생한 뾰족한 '계단(Staircase)' 파형이 후단의 PWM 톱니파와 부딪히면, 두 비선형 시스템이 곱해지면서 **상호변조 왜곡(Intermodulation Distortion)**을 일으킵니다.
이를 막기 위해, 고전적인 Amiga/SNES 스타일의 **2-pole IIR Low-Pass Filter**($\alpha = 0.45$)를 중간에 삽입하여 계단 모서리를 부드러운 아날로그 곡선으로 재건(Reconstruction)합니다.

### Stage 2 & 3: 32x Oversampled PWM & Virtual Acoustic LPF
에일리어싱을 원천 차단하기 위해 **32배 오버샘플링(Oversampling)**을 적용합니다. 
내부 클럭은 오리지널 하드웨어($1.19 \text{ MHz}$)를 초월하는 **$1.4112 \text{ MHz}$ ($44.1 \text{ kHz} \times 32$)**로 동작합니다.

1.  **Anti-Aliasing FIR (Boxcar) Filter:** 32번의 초고속 1-Bit PWM 스위칭 결과를 단순히 평균($\frac{1}{32}\sum$) 냅니다. 이는 수학적으로 주파수 스펙트럼에서 깊은 Null을 형성하는 FIR 필터로 작동하여, 다운샘플링(Decimation) 전에 발생하는 고주파 배음을 99% 억제합니다.
2.  **Virtual Paper Cone (Acoustic Filter):** 오리지널 PC 스피커의 좁은 주파수 응답을 재현합니다.
    *   **2-Stage Low-Pass ($\alpha=0.20$):** 뻣뻣한 종이 콘의 관성을 시뮬레이션하여 $~2.5 \text{ kHz}$ 이상의 PWM 캐리어와 날카로운 음색을 깎아냅니다.
    *   **1-Stage High-Pass ($\alpha=0.98$):** 2.25인치 스피커의 물리적 한계인 빈약한 저역대($~100 \text{ Hz}$ 이하)를 차단하여, 깡통 라디오 특유의 앵앵거리는 질감을 완성합니다.
3.  **Epsilon Noise Gate:** 부동소수점 오차가 점근선(Asymptote)을 형성하여 노이즈 게이트를 막는 현상을 피하기 위해, $|x| < 1\times10^{-5}$ 일 때 IIR 필터 상태를 $0$으로 강제 플러시(Flush)하여 완벽한 적막을 보장합니다.

## 결론
이러한 치밀한 DSP 설계와 Python 기반의 FFT 스펙트럼 증명을 통해, `midra`는 디지털 환경이 가진 근본적인 에일리어싱 한계를 극복하고, 1980년대 컴퓨터 오디오 공학의 금자탑이었던 RealSound를 그 어떠한 잡음 없이 가장 낭만적인 형태로 복원하는 데 성공했습니다.