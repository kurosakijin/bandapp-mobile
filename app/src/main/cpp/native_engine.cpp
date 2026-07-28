#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include <android/log.h>
#include <oboe/Oboe.h>

#include "get_dsp.h"
#include "container.h"
#include "json.hpp"

#define STB_VORBIS_NO_STDIO
#define STB_VORBIS_NO_PUSHDATA_API
#include "stb_vorbis.c"
#define TSF_IMPLEMENTATION
#include "tsf.h"

namespace {

constexpr int kInputBufferCapacity = 16384;
constexpr int kPitchWindowSize = 4096;
// Guitar Keys polyphonic tracking: 170ms ring (the analysis ring holds this
// much; mono/tuner paths keep reading only the newest kPitchWindowSize).
constexpr int kGkPolyWindow = 8192;
constexpr double kPi = 3.14159265358979323846;
constexpr double kTwoPi = 6.28318530717958647692;
constexpr int kNoDevice = -1;

enum Instrument {
    kElectricGuitar = 0,
    kBass = 1,
    kPiano = 2,
    kDrums = 3,
    kTuner = 4,
    kLoopMix = 5,
    kVocals = 6,
    kGuitarKeys = 7   // guitar audio -> MIDI-style piano (mono pitch-to-note)
};

// One scheduled note event from a parsed Standard MIDI File (time in ms).
struct SeqEvent {
    double t;
    uint8_t on;    // 1 = note on, 0 = note off
    uint8_t ch;
    uint8_t key;
    uint8_t vel;
};

static uint32_t midiReadBE(const uint8_t *p, int n) {
    uint32_t v = 0;
    for (int i = 0; i < n; ++i) v = (v << 8) | p[i];
    return v;
}

// Parse a Standard MIDI File (format 0/1) into a time-sorted note-event list.
// Tempo changes are resolved to absolute milliseconds. Bounds-checked for
// arbitrary user files.
static bool parseMidiFile(const uint8_t *d, size_t n, std::vector<SeqEvent> &out, double &totalMs) {
    if (n < 14 || std::memcmp(d, "MThd", 4) != 0) return false;
    uint32_t hlen = midiReadBE(d + 4, 4);
    int ntracks = static_cast<int>(midiReadBE(d + 10, 2));
    int division = static_cast<int>(midiReadBE(d + 12, 2));
    if (division & 0x8000) return false;            // SMPTE timing unsupported
    int tpqn = division ? division : 480;

    struct Raw { uint32_t tick; uint8_t kind; uint8_t ch, key, vel; uint32_t tempo; }; // kind: 0 off,1 on,2 tempo
    std::vector<Raw> raws;
    size_t pos = 8 + hlen;
    for (int trk = 0; trk < ntracks && pos + 8 <= n; ++trk) {
        if (std::memcmp(d + pos, "MTrk", 4) != 0) break;
        uint32_t tlen = midiReadBE(d + pos + 4, 4);
        size_t tend = pos + 8 + tlen;
        if (tend > n) tend = n;
        size_t p = pos + 8;
        uint32_t tick = 0;
        uint8_t running = 0;
        while (p < tend) {
            uint32_t delta = 0;
            while (p < tend) { uint8_t b = d[p++]; delta = (delta << 7) | (b & 0x7F); if (!(b & 0x80)) break; }
            tick += delta;
            if (p >= tend) break;
            uint8_t status = d[p];
            if (status & 0x80) { ++p; running = status; } else { status = running; }
            uint8_t cmd = status & 0xF0, ch = status & 0x0F;
            if (cmd == 0x90) {
                if (p + 2 > tend) break;
                uint8_t k = d[p], v = d[p + 1]; p += 2;
                raws.push_back({tick, static_cast<uint8_t>(v ? 1 : 0), ch, k, v, 0});
            } else if (cmd == 0x80) {
                if (p + 2 > tend) break;
                uint8_t k = d[p], v = d[p + 1]; p += 2;
                raws.push_back({tick, 0, ch, k, v, 0});
            } else if (cmd == 0xA0 || cmd == 0xB0 || cmd == 0xE0) {
                if (p + 2 > tend) break; p += 2;
            } else if (cmd == 0xC0 || cmd == 0xD0) {
                if (p + 1 > tend) break; p += 1;
            } else if (status == 0xFF) {
                if (p >= tend) break;
                uint8_t mt = d[p++];
                uint32_t len = 0;
                while (p < tend) { uint8_t b = d[p++]; len = (len << 7) | (b & 0x7F); if (!(b & 0x80)) break; }
                if (mt == 0x51 && len == 3 && p + 3 <= tend) {
                    raws.push_back({tick, 2, 0, 0, 0, midiReadBE(d + p, 3)});
                }
                p += len;
            } else if (status == 0xF0 || status == 0xF7) {
                uint32_t len = 0;
                while (p < tend) { uint8_t b = d[p++]; len = (len << 7) | (b & 0x7F); if (!(b & 0x80)) break; }
                p += len;
            } else {
                break;
            }
        }
        pos = tend;
    }

    std::stable_sort(raws.begin(), raws.end(), [](const Raw &a, const Raw &b) {
        if (a.tick != b.tick) return a.tick < b.tick;
        return a.kind == 2 && b.kind != 2;   // apply tempo before notes at the same tick
    });

    double ms = 0.0, msPerTick = 500000.0 / tpqn / 1000.0;   // default 120 BPM
    uint32_t curTick = 0;
    for (const Raw &r : raws) {
        ms += static_cast<double>(r.tick - curTick) * msPerTick;
        curTick = r.tick;
        if (r.kind == 2) {
            double tempo = r.tempo ? r.tempo : 500000.0;
            msPerTick = tempo / tpqn / 1000.0;
        } else {
            out.push_back({ms, r.kind, r.ch, r.key, r.vel});
        }
    }
    totalMs = ms;
    return !out.empty();
}

enum InputRoute {
    kRouteAuto = 0,
    kRouteMicrophone = 1,
    kRouteUsb = 2,
    kRouteMidi = 3
};

enum Tone {
    kGuitarClean = 0,
    kGuitarOverdrive = 1,
    kGuitarDistortion = 2,
    kGuitarMetal = 3,
    kGuitarFuzz = 4,
    kGuitarWah = 5,
    kGuitarChorus = 6,
    kGuitarHardMetal = 7,
    // Boss GT-1000 AIRD-style rigs. 10..23 are taken by bass/piano tones,
    // so the last two live above that range.
    kGuitarGtClean = 8,
    kGuitarGtCrunch = 9,
    kGuitarGtBrown = 30,
    kGuitarGtLead = 31,
    kBassClean = 10,
    kBassGrit = 11,
    kBassSynth = 12,
    kBassDoom = 13,
    kPianoFm = 20,
    kPianoElectric = 21,
    kPianoOrgan = 22,
    kPianoBell = 23
};

struct EngineConfig {
    int instrument = kElectricGuitar;
    int tone = kGuitarOverdrive;
    int inputRoute = 0;
    int inputDeviceId = kNoDevice;
    int outputDeviceId = kNoDevice;
};

float clampFloat(float value, float low, float high) {
    return std::max(low, std::min(high, value));
}

float onePoleLowPass(float input, float &state, float coefficient) {
    state += coefficient * (input - state);
    return state;
}

float softClip(float value) {
    return std::tanh(value);
}

float softKneeLimit(float value, float knee, float ceiling) {
    float magnitude = std::fabs(value);
    if (magnitude <= knee) return value;
    float width = std::max(0.001f, ceiling - knee);
    return std::copysign(knee + width * std::tanh((magnitude - knee) / width), value);
}

float hardClip(float value, float limit) {
    return clampFloat(value, -limit, limit);
}

// RBJ biquad (transposed direct form II) used to build the guitar cabinet /
// impulse-response voicings. Coefficients are normalized by a0 up front.
struct Biquad {
    float b0 = 1.0f, b1 = 0.0f, b2 = 0.0f, a1 = 0.0f, a2 = 0.0f;
    float z1 = 0.0f, z2 = 0.0f;
    inline float process(float x) {
        float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
    void reset() { z1 = 0.0f; z2 = 0.0f; }
    void setCoeffs(float B0, float B1, float B2, float A0, float A1, float A2) {
        b0 = B0 / A0; b1 = B1 / A0; b2 = B2 / A0; a1 = A1 / A0; a2 = A2 / A0;
    }
    void setHighpass(float sr, float f, float q) {
        float w = 2.0f * static_cast<float>(kPi) * f / sr;
        float cw = std::cos(w), sw = std::sin(w), al = sw / (2.0f * q);
        setCoeffs((1 + cw) * 0.5f, -(1 + cw), (1 + cw) * 0.5f, 1 + al, -2 * cw, 1 - al);
    }
    void setLowpass(float sr, float f, float q) {
        float w = 2.0f * static_cast<float>(kPi) * f / sr;
        float cw = std::cos(w), sw = std::sin(w), al = sw / (2.0f * q);
        setCoeffs((1 - cw) * 0.5f, 1 - cw, (1 - cw) * 0.5f, 1 + al, -2 * cw, 1 - al);
    }
    void setPeak(float sr, float f, float q, float dbGain) {
        float A = std::pow(10.0f, dbGain / 40.0f);
        float w = 2.0f * static_cast<float>(kPi) * f / sr;
        float cw = std::cos(w), sw = std::sin(w), al = sw / (2.0f * q);
        setCoeffs(1 + al * A, -2 * cw, 1 - al * A, 1 + al / A, -2 * cw, 1 - al / A);
    }
};

// Guitar speaker-cabinet / IR emulation: a short cascade of biquads whose
// magnitude response matches real miked cabs. The steep ~4-5 kHz rolloff is
// what turns a raw, "physical"/fizzy DI into a speaker-in-a-room tone.
struct GuitarCab {
    static constexpr int kMaxStages = 6;
    Biquad stage[kMaxStages];
    int stages = 0;
    int builtType = -1;
    float builtSr = 0.0f;
    float makeup = 1.0f;
    void configure(int type, float sr) {
        stages = 0;
        auto add = [&]() -> Biquad* { return &stage[stages++]; };
        switch (type) {
            default:
            case 0:  // 4x12 Modern (V30): tight, scooped, aggressive presence
                add()->setHighpass(sr, 85.0f, 0.72f);
                add()->setPeak(sr, 110.0f, 1.1f, 3.5f);
                add()->setPeak(sr, 500.0f, 0.9f, -5.0f);
                add()->setPeak(sr, 2300.0f, 1.6f, 3.5f);
                add()->setPeak(sr, 4000.0f, 2.2f, 4.0f);
                add()->setLowpass(sr, 5000.0f, 0.9f);
                makeup = 1.8f;
                break;
            case 1:  // 4x12 Vintage (Greenback): warm, woody, rounded top
                add()->setHighpass(sr, 90.0f, 0.7f);
                add()->setPeak(sr, 120.0f, 1.0f, 2.5f);
                add()->setPeak(sr, 400.0f, 0.9f, -3.0f);
                add()->setPeak(sr, 1700.0f, 1.4f, 2.5f);
                add()->setLowpass(sr, 4200.0f, 0.8f);
                makeup = 1.6f;
                break;
            case 2:  // 2x12 Combo (American clean): scooped, glassy, bright
                add()->setHighpass(sr, 95.0f, 0.7f);
                add()->setPeak(sr, 450.0f, 1.0f, -3.5f);
                add()->setPeak(sr, 3000.0f, 1.3f, 3.0f);
                add()->setLowpass(sr, 5800.0f, 0.9f);
                makeup = 1.5f;
                break;
            case 3:  // 1x12 Combo (Tweed): mid-forward small box
                add()->setHighpass(sr, 110.0f, 0.7f);
                add()->setPeak(sr, 800.0f, 0.9f, 3.5f);
                add()->setPeak(sr, 2000.0f, 1.2f, 1.5f);
                add()->setLowpass(sr, 5000.0f, 0.85f);
                makeup = 1.5f;
                break;
            case 4:  // 1x15 warm cab (also flatters bass): deep, dark
                add()->setHighpass(sr, 55.0f, 0.7f);
                add()->setPeak(sr, 100.0f, 1.0f, 2.0f);
                add()->setPeak(sr, 700.0f, 1.0f, -2.0f);
                add()->setLowpass(sr, 3500.0f, 0.8f);
                makeup = 1.4f;
                break;
        }
        builtType = type;
        builtSr = sr;
        for (int i = 0; i < stages; ++i) stage[i].reset();
    }
    inline float process(float x) {
        for (int i = 0; i < stages; ++i) x = stage[i].process(x);
        return x * makeup;
    }
    void reset() { for (int i = 0; i < kMaxStages; ++i) stage[i].reset(); }
};

// Hard cymbal choke: cymbal samples are long one-shots, so a normal note-off
// only starts their (slow) release and they keep ringing. Force-kill the
// matching voices via tsf_voice_endquick (~instant fade) — a real choke.
static void chokeCymbalVoices(tsf *f, int channel) {
    if (f == nullptr) return;
    static const int kCym[] = {49, 51, 52, 53, 55, 57, 59};
    for (int i = 0; i < f->voiceNum; ++i) {
        struct tsf_voice *v = &f->voices[i];
        if (v->playingPreset == -1 || v->playingChannel != channel) continue;
        for (int cn : kCym) {
            if (v->playingKey == cn) { tsf_voice_endquick(f, v); break; }
        }
    }
}

// Same-key retrigger: re-striking a key that is still sounding must replace its
// voice, not stack a second one under the first's release tail. Without this,
// fast repeats on one key pile up voices (muddy "retained" repeats and, at the
// limit, voice-pool exhaustion -> dropouts). Quick-fade any voice already
// playing this key on this channel just before the fresh attack (font-
// independent; the ~few-ms fade avoids the click of a hard cut).
static void chokeSameKey(tsf *f, int channel, int key) {
    if (f == nullptr) return;
    for (int i = 0; i < f->voiceNum; ++i) {
        struct tsf_voice *v = &f->voices[i];
        if (v->playingPreset == -1 || v->playingChannel != channel) continue;
        if (v->playingKey == key) tsf_voice_endquick(f, v);
    }
}

class InstrumentalEngine final : public oboe::AudioStreamCallback {
public:
    InstrumentalEngine() {
        for (auto &slot : layerFontSlot_) slot.store(-1);
    }

    bool start(const EngineConfig &config) {
        std::lock_guard<std::mutex> lock(streamMutex_);
        stopLocked();
        lastConfig_ = config;   // kept for auto-restart after route changes

        instrument_.store(config.instrument);
        tone_.store(config.tone);
        inputRoute_.store(config.inputRoute);
        inputDeviceId_.store(config.inputDeviceId);
        outputDeviceId_.store(config.outputDeviceId);

        std::shared_ptr<oboe::AudioStream> output;
        oboe::Result outputResult;
        if (config.outputDeviceId != kNoDevice) {
            // A specific external sink was chosen (USB DAC / audio interface,
            // Bluetooth, wired headset). Exclusive mode is only supported on the
            // built-in low-latency path, so requesting it on an external device
            // just fails — and that failure is why the keyboard/soundfont
            // instruments went silent on those sinks. Go straight to Shared,
            // which is what actually routes to them; only fall back to the
            // system default if the requested device can't be opened at all.
            outputResult = openOutputStream(
                    config.outputDeviceId, oboe::SharingMode::Exclusive, output);
            if (outputResult != oboe::Result::OK) {
                outputResult = openOutputStream(
                        config.outputDeviceId, oboe::SharingMode::Shared, output);
            }
            if (outputResult != oboe::Result::OK) {
                outputResult = openOutputStream(kNoDevice, oboe::SharingMode::Shared, output);
            }
        } else {
            outputResult = openOutputStream(kNoDevice, oboe::SharingMode::Exclusive, output);
            if (outputResult != oboe::Result::OK) {
                outputResult = openOutputStream(kNoDevice, oboe::SharingMode::Shared, output);
            }
        }
        if (outputResult != oboe::Result::OK || output == nullptr) {
            setStatus("Engine error: output stream failed");
            return false;
        }

        sampleRate_ = output->getSampleRate();
        if (sampleRate_ <= 0) {
            sampleRate_ = 48000;
        }

        int32_t burst = output->getFramesPerBurst();
        burstFrames_ = burst > 0 ? burst : 192;
        prevXRuns_ = 0;
        // Start at a comfortable 3-burst buffer; the callback grows it further if
        // the device reports underruns (each xrun is an audible "tak"/glitch).
        // Cap the adaptive growth at 16 bursts (~60-80 ms) — enough headroom
        // to silence underrun "tak"s on a busy device, while device capacity
        // (which can be seconds) no longer ratchets into half-second delay.
        int32_t cap = output->getBufferCapacityInFrames();
        int32_t maxBursts = config.inputRoute == kRouteMidi ? 12 : 8;
        maxBufFrames_ = cap > 0
                ? std::min(cap, burstFrames_ * maxBursts)
                : burstFrames_ * maxBursts;
        // MIDI instruments (drums, piano) read no audio input and are lighter,
        // so on the built-in sink they start at a tighter 1-burst buffer for the
        // lowest note→sound latency; live-input rigs start at 2. External sinks
        // (USB DAC/interface, Bluetooth) can't sustain a 1-burst buffer — they
        // underrun straight to silence, which is exactly why the keyboard had no
        // output on them — so any explicitly-chosen device gets a safe 3-burst
        // start instead. Adaptive growth (+ the ADPF hint) still lifts either.
        int32_t startBursts;
        if (config.inputRoute != kRouteMidi) {
            startBursts = 3;
        } else if (output->getSharingMode() == oboe::SharingMode::Exclusive) {
            startBursts = 1;
        } else if (config.outputDeviceId != kNoDevice) {
            startBursts = 2;
        } else {
            startBursts = config.inputRoute == kRouteMidi ? 1 : 2;
        }
        minBufFrames_ = std::min(burstFrames_ * startBursts, maxBufFrames_);
        stableCallbacks_ = 0;
        output->setBufferSizeInFrames(minBufFrames_);

        resetProcessor();
        {
            std::lock_guard<std::mutex> namLock(namLoadMutex_);
            nam::DSP *model = namModel_.load(std::memory_order_acquire);
            if (model != nullptr) {
                double expected = model->GetExpectedSampleRate();
                if (expected > 0.0
                        && std::fabs(expected - static_cast<double>(sampleRate_)) > 1.0) {
                    namEnabled_.store(false);
                } else {
                    try {
                        model->SetPrewarmOnReset(false);
                        model->Reset(static_cast<double>(sampleRate_), kNamBlockFrames);
                        model->prewarm();
                    } catch (...) {
                        namEnabled_.store(false);
                    }
                }
            }
        }

        // Loop Mix: preallocate the loop buffers off the audio thread (kept for
        // the whole session so loops survive leaving/re-entering the screen).
        if (config.instrument == kLoopMix) {
            for (int t = 0; t < kNumLoops; ++t) {
                size_t frames = static_cast<size_t>(kLoopSeconds[t])
                        * static_cast<size_t>(sampleRate_);
                if (loopBuf_[t].size() != frames * 2) {
                    loopBuf_[t].assign(frames * 2, 0.0f);
                    undoBuf_[t].assign(frames * 2, 0.0f);
                }
            }
        }

        std::shared_ptr<oboe::AudioStream> input;
        if (config.inputRoute != kRouteMidi) {
            oboe::Result inputResult = openInputStream(
                    config.inputDeviceId,
                    oboe::SharingMode::Exclusive,
                    sampleRate_,
                    input
            );
            if (inputResult != oboe::Result::OK) {
                inputResult = openInputStream(config.inputDeviceId, oboe::SharingMode::Shared, sampleRate_, input);
            }
            if (inputResult != oboe::Result::OK && config.inputDeviceId != kNoDevice) {
                inputResult = openInputStream(kNoDevice, oboe::SharingMode::Shared, sampleRate_, input);
            }
            if (inputResult != oboe::Result::OK || input == nullptr) {
                setStatus("Engine error: input stream failed");
                output->close();
                return false;
            }

            oboe::Result startInput = input->requestStart();
            if (startInput != oboe::Result::OK) {
                setStatus("Engine error: input start failed");
                input->close();
                output->close();
                return false;
            }
        }

        // Loop Mix only: a second capture stream for the instrument, so the
        // mic (vocals) and the line-in (loops 1-3) can be different devices.
        std::shared_ptr<oboe::AudioStream> inst;
        int instDevice = loopInstDevice_.load();
        if (config.instrument == kLoopMix && instDevice != kNoDevice) {
            // Accompaniment / line-in capture (a USB audio interface, a wired
            // line-in). Try the fast path first, then fall back to the normal
            // capture path — many USB interfaces reject low-latency input, and
            // without this retry the backing track was silently dropped.
            oboe::Result instResult = openInputStream(
                    instDevice, oboe::SharingMode::Shared, sampleRate_, inst, true);
            if (instResult != oboe::Result::OK || inst == nullptr) {
                instResult = openInputStream(
                        instDevice, oboe::SharingMode::Shared, sampleRate_, inst, false);
            }
            if (instResult == oboe::Result::OK && inst != nullptr) {
                if (inst->requestStart() != oboe::Result::OK) {
                    inst->close();
                    inst.reset();
                }
            } else {
                inst.reset();   // non-fatal: looper runs without the instrument
            }
        }

        inputStream_ = input;
        instStream_ = inst;
        outputStream_ = output;
        inputStreamRaw_.store(input == nullptr ? nullptr : input.get());
        instStreamRaw_.store(inst == nullptr ? nullptr : inst.get());

        oboe::Result startOutput = output->requestStart();
        if (startOutput != oboe::Result::OK) {
            setStatus("Engine error: output start failed");
            inputStreamRaw_.store(nullptr);
            if (input != nullptr) {
                input->close();
            }
            output->close();
            inputStream_.reset();
            outputStream_.reset();
            return false;
        }

        running_.store(true);
        std::ostringstream message;
        message << "Engine: running at " << sampleRate_ << " Hz";
        if (config.inputRoute == kRouteMidi) {
            message << " | MIDI input";
        } else if (config.inputDeviceId != kNoDevice) {
            message << " | input #" << config.inputDeviceId;
        }
        if (config.outputDeviceId != kNoDevice) {
            message << " | output #" << config.outputDeviceId;
        }
        setStatus(message.str());
        startPitchThread();
        return true;
    }

    ~InstrumentalEngine() override {
        stop();
        tsf *snd = sound_.exchange(nullptr);
        if (snd != nullptr) {
            tsf_close(snd);
        }
        tsf *s2 = sound2_.exchange(nullptr);
        if (s2 != nullptr) {
            tsf_close(s2);
        }
        for (int k = 0; k < kNumHqFonts; ++k) {
            tsf *f = hqFonts_[k].exchange(nullptr);
            if (f != nullptr) {
                tsf_close(f);
            }
        }
        for (int k = 0; k < kNumDrumFonts; ++k) {
            tsf *df = drumFonts_[k].exchange(nullptr);
            if (df != nullptr) {
                tsf_close(df);
            }
        }
    }

    void stop() {
        std::lock_guard<std::mutex> lock(streamMutex_);
        stopLocked();
    }

    void setPreset(const EngineConfig &config) {
        instrument_.store(config.instrument);
        tone_.store(config.tone);
        inputRoute_.store(config.inputRoute);
        inputDeviceId_.store(config.inputDeviceId);
        outputDeviceId_.store(config.outputDeviceId);
    }

    void noteOn(int note, float velocity) {
        float frequency = midiNoteToHz(note);
        if (frequency <= 0.0f) {
            return;
        }
        midiFrequency_.store(frequency);
        midiVelocity_.store(clampFloat(velocity, 0.05f, 1.0f));
        midiGate_.store(true);
        noteOnId_.fetch_add(1, std::memory_order_relaxed);
        pitchHz_.store(frequency);
        int vel = static_cast<int>(std::lround(clampFloat(velocity, 0.0f, 1.0f) * 127.0f));
        enqueueEvent(kEvNoteOn, note, vel);
    }

    void noteOff(int note) {
        float frequency = midiNoteToHz(note);
        float currentFrequency = midiFrequency_.load();
        if (std::fabs(currentFrequency - frequency) < 0.5f) {
            midiGate_.store(false);
        }
        enqueueEvent(kEvNoteOff, note, 0);
    }

    // Piano dual Sound 2: play directly on the layer channel (1), routed in
    // Java so a hardware keyboard always splits correctly.
    void note2On(int note, float velocity) {
        midiVelocity_.store(clampFloat(velocity, 0.05f, 1.0f));
        int vel = static_cast<int>(std::lround(clampFloat(velocity, 0.0f, 1.0f) * 127.0f));
        enqueueEvent(kEvNote2On, note, vel);
    }

    void note2Off(int note) {
        enqueueEvent(kEvNote2Off, note, 0);
    }

    void setMidiProgram(int program) {
        if (program < 0) {
            program = 0;
        }
        if (program > 127) {
            program = 127;
        }
        midiProgram_.store(program);
    }

    // Layered preset: a second GM program (channel 1) sounds with every note,
    // e.g. pizzicato stab + string pad ("Staccato Heaven"). -1 = off.
    void setMidiLayer(int program) {
        midiLayerProgram_.store(program > 127 ? 127 : program);
    }

    void setLoopKeysLayer(int program) {
        loopKeysLayer_.store(program > 127 ? 127 : program);
    }

    // Extra GM layers: A on GM channels 2/3/4, B on GM channels 5/6/7. -1 = off.
    void setLayer3(int program) { layer3Program_.store(program > 127 ? 127 : program); }
    void setLayer4(int program) { layer4Program_.store(program > 127 ? 127 : program); }
    void setLayer5(int program) { layer5Program_.store(program > 127 ? 127 : program); }
    void setLayer6(int program) { layer6Program_.store(program > 127 ? 127 : program); }
    void setLayer7(int program) { layer7Program_.store(program > 127 ? 127 : program); }
    void setLayer8(int program) { layer8Program_.store(program > 127 ? 127 : program); }
    void setLayerFontSlot(int channel, int slot) {
        if (channel < 0 || channel >= 8) return;
        layerFontSlot_[channel].store(
                slot >= 0 && slot < kNumHqFonts ? slot : -1);
    }

    // Per-channel blend level (0..1). idx = channel+1: 1=ch0..8=ch7.
    void setLayerVolume(int idx, float vol) {
        float v = vol < 0.0f ? 0.0f : (vol > 1.0f ? 1.0f : vol);
        if (idx == 1) layer1Vol_.store(v);
        else if (idx == 2) layer2Vol_.store(v);
        else if (idx == 3) layer3Vol_.store(v);
        else if (idx == 4) layer4Vol_.store(v);
        else if (idx == 5) layer5Vol_.store(v);
        else if (idx == 6) layer6Vol_.store(v);
        else if (idx == 7) layer7Vol_.store(v);
        else if (idx == 8) layer8Vol_.store(v);
    }

    // Mirror the main keyboard's notes onto the extra-layer channels (2 & 3),
    // one call per note event, so the layers stack under Sound 1.
    inline void extraLayersOn(tsf *gm, int note, float vel) {
        if (gm == nullptr) return;
        if (layer3Program_.load() >= 0) { chokeSameKey(gm, 2, note); tsf_channel_note_on(gm, 2, note, vel * 0.9f); }
        if (layer4Program_.load() >= 0) { chokeSameKey(gm, 3, note); tsf_channel_note_on(gm, 3, note, vel * 0.9f); }
        if (layer5Program_.load() >= 0) { chokeSameKey(gm, 4, note); tsf_channel_note_on(gm, 4, note, vel * 0.9f); }
    }
    inline void extraLayersOff(tsf *gm, int note) {
        if (gm == nullptr) return;
        tsf_channel_note_off(gm, 2, note);
        tsf_channel_note_off(gm, 3, note);
        tsf_channel_note_off(gm, 4, note);
    }
    inline void extraLayersAllOff(tsf *gm) {
        if (gm == nullptr) return;
        tsf_channel_note_off_all(gm, 2);
        tsf_channel_note_off_all(gm, 3);
        tsf_channel_note_off_all(gm, 4);
    }

    // Layer split (channels 4-7): the second stack mirrors the same 4 layer
    // sounds at side-B levels. side 0 = channels 0-3, side 1 = channels 4-7.
    void setLayerBlend(int mode, int note) {
        layerBlendMode_.store(mode);
        if (note >= 0) layerSplitNote_.store(note);
    }
    // Attack-zap depth for one physical layer channel (0..7), in semitones.
    void setLayerZap(int ch, int semis) {
        if (ch < 0 || ch > 7) return;
        layerZap_[ch].store(semis < 0 ? 0 : (semis > 36 ? 36 : semis));
    }
    // Fire the zap on any channel that just sounded (called from layerStackOn).
    inline void zapTrigger(int ch) {
        int d = layerZap_[ch].load();
        if (d > 0) { zapOff_[ch] = static_cast<float>(d); zapActive_[ch] = true; }
    }
    // Peak the mixer meter for a channel to the note velocity (called on note-on).
    inline void meterHit(int ch, float vel) { if (vel > meterEnv_[ch]) meterEnv_[ch] = vel; }
    // Track which notes are sounding per channel so the meter holds while held.
    inline void chanNoteOn(int ch, int n) {
        if (n >= 0 && n < 128 && !chanNote_[ch][n]) { chanNote_[ch][n] = true; chanActive_[ch]++; }
    }
    inline void chanNoteOff(int ch, int n) {
        if (n >= 0 && n < 128 && chanNote_[ch][n]) { chanNote_[ch][n] = false; if (chanActive_[ch] > 0) chanActive_[ch]--; }
    }
    inline void chanClear(int ch) {
        for (int n = 0; n < 128; ++n) chanNote_[ch][n] = false;
        chanActive_[ch] = 0;
    }
    // Copy the 8 live channel meters (0..1) out for the mixer UI.
    void getLayerMeters(float *out) { for (int c = 0; c < 8; ++c) out[c] = chanMeter_[c].load(); }

    inline tsf *layerFont(int channel, tsf *fallback) {
        int slot = layerFontSlot_[channel].load(std::memory_order_relaxed);
        if (slot < 0 || slot >= kNumHqFonts) return fallback;
        tsf *font = hqFonts_[slot].load(std::memory_order_acquire);
        return font != nullptr ? font : fallback;
    }

    inline int layerFontChannel(int channel, tsf *font, tsf *fallback) {
        return font != nullptr && font != fallback ? 0 : channel;
    }

    inline void layerNoteOn(int channel, tsf *fallback, int note, float velocity) {
        tsf *font = layerFont(channel, fallback);
        if (font == nullptr) return;
        int voiceChannel = layerFontChannel(channel, font, fallback);
        chokeSameKey(font, voiceChannel, note);
        tsf_channel_note_on(font, voiceChannel, note, velocity);
    }

    inline void layerNoteOff(int channel, tsf *fallback, int note) {
        tsf *font = layerFont(channel, fallback);
        if (font == nullptr) return;
        tsf_channel_note_off(font, layerFontChannel(channel, font, fallback), note);
    }

    inline void layerNotesOff(int channel, tsf *fallback) {
        tsf *font = layerFont(channel, fallback);
        if (font == nullptr) return;
        tsf_channel_note_off_all(font, layerFontChannel(channel, font, fallback));
    }

    // Two independent 4-layer keyboards. side 0 = Keyboard A (master = snd ch0,
    // adds L2/L3/L4 on gm ch2/3/4). side 1 = Keyboard B (master = alt ch1 /
    // Sound 2, adds L6/L7/L8 on gm ch5/6/7). A & B never share sounds.
    inline void layerStackOn(tsf *snd, tsf *alt, tsf *gm, int side, int note, float vel) {
        // Every layer channel fires at the SAME velocity; loudness balance is set
        // purely by each channel's fader volume (the mixer), so they stay equal.
        if (side == 0) {
            zapTrigger(0); meterHit(0, vel); chanNoteOn(0, note);
            layerNoteOn(0, snd, note, vel);
            if (layer3Program_.load() >= 0) { zapTrigger(2); meterHit(2, vel); chanNoteOn(2, note); layerNoteOn(2, gm, note, vel); }
            if (layer4Program_.load() >= 0) { zapTrigger(3); meterHit(3, vel); chanNoteOn(3, note); layerNoteOn(3, gm, note, vel); }
            if (layer5Program_.load() >= 0) { zapTrigger(4); meterHit(4, vel); chanNoteOn(4, note); layerNoteOn(4, gm, note, vel); }
        } else {
            if (alt != nullptr) { zapTrigger(1); meterHit(1, vel); chanNoteOn(1, note); layerNoteOn(1, alt, note, vel); }
            if (layer6Program_.load() >= 0) { zapTrigger(5); meterHit(5, vel); chanNoteOn(5, note); layerNoteOn(5, gm, note, vel); }
            if (layer7Program_.load() >= 0) { zapTrigger(6); meterHit(6, vel); chanNoteOn(6, note); layerNoteOn(6, gm, note, vel); }
            if (layer8Program_.load() >= 0) { zapTrigger(7); meterHit(7, vel); chanNoteOn(7, note); layerNoteOn(7, gm, note, vel); }
        }
    }
    inline void layerStackOffNote(tsf *snd, tsf *alt, tsf *gm, int note) {
        layerNoteOff(0, snd, note);
        layerNoteOff(1, alt, note);
        for (int c = 2; c <= 7; ++c) layerNoteOff(c, gm, note);
        for (int c = 0; c < 8; ++c) chanNoteOff(c, note);
    }
    inline void layerStackAllOff(tsf *snd, tsf *alt, tsf *gm) {
        layerNotesOff(0, snd);
        layerNotesOff(1, alt);
        for (int c = 2; c <= 7; ++c) layerNotesOff(c, gm);
        for (int c = 0; c < 8; ++c) chanClear(c);
    }
    // Release every channel of one side (for mono slide's re-attack).
    inline void layerStackSideAllOff(tsf *snd, tsf *alt, tsf *gm, int side) {
        if (side == 0) {
            layerNotesOff(0, snd);
            for (int c = 2; c <= 4; ++c) layerNotesOff(c, gm);
            chanClear(0); chanClear(2); chanClear(3); chanClear(4);
        } else {
            layerNotesOff(1, alt);
            for (int c = 5; c <= 7; ++c) layerNotesOff(c, gm);
            chanClear(1); chanClear(5); chanClear(6); chanClear(7);
        }
    }
    // One blended layer event. blend: 1 = single (all -> A), 2 = key-split (low =
    // A, high = B), 3 = two-manual (noteOn = A, note2On = B). When Slide is on,
    // each side runs its own glide stream (A = stream 1, B = stream 2) so a held
    // layer stack bends to the new note instead of re-attacking.
    void blendEvent(tsf *snd, tsf *alt, tsf *gm, int *e, int blend,
                    bool pedal, bool toggle, int64_t sustainSamples) {
        int note = e[1] & 0x7F;
        bool glide = glideOn_.load(std::memory_order_relaxed);
        bool mono = glide && glideMono_.load(std::memory_order_relaxed);
        if (e[0] == kEvNoteOn || e[0] == kEvNote2On) {
            pendingRelease_[note] = -1;
            float vel = static_cast<float>(e[2]) / 127.0f;
            if (vel > 0.8f) vel = 0.8f;
            int side;
            if (blend == 1) side = 0;
            else if (blend == 2) side = note < layerSplitNote_.load() ? 0 : 1;   // low = A
            else side = (e[0] == kEvNote2On) ? 1 : 0;                            // two-manual
            if (glide) {
                bool up = side == 1;
                // f = nullptr: glideOnHit only manages the stream; the render loop
                // applies the offset to ALL of the side's channels at once.
                bool legato = glideOnHit(up ? glide2Stack_ : glideStack_,
                        up ? glide2StackN_ : glideStackN_,
                        up ? glide2Anchor_ : glideAnchor_,
                        up ? glide2OffTarget_ : glideOffTarget_,
                        up ? glide2OffCur_ : glideOffCur_, nullptr, 0, note);
                if (!legato) {
                    if (mono) layerStackSideAllOff(snd, alt, gm, side);
                    layerStackOn(snd, alt, gm, side, note, vel);
                }
            } else {
                layerStackOn(snd, alt, gm, side, note, vel);
            }
        } else if (e[0] == kEvAllOff) {
            tsf_note_off_all(snd);
            if (alt != nullptr && alt != snd) tsf_note_off_all(alt);
            layerStackAllOff(snd, alt, gm);
            glideStackN_ = 0; glideAnchor_ = -1;
            glide2StackN_ = 0; glide2Anchor_ = -1;
            for (int n = 0; n < 128; ++n) pendingRelease_[n] = -1;
        } else if (glide) {   // note off while sliding: bend to remaining held key
            int side;
            if (blend == 1) side = 0;
            else if (blend == 2) side = note < layerSplitNote_.load() ? 0 : 1;
            else side = (e[0] == kEvNote2Off) ? 1 : 0;
            bool up = side == 1;
            int rel = glideOffHit(up ? glide2Stack_ : glideStack_,
                    up ? glide2StackN_ : glideStackN_,
                    up ? glide2Anchor_ : glideAnchor_,
                    up ? glide2OffTarget_ : glideOffTarget_, note);
            if (rel >= 0) layerStackOffNote(snd, alt, gm, rel);
        } else {   // note off (kEvNoteOff / kEvNote2Off)
            if (pedal) pendingRelease_[note] = kSustainHeld;
            else if (toggle) pendingRelease_[note] = sampleClock_ + sustainSamples;
            else layerStackOffNote(snd, alt, gm, note);
        }
    }

    // Dual sound: from this key upward, notes play only the channel-1 sound
    // (set via the layer program). -1 = plain layering (both sounds together).
    void setKeySplit(int note) {
        midiKeySplit_.store(note);
    }
    // Per-board routing (Full Keys): noteOn = Sound 1 only, note2On = Sound 2 only
    // — no auto-layering of the two sounds on a single noteOn.
    void setManualSplit(bool on) { manualSplit_.store(on); }

    // Sound 2 = an HQ font slot (custom/sampled fonts); -1 = GM program.
    void setDualFontSlot(int slot) {
        dualFontSlot_.store(slot);
    }

    void setLoopKeySplit(int note) {
        loopKeySplit_.store(note);
    }

    // On-screen note bender for the looper keys (0..16383, 8192 = center).
    void setLoopKeysBend(int value) {
        loopKeysBend_.store(value < 0 ? 0 : (value > 16383 ? 16383 : value));
    }

    // Bender range in semitones at full throw (1..24).
    void setBendRange(int semis) {
        bendRange_.store(semis < 1 ? 1 : (semis > 24 ? 24 : semis));
    }

    // Separate dual mode: these notes play sound 2 (channel 1) directly,
    // regardless of the key-split point.
    void loopKey2On(int note, float velocity) {
        int vel = static_cast<int>(std::lround(clampFloat(velocity, 0.0f, 1.0f) * 127.0f));
        enqueueEvent(kEvKey2On, note, vel);
    }

    void loopKey2Off(int note) {
        enqueueEvent(kEvKey2Off, note, 0);
    }

    void setDrumKit(int kit) {
        drumKit_.store(kit < 0 ? 0 : kit);
        appliedDrumKit_ = -1;
        appliedCustomMask_ = -1;
        appliedSelectedKit_ = -1;
    }

    // Genre note-remap over the drum pads: 0 = none, 1 = 808, 2 = reggae,
    // 3 = mambo/Latin, 4 = beatbox. Lets one base font voice several genre kits.
    void setDrumRemap(int id) {
        drumRemap_.store(id < 0 ? 0 : id);
    }

    // Whether the currently-selected kit's sound is loaded and ready to play
    // (so the UI can show a loading bar instead of the old GM-fallback swap).
    bool drumKitReady() {
        if (customDrum_.load()) {
            uint64_t slots = customSlotMask_.load();
            for (int slot = 0; slot < kNumDrumFonts; ++slot) {
                if ((slots & (1ULL << slot)) && drumFonts_[slot].load() == nullptr) {
                    return false;
                }
            }
            int selected = customSelectedKit_.load();
            int cleanSelected = selected >= kMetalDriveBase
                    ? selected - kMetalDriveBase : selected;
            bool selectedUsesGm = selected >= 0 && cleanSelected < kHqDrumBase;
            return (customGmMask_.load() == 0 && !selectedUsesGm)
                    || sound_.load() != nullptr;
        }
        int rawKit = drumKit_.load();
        int kit = rawKit >= kMetalDriveBase ? rawKit - kMetalDriveBase : rawKit;
        if (kit < kHqDrumBase) return sound_.load() != nullptr;   // GM kit: base font
        int slot = (kit - kHqDrumBase) / 100;
        if (slot < 0 || slot >= kNumDrumFonts) return true;
        return drumFonts_[slot].load() != nullptr;
    }

    // Whether a drum pad (GM note) actually maps to a sample in the current kit,
    // so the UI can grey out / disable pads a kit doesn't voice. Mirrors the
    // playback path: applies the genre remap, then checks the active preset's
    // region key-ranges. Returns true while a kit is still loading (don't flash
    // pads off during the load) and for custom kits (user-configured).
    bool drumNoteHasSound(int note) {
        if (note < 0 || note > 127) return false;
        if (customDrum_.load()) return true;
        int remap = drumRemap_.load(std::memory_order_relaxed);
        int rawKit = drumKit_.load();
        int kit = rawKit >= kMetalDriveBase ? rawKit - kMetalDriveBase : rawKit;
        int slot = -1, pre = 0;
        if (kit >= kHqDrumBase) { slot = (kit - kHqDrumBase) / 100; pre = (kit - kHqDrumBase) % 100; }
        bool wantsHq = (slot >= 0 && slot < kNumDrumFonts);
        tsf *df = wantsHq ? drumFonts_[slot].load() : nullptr;
        if (wantsHq && df == nullptr) return true;   // still loading
        if (remap == 0 && wantsHq && slot == k808Slot) remap = 1;
        switch (remap) {
            case 1: note = remap808(note); break;
            case 2: note = remapReggae(note); break;
            case 3: note = remapMambo(note); break;
            case 4: note = remapBeatbox(note); break;
            case 5: note = remapCongas(note); break;
            case 6: note = remapOneDrop(note); break;
            default: break;
        }
        tsf *snd = df ? df : sound_.load();
        if (snd == nullptr) return true;
        int idx;
        if (df) {
            int cnt = tsf_get_presetcount(df);
            idx = (pre < 0 || pre >= cnt) ? 0 : pre;
        } else {
            idx = tsf_get_presetindex(snd, 128, kit);
            if (idx < 0) idx = tsf_get_presetindex(snd, 128, 0);
        }
        if (idx < 0 || idx >= snd->presetNum) return true;
        note = kitSpecificDrumNote(slot, note);
        return presetHasNote(snd, idx, playableDrumNote(snd, idx, note));
    }

    // Loop Mix keys: melodic notes from the looper keyboard. They play on the
    // GM font's channel 0 and join the drum-pad bus, so takes print into
    // loops 1-3 exactly like pad hits.
    void loopKeyOn(int note, float velocity) {
        int vel = static_cast<int>(std::lround(clampFloat(velocity, 0.0f, 1.0f) * 127.0f));
        enqueueEvent(kEvKeyOn, note, vel);
    }

    void loopKeyOff(int note) {
        enqueueEvent(kEvKeyOff, note, 0);
    }

    // Panic: release every melodic voice. Fired when the selected sound (and
    // with it the note-fold range / layer channel) changes, so notes held
    // across the switch can't keep ringing with no matching note-off.
    void allNotesOff() {
        enqueueEvent(kEvAllOff, 0, 0);
    }

    void setLoopKeysProgram(int program) {
        loopKeysProg_.store(program < 0 ? 0 : (program > 127 ? 127 : program));
    }

    // Route the looper keys to an HQ piano font slot (-1 = GM font + program).
    void setLoopKeysSlot(int slot) {
        loopKeysSlot_.store(slot);
    }

    // Manual wah pedal on the guitar chain: on/off + sweep position 0..1.
    void setWah(bool on) {
        wahOn_.store(on);
    }

    void setWahPos(float pos) {
        wahPos_.store(clampFloat(pos, 0.0f, 1.0f));
    }

    // Guitar cabinet / IR pedal: on/off, cab voicing (0-4), dry↔cab blend.
    void setGuitarCab(bool on, int type, float mix) {
        cabOn_.store(on);
        cabType_.store(type);
        cabMix_.store(clampFloat(mix, 0.0f, 1.0f));
    }

    void setGuitarRackFx(bool compOn, float compAmount,
                         bool modOn, float modRate, float modDepth,
                         bool delayOn, float delayTime, float delayFeedback, float delayMix,
                         bool roomOn, float roomMix) {
        guitarCompOn_.store(compOn);
        guitarCompAmount_.store(clampFloat(compAmount, 0.0f, 1.0f));
        guitarModOn_.store(modOn);
        guitarModRate_.store(clampFloat(modRate, 0.0f, 1.0f));
        guitarModDepth_.store(clampFloat(modDepth, 0.0f, 1.0f));
        guitarDelayOn_.store(delayOn);
        guitarDelayTime_.store(clampFloat(delayTime, 0.0f, 1.0f));
        guitarDelayFeedback_.store(clampFloat(delayFeedback, 0.0f, 0.82f));
        guitarDelayMix_.store(clampFloat(delayMix, 0.0f, 0.65f));
        guitarRoomOn_.store(roomOn);
        guitarRoomMix_.store(clampFloat(roomMix, 0.0f, 0.55f));
    }

    void setBuiltInMetalRigFx(int style, float drive, float tone, float level,
                              float delayTime, float delayFeedback, float delayMix) {
        metalRigStyle_.store(style < 0 ? -1 : (style == 1 ? 1 : 0));
        metalBoostDrive_.store(clampFloat(drive, 0.0f, 1.0f));
        metalBoostTone_.store(clampFloat(tone, 0.0f, 1.0f));
        metalBoostLevel_.store(clampFloat(level, 0.0f, 1.0f));
        metalDelayTime_.store(clampFloat(delayTime, 0.0f, 1.0f));
        metalDelayFeedback_.store(clampFloat(delayFeedback, 0.0f, 0.78f));
        metalDelayMix_.store(clampFloat(delayMix, 0.0f, 0.55f));
    }

    // Global mono output: sum L+R on every instrument for mixer/PA rigs.
    void setMonoOutput(bool on) { monoOut_.store(on); }

    // Fire the one-shot chime (Drums screen). The audio thread ignores the
    // request while it is still sounding, so it can't be retriggered or stacked.
    void triggerChimes() { chimeTrigger_.store(true); }

    // A swell strikes the chosen cymbal five times, deliberately offset by
    // 5 ms. Retriggers are counted so rapid pad/MIDI hits start new overlapping
    // swells instead of restarting or replacing the one already ringing.
    void triggerSwell(int index) {
        if (index < 0 || index >= 6) return;
        int pending = swellPending_[index].load(std::memory_order_relaxed);
        while (pending < kMaxSwellGroups
                && !swellPending_[index].compare_exchange_weak(
                        pending, pending + 1, std::memory_order_release,
                        std::memory_order_relaxed)) {
        }
    }

    // Audition a single custom-kit source without touching the live kit routing.
    // `code` is a piece source code (HQ/extra slot, 200+drive, 100+GM); the right
    // font is rendered additively for a bounded window after each trigger, so the
    // Kit Mode sound picker can play a preview of any source it lists.
    void previewDrum(int code, int note) {
        if (code >= kPieceGmBase && code < kPieceGmBase + kGmKitCount) {
            previewGm_.store(code - kPieceGmBase);
            previewSlot_.store(-1);
        } else {
            previewGm_.store(-1);
            previewSlot_.store(code >= kPieceDriveBase ? code - kPieceDriveBase : code);
        }
        previewNote_.store(note & 0x7F);
        previewFrames_.store(sampleRate_ > 0 ? sampleRate_ * 2 : 96000);   // ~2 s window
        previewTrig_.store(true);
    }

    void mixDrumPreview(float *out, int32_t numFrames) {
        int gmIdx = previewGm_.load();
        int ps = previewSlot_.load();
        tsf *pf = nullptr;
        if (gmIdx >= 0 && gmIdx < kGmKitCount) pf = sound_.load();
        else if (ps >= 0 && ps < kNumDrumFonts) pf = drumFonts_[ps].load();
        if (pf == nullptr) return;
        int ch = gmIdx >= 0 ? kGmDrumChannel0 + gmIdx : 0;
        if (previewTrig_.exchange(false)) {
            if (gmIdx >= 0) tsf_channel_set_presetnumber(pf, ch, kGmPrograms[gmIdx], 1);
            else tsf_channel_set_presetindex(pf, ch, fullKitPreset(ps, pf));
            int note = previewNote_.load();
            if (gmIdx < 0) {
                note = kitSpecificDrumNote(ps, note);
                note = playableDrumNote(pf, fullKitPreset(ps, pf), note);
            }
            tsf_channel_note_on(pf, ch, note, 0.9f);
        }
        int left = previewFrames_.load();
        if (left <= 0) return;
        // Whether the live kit path already renders this same tsf this callback.
        // If so the preview note is heard through it — rendering again would
        // advance the synth twice (double-speed / doubled voices), so skip.
        bool custom = customDrum_.load();
        int rawKit = drumKit_.load();
        int kit = rawKit >= kMetalDriveBase ? rawKit - kMetalDriveBase : rawKit;
        bool live;
        if (gmIdx >= 0) {
            live = custom ? (customGmMask_.load() != 0) : (kit < kHqDrumBase);
        } else {
            int selSlot = kit >= kHqDrumBase ? (kit - kHqDrumBase) / 100 : -1;
            live = custom ? ((customSlotMask_.load() & (1ULL << ps)) != 0) : (selSlot == ps);
        }
        if (!live) tsf_render_float(pf, out, numFrames, 1);   // additive stereo
        left -= numFrames;
        previewFrames_.store(left < 0 ? 0 : left);
    }

    // Load the "Chimes" one-shot from a decoded PCM buffer (interleaved,
    // `channels` wide). Stored as stereo; mono sources are duplicated to both.
    void loadChimeSample(const float *data, int frames, int channels, int rate) {
        if (data == nullptr || frames <= 0 || channels <= 0) return;
        std::vector<float> buf(static_cast<size_t>(frames) * 2);
        for (int i = 0; i < frames; ++i) {
            float l = data[static_cast<size_t>(i) * channels];
            float r = channels >= 2 ? data[static_cast<size_t>(i) * channels + 1] : l;
            buf[static_cast<size_t>(i) * 2] = l;
            buf[static_cast<size_t>(i) * 2 + 1] = r;
        }
        chimeSample_ = std::move(buf);
        chimeSampleFrames_ = frames;
        chimeSampleRate_ = rate > 0 ? rate : 48000;
        chimeReady_.store(true, std::memory_order_release);   // publish after fill
    }

    void loadSwellSample(int index, const float *data, int frames, int channels, int rate) {
        if (index < 0 || index >= 6 || data == nullptr || frames <= 0 || channels <= 0) return;
        std::vector<float> buf(static_cast<size_t>(frames) * 2);
        for (int i = 0; i < frames; ++i) {
            float l = data[static_cast<size_t>(i) * channels];
            float r = channels >= 2 ? data[static_cast<size_t>(i) * channels + 1] : l;
            buf[static_cast<size_t>(i) * 2] = l;
            buf[static_cast<size_t>(i) * 2 + 1] = r;
        }
        swellSample_[index] = std::move(buf);
        swellSampleFrames_[index] = frames;
        swellSampleRate_[index] = rate > 0 ? rate : 48000;
        swellReady_[index].store(true, std::memory_order_release);
    }

    void setCustomDrum(bool on) {
        customDrum_.store(on);
        appliedCustomMask_ = -1;
        appliedGmMask_ = -1;
        appliedSelectedKit_ = -1;
        appliedDrumKit_ = -1;
    }

    void setDrumPieceSlot(int note, int code) {
        if (note < 0 || note > 127) {
            return;
        }
        drumPieceSlot_[note].store(code);
        uint64_t hqMask = 0, driveMask = 0;
        int gmMask = 0;
        int selectedKit = -1;
        for (int n = 0; n < 128; ++n) {
            int c = drumPieceSlot_[n].load();
            if (c >= kPieceSelectedBase) {
                int raw = c - kPieceSelectedBase;
                int clean = raw >= kMetalDriveBase ? raw - kMetalDriveBase : raw;
                selectedKit = raw;
                if (clean >= kHqDrumBase) {
                    int s = (clean - kHqDrumBase) / 100;
                    if (s >= 0 && s < kNumDrumFonts) {
                        hqMask |= (1ULL << s);
                        if (raw >= kMetalDriveBase) driveMask |= (1ULL << s);
                    }
                }
            } else if (c >= 0 && c < kNumDrumFonts) {
                hqMask |= (1ULL << c);
            } else if (c >= kPieceDriveBase && c < kPieceDriveBase + kNumDrumFonts) {
                int s = c - kPieceDriveBase;
                hqMask |= (1ULL << s);
                driveMask |= (1ULL << s);
            } else if (c >= kPieceGmBase && c < kPieceGmBase + kGmKitCount) {
                gmMask |= (1 << (c - kPieceGmBase));
            }
        }
        customSlotMask_.store(hqMask);
        customDriveMask_.store(driveMask);
        customGmMask_.store(gmMask);
        customSelectedKit_.store(selectedKit);
    }

    void setDrumPieceSrcNote(int note, int srcNote) {
        if (note < 0 || note > 127) {
            return;
        }
        drumPieceSrcNote_[note].store((srcNote >= 0 && srcNote < 128) ? srcNote : -1);
    }

    void setDrumPieceGain(int note, float gain) {
        if (note < 0 || note > 127) {
            return;
        }
        drumPieceGain_[note].store(clampFloat(gain, 0.0f, 1.4f));
    }

    void setDrumPiecePan(int note, float pan) {
        if (note < 0 || note > 127) {
            return;
        }
        drumPiecePan_[note].store(clampFloat(pan, 0.0f, 1.0f));
    }

    void setSustain(bool on) {
        sustainOn_.store(on);
    }

    void setSustainPedal(bool down) {
        sustainPedal_.store(down);
    }

    void setPitchWheel(int value) {
        int v = value < 0 ? 0 : (value > 16383 ? 16383 : value);
        pitchWheel_.store(v);    // side A
        pitchWheelB_.store(v);   // and side B: a single bender moves both
    }
    // Split benders: side A (Sound 1) and side B (Sound 2) move independently.
    void setPitchWheelA(int value) {
        pitchWheel_.store(value < 0 ? 0 : (value > 16383 ? 16383 : value));
    }
    void setPitchWheelB(int value) {
        pitchWheelB_.store(value < 0 ? 0 : (value > 16383 ? 16383 : value));
    }
    // Vibrato lever depth 0..1. setVibrato moves both sides; A/B move one.
    static float clampVib(float d) { return d < 0.0f ? 0.0f : (d > 1.0f ? 1.0f : d); }
    void setVibrato(float d)  { float v = clampVib(d); vibratoDepthA_.store(v); vibratoDepthB_.store(v); }
    void setVibratoA(float d) { vibratoDepthA_.store(clampVib(d)); }
    void setVibratoB(float d) { vibratoDepthB_.store(clampVib(d)); }

    void setReverb(bool on) {
        reverbOn_.store(on);
    }

    void setSustainTime(float seconds) {
        sustainSeconds_.store(clampFloat(seconds, 0.1f, 8.0f));
    }

    void setReverbLevel(float level) {
        reverbLevel_.store(clampFloat(level, 0.0f, 0.7f));
    }

    void setDrumRoom(float level) {
        drumRoom_.store(clampFloat(level, 0.0f, 0.5f));
    }

    // Noise gate threshold for guitar/bass (0 = off, ~0.005–0.06 useful).
    void setNoiseGate(float threshold) {
        gateThresh_.store(clampFloat(threshold, 0.0f, 0.5f));
    }

    // Per-group cymbal volume (0 = hi-hat, 1 = ride, 2 = crash).
    void setCymbalGain(int group, float g) {
        g = clampFloat(g, 0.0f, 3.0f);
        if (group == 0) cymGainHat_.store(g);
        else if (group == 1) cymGainRide_.store(g);
        else if (group == 2) cymGainCrash_.store(g);
    }

    // Choke all ringing cymbals on the next drum drain (edge/light pad touch).
    void chokeCymbals() { cymbalChoke_.store(true, std::memory_order_relaxed); }

    void setMidiVolume(int value) {
        ccVolume_.store(value < 0 ? 0 : (value > 127 ? 127 : value));
    }

    void setMidiExpression(int value) {
        ccExpression_.store(value < 0 ? 0 : (value > 127 ? 127 : value));
    }

    void setMetronome(bool on, int bpm, int beatsPerBar) {
        metronomeBpm_.store(bpm < 30 ? 30 : (bpm > 300 ? 300 : bpm));
        metroBeats_.store(beatsPerBar < 1 ? 1 : (beatsPerBar > 16 ? 16 : beatsPerBar));
        bool was = metronomeOn_.exchange(on);
        if (on && !was) {
            metroResetReq_.store(true);
        }
    }

    // Realign the click to beat 1 now (count-in before recording).
    void resetMetronome() { metroResetReq_.store(true); }

    void recordStart(const std::string &path) { startRecording(path); }
    void recordStop() { stopRecording(); }

    bool loadMidi(const void *data, int length) {
        std::vector<SeqEvent> tmp;
        double total = 0.0;
        if (!parseMidiFile(static_cast<const uint8_t *>(data), static_cast<size_t>(length), tmp, total)) {
            return false;
        }
        std::lock_guard<std::mutex> lock(seqMutex_);
        seq_ = std::move(tmp);
        seqPos_ = 0;
        seqMs_ = 0.0;
        seqTotalMs_ = total;
        seqLoaded_.store(true);
        seqPlaying_.store(false);
        seqFlushReq_.store(true);
        seqPositionMs_.store(0.0);
        return true;
    }

    void midiPlay() { if (seqLoaded_.load()) seqPlaying_.store(true); }
    void midiPause() { seqPlaying_.store(false); seqFlushReq_.store(true); }
    void midiStop() {
        std::lock_guard<std::mutex> lock(seqMutex_);
        seqPlaying_.store(false);
        seqPos_ = 0;
        seqMs_ = 0.0;
        seqPositionMs_.store(0.0);
        seqFlushReq_.store(true);
    }
    void midiSetLoop(bool on) { seqLoop_.store(on); }
    bool midiIsPlaying() const { return seqPlaying_.load(); }
    float midiPositionMs() const { return static_cast<float>(seqPositionMs_.load()); }
    float midiDurationMs() const { return static_cast<float>(seqTotalMs_); }
    // Bitmask of notes the MIDI-file player currently holds (for UI visualization).
    int64_t midiActiveLow() const { return static_cast<int64_t>(seqActiveLo_.load()); }
    int64_t midiActiveHigh() const { return static_cast<int64_t>(seqActiveHi_.load()); }

    void setPianoFx(float tone, float drive, float chorus, float trem) {
        fxTone_.store(clampFloat(tone, -1.0f, 1.0f));
        fxDrive_.store(clampFloat(drive, 0.0f, 1.0f));
        fxChorus_.store(clampFloat(chorus, 0.0f, 1.0f));
        fxTrem_.store(clampFloat(trem, 0.0f, 1.0f));
    }
    void setPianoSoft(float soft) { fxSoft_.store(clampFloat(soft, 0.0f, 1.0f)); }
    // Side-B (Sound 2) FX + level, for the independent Live Controls B.
    void setPianoFxB(float tone, float drive, float chorus, float trem) {
        fxToneB_.store(clampFloat(tone, -1.0f, 1.0f));
        fxDriveB_.store(clampFloat(drive, 0.0f, 1.0f));
        fxChorusB_.store(clampFloat(chorus, 0.0f, 1.0f));
        fxTremB_.store(clampFloat(trem, 0.0f, 1.0f));
    }
    void setPianoSoftB(float soft) { fxSoftB_.store(clampFloat(soft, 0.0f, 1.0f)); }
    void setLevelB(float lvl) { levelBCtl_.store(clampFloat(lvl, 0.0f, 1.0f)); }

    void loadSoundFont(const void *data, int length) {
        tsf *loaded = tsf_load_memory(data, length);
        if (loaded == nullptr) {
            return;
        }
        // -8.8 dB: measured on host so a max-velocity chord peaks ~0.70 (no clipping).
        tsf_set_output(loaded, TSF_STEREO_INTERLEAVED, sampleRate_, -8.8f);
        tsf_set_max_voices(loaded, 64);   // fixed pool + voice-stealing (no audio-thread realloc)
        tsf *old = sound_.exchange(loaded);
        if (old != nullptr) {
            tsf_close(old);
        }
        // A dedicated copy of the GM font for Sound 2 (Keyboard B). tsf_copy shares
        // the sample data (cheap), but gives Sound 2 its OWN render state so a GM
        // Sound 2 renders separately and Live Controls B stay independent of A.
        tsf *s2 = tsf_copy(loaded);
        if (s2 != nullptr) {
            tsf_set_output(s2, TSF_STEREO_INTERLEAVED, sampleRate_, -8.8f);
            tsf_set_max_voices(s2, 48);
        }
        tsf *oldS2 = sound2_.exchange(s2);
        if (oldS2 != nullptr) {
            tsf_close(oldS2);
        }
        appliedLayerProgram_ = -1;   // re-apply Sound 2 program onto the new copy
    }

    bool hasSoundFont() const {
        return sound_.load() != nullptr;
    }

    bool loadHqFont(int slot, int presetIndex, float gainDb, const void *data, int length) {
        if (slot < 0 || slot >= kNumHqFonts) {
            return false;
        }
        tsf *loaded = tsf_load_memory(data, length);
        if (loaded == nullptr) {
            return false;
        }
        // Per-font gain (hot fonts are pulled down to avoid clipping/harshness).
        tsf_set_output(loaded, TSF_STEREO_INTERLEAVED, sampleRate_, gainDb);
        tsf_set_max_voices(loaded, 96);   // piano chords + held pedal need headroom
        // Select by preset INDEX (set_presetnumber is unreliable on these fonts).
        int count = tsf_get_presetcount(loaded);
        if (presetIndex < 0 || presetIndex >= count) {
            presetIndex = 0;
        }
        tsf_channel_set_presetindex(loaded, 0, presetIndex);
        tsf *old = hqFonts_[slot].exchange(loaded);
        if (old != nullptr) {
            tsf_close(old);
        }
        return true;
    }

    bool hasHqFont(int slot) const {
        return slot >= 0 && slot < kNumHqFonts && hqFonts_[slot].load() != nullptr;
    }

    int hqFontPresetCount(int slot) const {
        tsf *font = slot >= 0 && slot < kNumHqFonts ? hqFonts_[slot].load() : nullptr;
        return font != nullptr ? tsf_get_presetcount(font) : 0;
    }

    std::string hqFontPresetName(int slot, int presetIndex) const {
        tsf *font = slot >= 0 && slot < kNumHqFonts ? hqFonts_[slot].load() : nullptr;
        if (font == nullptr || presetIndex < 0 || presetIndex >= font->presetNum) return "";
        const struct tsf_preset &preset = font->presets[presetIndex];
        std::ostringstream label;
        label << preset.presetName << "  [B" << preset.bank << " P" << preset.preset << "]";
        return label.str();
    }

    bool setHqFontPreset(int slot, int presetIndex) {
        tsf *font = slot >= 0 && slot < kNumHqFonts ? hqFonts_[slot].load() : nullptr;
        if (font == nullptr || presetIndex < 0 || presetIndex >= tsf_get_presetcount(font)) {
            return false;
        }
        tsf_channel_note_off_all(font, 0);
        tsf_channel_note_off_all(font, 1);
        tsf_channel_set_presetindex(font, 0, presetIndex);
        tsf_channel_set_presetindex(font, 1, presetIndex);
        return true;
    }

    bool loadDrumFont(int slot, float gainDb, const void *data, int length) {
        if (slot < 0 || slot >= kNumDrumFonts) {
            return false;
        }
        tsf *loaded = tsf_load_memory(data, length);
        if (loaded == nullptr) {
            return false;
        }
        // Per-kit gain so the differently-sampled kits play at a consistent level.
        tsf_set_output(loaded, TSF_STEREO_INTERLEAVED, sampleRate_, gainDb);
        tsf_set_max_voices(loaded, 48);   // fast rolls / many simultaneous hits
        tsf_channel_set_presetindex(loaded, 0, 0);
        tsf *old = drumFonts_[slot].exchange(loaded);
        if (old != nullptr) {
            tsf_close(old);
        }
        return true;
        return true;
    }
    void setPianoGuitarRig(bool onA, bool onB, int amp, int cab,
            float drive, float tone, float harmonics) {
        pianoGuitarRigA_.store(onA);
        pianoGuitarRigB_.store(onB);
        pianoGuitarAmp_.store(std::max(0, std::min(3, amp)));
        pianoGuitarCab_.store(std::max(0, std::min(4, cab)));
        pianoGuitarDrive_.store(clampFloat(drive, 0.0f, 1.0f));
        pianoGuitarTone_.store(clampFloat(tone, 0.0f, 1.0f));
        pianoGuitarHarmonics_.store(clampFloat(harmonics, 0.0f, 1.0f));
    }

    void setVirtualGuitarPlayer(bool on) {
        virtualGuitarPlayer_.store(on);
        if (!on) virtualGuitarReset_.store(true);
    }

    bool loadNamModel(const void *data, int length) {
        if (data == nullptr || length <= 0) return false;
        std::lock_guard<std::mutex> loadLock(namLoadMutex_);
        namExpectedRate_.store(0.0f);
        try {
            // container.cpp otherwise has no externally referenced symbol and
            // can be discarded from nam_core's static archive on Android.
            nam::container::ensure_registered();
            const char *begin = static_cast<const char *>(data);
            nlohmann::json config = nlohmann::json::parse(begin, begin + length);
            auto next = nam::get_dsp(config, nam::DspLoadOptions{false});
            if (next == nullptr || next->NumInputChannels() != 1
                    || next->NumOutputChannels() != 1) {
                return false;
            }
            double expected = next->GetExpectedSampleRate();
            int rate = sampleRate_ > 0 ? sampleRate_ : 48000;
            if (expected > 0.0 && std::fabs(expected - static_cast<double>(rate)) > 1.0) {
                namExpectedRate_.store(static_cast<float>(expected));
                return false;
            }
            next->SetPrewarmOnReset(false);
            next->Reset(static_cast<double>(rate), kNamBlockFrames);
            next->prewarm();

            nam::DSP *nextRaw = next.get();
            nam::DSP *oldRaw = namModel_.exchange(nextRaw, std::memory_order_acq_rel);
            while (oldRaw != nullptr
                    && namReaders_.load(std::memory_order_acquire) != 0) {
                std::this_thread::yield();
            }
            namOwned_ = std::move(next);
            namExpectedRate_.store(static_cast<float>(
                    expected > 0.0 ? expected : static_cast<double>(rate)));
            return true;
        } catch (const std::exception &e) {
            __android_log_print(ANDROID_LOG_WARN, "InstrumentalNAM",
                    "Model load failed: %s", e.what());
            return false;
        } catch (...) {
            __android_log_print(ANDROID_LOG_WARN, "InstrumentalNAM",
                    "Model load failed with an unknown error");
            return false;
        }
    }

    void setNam(bool enabled, float mix, float inputGain, float outputGain) {
        namEnabled_.store(enabled);
        namMix_.store(clampFloat(mix, 0.0f, 1.0f));
        namInputGain_.store(clampFloat(inputGain, 0.0f, 2.5f));
        namOutputGain_.store(clampFloat(outputGain, 0.05f, 4.0f));
    }

    bool loadNamIr(const float *data, int frames, int channels, int rate) {
        if (data == nullptr || frames <= 0 || channels <= 0 || rate <= 0) return false;
        int inactive = 1 - namIrActive_.load(std::memory_order_acquire);
        auto &target = namIr_[inactive];
        target.fill(0.0f);

        float peak = 0.0f;
        for (int i = 0; i < frames; ++i) {
            float mono = 0.0f;
            for (int ch = 0; ch < channels; ++ch) mono += data[i * channels + ch];
            peak = std::max(peak, std::fabs(mono / static_cast<float>(channels)));
        }
        if (peak < 1.0e-7f) return false;
        int first = 0;
        float trimThreshold = peak * 0.01f;
        for (; first < frames; ++first) {
            float mono = 0.0f;
            for (int ch = 0; ch < channels; ++ch) mono += data[first * channels + ch];
            if (std::fabs(mono / static_cast<float>(channels)) >= trimThreshold) break;
        }
        if (first >= frames) return false;

        int outRate = sampleRate_ > 0 ? sampleRate_ : 48000;
        double ratio = static_cast<double>(rate) / static_cast<double>(outRate);
        int available = frames - first;
        int taps = std::min(kNamIrTaps,
                std::max(1, static_cast<int>(std::ceil(available / ratio))));
        float energy = 0.0f;
        for (int i = 0; i < taps; ++i) {
            double source = static_cast<double>(first) + static_cast<double>(i) * ratio;
            int i0 = std::min(frames - 1, static_cast<int>(source));
            int i1 = std::min(frames - 1, i0 + 1);
            float frac = static_cast<float>(source - static_cast<double>(i0));
            float a = 0.0f, b = 0.0f;
            for (int ch = 0; ch < channels; ++ch) {
                a += data[i0 * channels + ch];
                b += data[i1 * channels + ch];
            }
            a /= static_cast<float>(channels);
            b /= static_cast<float>(channels);
            target[i] = a + (b - a) * frac;
            energy += target[i] * target[i];
        }
        if (energy < 1.0e-12f) return false;
        // L1/absolute-sum normalization made real cabinet IRs 20-30 dB too
        // quiet because their many alternating taps all counted positively.
        // Normalize convolution energy instead: static gain, no compressor or
        // auto-level behavior, so sustained notes do not get pulled downward.
        float norm = std::min(1.0f, 0.35f / std::sqrt(energy));
        for (int i = 0; i < taps; ++i) target[i] *= norm;
        namIrLength_[inactive].store(taps, std::memory_order_release);
        namIrActive_.store(inactive, std::memory_order_release);
        namIrReset_.store(true, std::memory_order_release);
        return true;
    }

    void setNamIr(bool enabled) {
        namIrEnabled_.store(enabled);
    }

    void setNamIrLevel(float level) {
        namIrLevel_.store(std::max(0.0f, std::min(1.5f, level)));
    }

    void setVirtualGuitarMode(bool enabled) {
        virtualGuitarMode_.store(enabled);
        virtualGuitarPolyGain_ = 1.0f;
    }

    void setVirtualGuitarOutput(float level) {
        virtualGuitarOutput_.store(clampFloat(level, 0.0f, 1.5f));
    }

    bool namIrReady() const {
        int active = namIrActive_.load(std::memory_order_acquire);
        return namIrLength_[active].load(std::memory_order_acquire) > 0;
    }

    bool namReady() const {
        return namModel_.load(std::memory_order_acquire) != nullptr;
    }

    float namExpectedRate() const {
        return namExpectedRate_.load();
    }

    void setFontSlot(int slot) {
        fontSlot_.store(slot);
    }

    void setControls(float control1, float control2, float control3, float control4, float control5, float control6) {
        control1_.store(clampFloat(control1, 0.0f, 1.0f));
        control2_.store(clampFloat(control2, 0.0f, 1.0f));
        control3_.store(clampFloat(control3, 0.0f, 1.0f));
        control4_.store(clampFloat(control4, 0.0f, 1.0f));
        control5_.store(clampFloat(control5, 0.0f, 1.0f));
        control6_.store(clampFloat(control6, 0.0f, 1.0f));
    }

    bool isRunning() const {
        return running_.load();
    }

    bool isRecordingActive() const {
        return recording_.load();
    }

    float outputLevelDb() const {
        return outputLevelDb_.load();
    }

    float inputLevelDb() const {
        return inputLevelDb_.load();
    }

    float outputLatencyMs() const {
        return outLatencyMs_.load();
    }

    float pitchHz() const {
        return pitchHz_.load();
    }

    std::string status() const {
        std::lock_guard<std::mutex> lock(statusMutex_);
        return status_;
    }

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames
    ) override {
        if (audioStream == nullptr || audioData == nullptr || numFrames <= 0) {
            return oboe::DataCallbackResult::Continue;
        }

        // Adaptive buffer: when the device reports new underruns, grow the buffer by
        // one burst (up to capacity). This eliminates the periodic "tak" glitches
        // without permanently paying the latency of a large fixed buffer.
        if (burstFrames_ > 0) {
            int32_t xruns = audioStream->getXRunCount().value();
            if (xruns > prevXRuns_) {
                prevXRuns_ = xruns;
                stableCallbacks_ = 0;
                int32_t want = audioStream->getBufferSizeInFrames() + burstFrames_;
                audioStream->setBufferSizeInFrames(std::min(want, maxBufFrames_));
            } else {
                ++stableCallbacks_;
            }
        }
        // Measured touch-free output latency (buffer + DSP + DAC), sampled
        // ~every 256 callbacks and surfaced in the UI status label.
        if ((++cbCount_ & 0xFF) == 0) {
            auto lat = audioStream->calculateLatencyMillis();
            if (lat.error() == oboe::Result::OK) {
                outLatencyMs_.store(static_cast<float>(lat.value()));
            }
        }

        auto *output = static_cast<float *>(audioData);
        int32_t framesToRead = std::min(numFrames, kInputBufferCapacity);
        int32_t framesRead = 0;
        oboe::AudioStream *inputStream = inputStreamRaw_.load();
        if (inputStream != nullptr) {
            // Bound the capture backlog: input queued far beyond a callback is
            // pure input→output delay (it piles up while the output stream
            // spins up). Hysteresis — only a real backlog (6+ callbacks)
            // triggers the drain, and it keeps 2 callbacks of audio — so
            // normal burst jitter never drops frames (dropped frames click).
            auto avail = inputStream->getAvailableFrames();
            if (avail.error() == oboe::Result::OK
                    && avail.value() > framesToRead * 8) {
                int32_t excess = avail.value() - framesToRead * 3;
                while (excess > 0) {
                    int32_t chunk = std::min(excess, framesToRead);
                    auto drop = inputStream->read(inputBuffer_.data(), chunk, 0);
                    if (drop.error() != oboe::Result::OK || drop.value() <= 0) {
                        break;
                    }
                    excess -= drop.value();
                }
            }
            oboe::ResultWithValue<int32_t> readResult = inputStream->read(
                    inputBuffer_.data(),
                    framesToRead,
                    0
            );
            if (readResult.error() == oboe::Result::OK) {
                framesRead = readResult.value();
            }
        }
        if (inputMute_.load(std::memory_order_relaxed)) {
            framesRead = 0;   // "input off": every path below sees pure silence
            inputTail_ = 0.0f;
        } else if (framesRead < framesToRead) {
            float tail = framesRead > 0 ? sanitize(inputBuffer_[framesRead - 1])
                                        : inputTail_;
            int32_t missing = framesToRead - framesRead;
            for (int32_t i = 0; i < missing; ++i) {
                inputBuffer_[framesRead + i] = tail
                        * (1.0f - static_cast<float>(i + 1)
                                / static_cast<float>(missing));
            }
            framesRead = framesToRead;
            inputTail_ = 0.0f;
        } else if (framesRead > 0) {
            inputTail_ = sanitize(inputBuffer_[framesRead - 1]);
        }
        // Instrument line-in (Loop Mix): read but never subject to the mic mute.
        int32_t instRead = 0;
        oboe::AudioStream *instStream = instStreamRaw_.load();
        if (instStream != nullptr) {
            auto iAvail = instStream->getAvailableFrames();
            if (iAvail.error() == oboe::Result::OK
                    && iAvail.value() > framesToRead * 6) {
                int32_t excess = iAvail.value() - framesToRead * 2;
                while (excess > 0) {
                    int32_t chunk = std::min(excess, framesToRead);
                    auto drop = instStream->read(instBuffer_.data(), chunk, 0);
                    if (drop.error() != oboe::Result::OK || drop.value() <= 0) {
                        break;
                    }
                    excess -= drop.value();
                }
            }
            oboe::ResultWithValue<int32_t> instResult = instStream->read(
                    instBuffer_.data(), framesToRead, 0);
            if (instResult.error() == oboe::Result::OK) {
                instRead = instResult.value();
            }
        }

        snapshotControls();
        float sumSquares = 0.0f;
        int instrument = instrument_.load();
        int tone = tone_.load();

        if (instrument == kTuner) {
            // Tuner: listen to the input (mic or USB), detect pitch, output silence.
            for (int32_t frame = 0; frame < numFrames; ++frame) {
                float in = frame < framesRead ? sanitize(inputBuffer_[frame]) : 0.0f;
                pushPitchSample(in);
                sumSquares += in * in;
                output[frame * 2] = 0.0f;
                output[frame * 2 + 1] = 0.0f;
            }
            updateMeter(sumSquares, numFrames);
            updateOutMeter(0.0f, numFrames * 2);
            return oboe::DataCallbackResult::Continue;
        }

        if (instrument == kVocals) {
            // Vocal FX rig: mic -> autotune / harmonizer -> output. No drums,
            // no loops — a plain vocal channel for singing through the app.
            int32_t n2 = numFrames * 2;
            for (int32_t frame = 0; frame < numFrames; ++frame) {
                float in = frame < framesRead ? sanitize(inputBuffer_[frame]) : 0.0f;
                sumSquares += in * in;
                float harm = 0.0f;
                float voice = in;
                bool hOn = harmOn_.load(std::memory_order_relaxed);
                bool tOn = harmTune_.load(std::memory_order_relaxed);
                if (hOn || tOn) {
                    harmAnalyze(in);
                    if (hOn) {
                        harm = softClip(harmVoicesOut())
                                * harmLevel_.load(std::memory_order_relaxed);
                    }
                    if (tOn) voice = harmTuneOut(in);
                }
                // One shared reverb over the whole vocal channel (dry + harmony),
                // amount set by the Vocals screen slider.
                float mixv = voice + harm;
                float rev = vocalRev_.load(std::memory_order_relaxed);
                if (rev > 0.001f) {
                    mixv += processReverb(0, mixv) * rev;
                }
                float s = softClip(mixv);
                output[frame * 2] = s;
                output[frame * 2 + 1] = s;
            }
            updateMeter(sumSquares, numFrames);
            float outSq = finalizeOutput(output, numFrames);
            pushRecording(output, numFrames);
            updateOutMeter(outSq, n2);
            return oboe::DataCallbackResult::Continue;
        }

        if (instrument == kGuitarKeys) {
            // Guitar → Keys: monophonic audio-to-MIDI. The input's detected
            // pitch plays the selected piano font live; only the converted
            // sound is heard (the guitar itself stays out of the output).
            int slotGk = fontSlot_.load();
            tsf *hfk = (slotGk >= 0 && slotGk < kNumHqFonts) ? hqFonts_[slotGk].load() : nullptr;
            tsf *snd = hfk != nullptr ? hfk : sound_.load();
            int32_t n2 = numFrames * 2;
            for (int32_t f = 0; f < n2; ++f) output[f] = 0.0f;
            if (snd != nullptr) {
                if (hfk == nullptr) {
                    int program = midiProgram_.load();
                    if (program != appliedProgram_) {
                        tsf_channel_set_presetnumber(snd, 0, program, 0);
                        appliedProgram_ = program;
                    }
                }
                // High-pass ~60 Hz before analysis: the raw (Unprocessed) mic
                // passes handling/room rumble that reads as random low notes
                // when nothing is played. Guitar low E (82 Hz) is untouched.
                float hpA = 1.0f / (1.0f + 2.0f * (float) kPi * 60.0f
                        / static_cast<float>(sampleRate_ > 0 ? sampleRate_ : 48000));
                float peak = 0.0f;
                for (int32_t frame = 0; frame < numFrames; ++frame) {
                    float raw = frame < framesRead ? sanitize(inputBuffer_[frame]) : 0.0f;
                    sumSquares += raw * raw;   // meter shows the true mic level
                    float in = hpA * (gkHpY_ + raw - gkHpX_);
                    gkHpX_ = raw;
                    gkHpY_ = in;
                    pushPitchSample(in);
                    float a = std::fabs(in);
                    if (a > peak) peak = a;
                }
                gkTrack(snd, peak, numFrames);
                tsf_render_float(snd, output, numFrames, 0);
            }
            float rev = vocalRev_.load(std::memory_order_relaxed);
            float outSq = 0.0f;
            for (int32_t frame = 0; frame < numFrames; ++frame) {
                float l = output[frame * 2] * 0.90f;
                float r = output[frame * 2 + 1] * 0.90f;
                if (rev > 0.001f) {
                    l += processReverb(0, l) * rev;
                    r += processReverb(1, r) * rev;
                }
                l = softClip(l);
                r = softClip(r);
                output[frame * 2] = l;
                output[frame * 2 + 1] = r;
                outSq += l * l + r * r;
            }
            updateMeter(sumSquares, numFrames);
            outSq = finalizeOutput(output, numFrames);
            pushRecording(output, numFrames);
            updateOutMeter(outSq, n2);
            return oboe::DataCallbackResult::Continue;
        }

        if (instrument == kLoopMix) {
            loopApplyGlobal();
            for (int t = 0; t < kNumLoops; ++t) {
                loopApplyCommand(t);
                loopMaintain(t);
            }
            // Drums for jamming into loops: same simple kit path as the drum screen.
            int rawKit = drumKit_.load();
            bool metal = rawKit >= kMetalDriveBase;
            int kit = metal ? rawKit - kMetalDriveBase : rawKit;
            int slot = -1, pre = 0;
            if (kit >= kHqDrumBase) {
                slot = (kit - kHqDrumBase) / 100;
                pre = (kit - kHqDrumBase) % 100;
            }
            tsf *gmf = sound_.load();
            bool wantsHq = slot >= 0 && slot < kNumDrumFonts;
            tsf *df = wantsHq ? drumFonts_[slot].load() : nullptr;
            bool useHq = df != nullptr;
            if (useHq) {
                int cnt = tsf_get_presetcount(df);
                if (pre < 0 || pre >= cnt) pre = 0;
            }
            tsf *snd = useHq ? df : gmf;
            int32_t n2 = numFrames * 2;
            for (int32_t f = 0; f < n2; ++f) output[f] = 0.0f;
            // Looper keys: an HQ piano font slot when set, otherwise the GM
            // font's channel 0 (drums keep channel 9 / their own font).
            int kslot = loopKeysSlot_.load();
            tsf *kfont = (kslot >= 0 && kslot < kNumHqFonts) ? hqFonts_[kslot].load() : nullptr;
            tsf *keys = kfont != nullptr ? kfont : gmf;
            if (keys != activeKeysFont_) {
                // Silence held notes on the font we're leaving (keys channel only).
                if (activeKeysFont_ != nullptr) tsf_channel_note_off_all(activeKeysFont_, 0);
                activeKeysFont_ = keys;
                appliedLoopKeysProg_ = -1;
                appliedLoopKeysBend_ = -1;   // new font: reapply bend + range
            }
            if (keys != nullptr && keys == gmf) {
                int kprog = loopKeysProg_.load();
                if (kprog != appliedLoopKeysProg_) {
                    tsf_channel_set_presetnumber(gmf, 0, kprog, 0);
                    appliedLoopKeysProg_ = kprog;
                }
            }
            // Channel 1 (layer / dual sound 2) lives on the GM font, so it also
            // works when the keys sound itself is an HQ piano font.
            tsf *altKeys = nullptr;
            if (gmf != nullptr) {
                int klayer = loopKeysLayer_.load();
                if (klayer != appliedLoopKeysLayer_) {
                    if (klayer >= 0) {
                        tsf_channel_set_presetnumber(gmf, 1, klayer, 0);
                    } else {
                        tsf_channel_note_off_all(gmf, 1);
                    }
                    appliedLoopKeysLayer_ = klayer;
                }
                if (klayer >= 0) altKeys = gmf;
            }
            // Sound 2 from an HQ font slot overrides the GM channel-1 program.
            {
                int aslot = dualFontSlot_.load();
                tsf *af = (aslot >= 0 && aslot < kNumHqFonts) ? hqFonts_[aslot].load() : nullptr;
                if (af != nullptr) {
                    if (af != appliedAltFont_) {
                        if (appliedAltFont_ != nullptr) tsf_channel_note_off_all(appliedAltFont_, 1);
                        tsf_channel_set_presetindex(af, 1, tsf_channel_get_preset_index(af, 0));
                        appliedAltFont_ = af;
                    }
                    altKeys = af;
                } else if (appliedAltFont_ != nullptr) {
                    tsf_channel_note_off_all(appliedAltFont_, 1);
                    appliedAltFont_ = nullptr;
                }
            }
            // On-screen note bender over the keys channels (range 1..12 st).
            if (keys != nullptr) {
                int kb = loopKeysBend_.load();
                int kbr = bendRange_.load();
                if (kb != appliedLoopKeysBend_ || kbr != appliedLoopBendRange_) {
                    tsf_channel_set_pitchrange(keys, 0, static_cast<float>(kbr));
                    tsf_channel_set_pitchwheel(keys, 0, kb);
                    if (altKeys != nullptr) {
                        tsf_channel_set_pitchrange(altKeys, 1, static_cast<float>(kbr));
                        tsf_channel_set_pitchwheel(altKeys, 1, kb);
                    }
                    appliedLoopKeysBend_ = kb;
                    appliedLoopBendRange_ = kbr;
                }
            }
            if (snd != nullptr || keys != nullptr) {
                if (snd != nullptr && useHq) {
                    if (rawKit != appliedDrumKit_) {
                        tsf_channel_set_presetindex(df, 0, pre);
                        appliedDrumKit_ = rawKit;
                    }
                } else if (snd != nullptr) {
                    // GM drum kit: snd == the keys' GM font, so its channel-9
                    // drum voice shares state with the keys on channel 0.
                    // Assert the drum bank every frame — a shared-font change
                    // must never leave channel 9 melodic (which played the
                    // drum notes as PIANO in the looper).
                    tsf_channel_set_presetnumber(gmf, 9, wantsHq ? 0 : kit, 1);
                    appliedDrumKit_ = wantsHq ? -1 : rawKit;
                }
                int remap = drumRemap_.load(std::memory_order_relaxed);
                if (remap == 0 && useHq && slot == k808Slot) remap = 1;
                drainDrumEvents(snd, useHq ? 0 : 9, remap, useHq ? slot : -1,
                        useHq ? pre : -1, keys, altKeys);
                // Looper keys slide: chase the glide target (see kPiano block).
                if (keys != nullptr) {
                    if (loopGlideOn_.load(std::memory_order_relaxed)) {
                        float step = glideRate_.load(std::memory_order_relaxed)
                                * static_cast<float>(numFrames)
                                / static_cast<float>(sampleRate_ > 0 ? sampleRate_ : 48000);
                        if (lkOffCur_ != lkOffTarget_) {
                            lkOffCur_ += clampFloat(lkOffTarget_ - lkOffCur_, -step, step);
                            tsf_channel_set_tuning(keys, 0, lkOffCur_);
                        }
                        // Sound 2 slides on its own stream (its own notes).
                        if (altKeys != nullptr && lk2OffCur_ != lk2OffTarget_) {
                            lk2OffCur_ += clampFloat(lk2OffTarget_ - lk2OffCur_, -step, step);
                            tsf_channel_set_tuning(altKeys, 1, lk2OffCur_);
                        }
                    } else if (lkOffCur_ != 0.0f || lk2OffCur_ != 0.0f) {   // mode off mid-bend
                        lkOffCur_ = 0.0f;
                        lkOffTarget_ = 0.0f;
                        lkAnchor_ = -1;
                        lkStackN_ = 0;
                        lk2OffCur_ = 0.0f;
                        lk2OffTarget_ = 0.0f;
                        lk2Anchor_ = -1;
                        lk2StackN_ = 0;
                        tsf_channel_set_tuning(keys, 0, 0.0f);
                        if (altKeys != nullptr) tsf_channel_set_tuning(altKeys, 1, 0.0f);
                    }
                }
                // Output is pre-zeroed, so all fonts mix in additively.
                if (snd != nullptr) tsf_render_float(snd, output, numFrames, 1);
                if (keys != nullptr && keys != snd) tsf_render_float(keys, output, numFrames, 1);
                if (altKeys != nullptr && altKeys != snd && altKeys != keys) {
                    tsf_render_float(altKeys, output, numFrames, 1);
                }
            }
            // Pads lead the looper mix now that the mic stays out of loops 1-3;
            // hot but safe — the output soft-clipper catches worst-case hits.
            float level = 1.60f;
            for (int32_t frame = 0; frame < numFrames; ++frame) {
                float in = frame < framesRead ? sanitize(inputBuffer_[frame]) : 0.0f;
                sumSquares += in * in;
                float harm = 0.0f;
                float voice = in;   // autotune replaces the dry voice everywhere
                bool hOn = harmOn_.load(std::memory_order_relaxed);
                bool tOn = harmTune_.load(std::memory_order_relaxed);
                if (hOn || tOn) {
                    harmAnalyze(in);
                    if (hOn) {
                        harm = softClip(harmVoicesOut())
                                * harmLevel_.load(std::memory_order_relaxed);
                        if (harmRev_.load(std::memory_order_relaxed)) {
                            harm += processReverb(0, harm) * 0.35f;
                        }
                    }
                    if (tOn) voice = harmTuneOut(in);
                }
                float dl = output[frame * 2] * level;
                float dr = output[frame * 2 + 1] * level;
                if (metal) { dl = drumMetal(0, dl); dr = drumMetal(1, dr); }
                // Record sources: the vocals track prints the ORIGINAL dry voice
                // (voice FX stay live-only); loops 1-3 print the drum pads plus
                // the dedicated instrument line-in, which is also audible live.
                float inst = frame < instRead ? sanitize(instBuffer_[frame]) : 0.0f;
                float srcL = dl + inst;
                float srcR = dr + inst;
                bool mon = loopMonitor_.load(std::memory_order_relaxed);
                float mixL = dl + harm + inst + (mon ? voice : 0.0f);
                float mixR = dr + harm + inst + (mon ? voice : 0.0f);
                loopTick(0, in, in, mixL, mixR);
                for (int t = 1; t < kNumLoops; ++t) {
                    loopTick(t, srcL, srcR, mixL, mixR);
                }
                float sl = softClip(mixL);
                float sr = softClip(mixR);
                output[frame * 2] = sl;
                output[frame * 2 + 1] = sr;
            }
            for (int t = 0; t < kNumLoops; ++t) {
                int len = loopLen_[t];
                loopPosNorm_[t].store(len > 0
                        ? static_cast<float>(loopPos_[t]) / static_cast<float>(len)
                        : 0.0f);
                loopLenShared_[t].store(len);
            }
            mixMetronome(output, numFrames);
            float outSq = finalizeOutput(output, numFrames);
            pushRecording(output, numFrames);
            updateMeter(sumSquares, numFrames);
            updateOutMeter(outSq, n2);
            return oboe::DataCallbackResult::Continue;
        }

        tsf *gm = sound_.load();
        if (instrument == kDrums && customDrum_.load()) {
            // Custom kit: each note plays from its assigned source; all used sources are mixed.
            uint64_t hqMask = customSlotMask_.load();
            uint64_t driveMask = customDriveMask_.load();
            int gmMask = customGmMask_.load();
            int selectedRaw = customSelectedKit_.load();
            int selectedKit = selectedRaw >= kMetalDriveBase
                    ? selectedRaw - kMetalDriveBase : selectedRaw;
            int selectedSlot = selectedKit >= kHqDrumBase
                    ? (selectedKit - kHqDrumBase) / 100 : -1;
            int selectedPreset = selectedSlot >= 0
                    ? (selectedKit - kHqDrumBase) % 100 : -1;
            if (hqMask != appliedCustomMask_ || selectedRaw != appliedSelectedKit_) {
                for (int s = 0; s < kNumDrumFonts; ++s) {
                    if (hqMask & (1ULL << s)) {
                        tsf *df = drumFonts_[s].load();
                        if (df != nullptr) {
                            tsf_channel_set_presetindex(df, 0, fullKitPreset(s, df));
                            if (s == selectedSlot) {
                                int count = tsf_get_presetcount(df);
                                int preset = selectedPreset >= 0 && selectedPreset < count
                                        ? selectedPreset : 0;
                                tsf_channel_set_presetindex(df, kSelectedKitChannel, preset);
                            }
                        }
                    }
                }
                appliedCustomMask_ = hqMask;
            }
            bool selectedUsesGm = selectedRaw >= 0 && selectedKit < kHqDrumBase;
            if (gm != nullptr
                    && (gmMask != appliedGmMask_ || selectedRaw != appliedSelectedKit_)) {
                for (int i = 0; i < kGmKitCount; ++i) {
                    if (gmMask & (1 << i)) {
                        tsf_channel_set_presetnumber(gm, kGmDrumChannel0 + i, kGmPrograms[i], 1);
                    }
                }
                if (selectedUsesGm) {
                    tsf_channel_set_presetnumber(
                            gm, kSelectedKitChannel, selectedKit, 1);
                }
                appliedGmMask_ = gmMask;
            }
            appliedSelectedKit_ = selectedRaw;
            drainCustomDrumEvents();
            int32_t n2 = numFrames * 2;   // interleaved stereo
            for (int32_t f = 0; f < n2; ++f) output[f] = 0.0f;
            float level = 1.05f + c6_ * 0.85f;
            float metalLevel = 0.50f + c6_ * 0.50f;
            float metalBusComp = metalLevel / std::max(level, 0.001f);
            // Clean HQ slots mix straight into the output (stereo interleaved).
            for (int s = 0; s < kNumDrumFonts; ++s) {
                if ((hqMask & (1ULL << s)) && !(driveMask & (1ULL << s))) {
                    tsf *df = drumFonts_[s].load();
                    if (df != nullptr) tsf_render_float(df, output, numFrames, 1);
                }
            }
            // Metal slots render to scratch, saturate, then add (per-slot drive).
            if (driveMask != 0 && n2 <= kInputBufferCapacity) {
                for (int s = 0; s < kNumDrumFonts; ++s) {
                    if (driveMask & (1ULL << s)) {
                        tsf *df = drumFonts_[s].load();
                        if (df == nullptr) continue;
                        for (int32_t f = 0; f < n2; ++f) mixScratch_[f] = 0.0f;
                        tsf_render_float(df, mixScratch_.data(), numFrames, 0);
                        for (int32_t f = 0; f < n2; ++f) {
                            // Standard Pad Mode uses the quieter metal bus.
                            // Match it here so Full Kit drive does not slam the
                            // final ceiling and turn into harsh digital fuzz.
                            output[f] += drumMetal(f & 1, mixScratch_[f]) * metalBusComp;
                        }
                    }
                }
            }
            // GM kits: every selected GM channel renders together.
            if (gm != nullptr && (gmMask != 0 || selectedUsesGm)) {
                tsf_render_float(gm, output, numFrames, 1);
            }
            // Keep clean sources linear. Their SoundFonts already carry
            // calibrated gain, and finalizeOutput supplies the shared ceiling.
            float room = drumRoom_.load();
            for (int32_t frame = 0; frame < numFrames; ++frame) {
                float l = output[frame * 2] * level;
                float r = output[frame * 2 + 1] * level;
                float sl = l + processReverb(0, l) * room;
                float sr = r + processReverb(1, r) * room;
                output[frame * 2] = sl;
                output[frame * 2 + 1] = sr;
                sumSquares += sl * sl + sr * sr;
            }
            mixDrumPreview(output, numFrames);
            mixChimes(output, numFrames);
            mixSwells(output, numFrames);
            mixMetronome(output, numFrames);
            float outSq = finalizeOutput(output, numFrames);
            pushRecording(output, numFrames);
            updateMeter(sumSquares, n2);
            updateOutMeter(outSq, n2);
            return oboe::DataCallbackResult::Continue;
        }
        if (instrument == kDrums) {
            int rawKit = drumKit_.load();
            bool metal = rawKit >= kMetalDriveBase;
            int kit = metal ? rawKit - kMetalDriveBase : rawKit;
            int slot = -1, pre = 0;
            if (kit >= kHqDrumBase) {
                slot = (kit - kHqDrumBase) / 100;
                pre = (kit - kHqDrumBase) % 100;
            }
            bool wantsHq = (slot >= 0 && slot < kNumDrumFonts);
            tsf *df = wantsHq ? drumFonts_[slot].load() : nullptr;
            bool useHq = df != nullptr;
            if (useHq) {
                int cnt = tsf_get_presetcount(df);
                if (pre < 0 || pre >= cnt) pre = 0;
            }
            // Never substitute Standard GM for a requested sampled kit. The UI
            // already exposes its loading state; playing a different kit here
            // makes the first strikes sound wrong and then change mid-session.
            if (wantsHq && !useHq) {
                int32_t n2 = numFrames * 2;
                for (int32_t f = 0; f < n2; ++f) output[f] = 0.0f;
                // Consume strikes made during loading instead of replaying a
                // burst of stale hits when the selected SoundFont becomes ready.
                drainDrumEvents(nullptr, 0, 0, -1, -1);
                mixDrumPreview(output, numFrames);
                mixChimes(output, numFrames);
                mixSwells(output, numFrames);
                mixMetronome(output, numFrames);
                float outSq = finalizeOutput(output, numFrames);
                pushRecording(output, numFrames);
                updateMeter(0.0f, n2);
                updateOutMeter(outSq, n2);
                return oboe::DataCallbackResult::Continue;
            }
            tsf *snd = useHq ? df : gm;
            if (snd != nullptr) {
                if (rawKit != appliedDrumKit_) {
                    if (useHq) {
                        tsf_channel_set_presetindex(df, 0, pre);
                    } else {
                        tsf_channel_set_presetnumber(gm, 9, wantsHq ? 0 : kit, 1);
                    }
                    appliedDrumKit_ = wantsHq && !useHq ? -1 : rawKit;
                }
                // HQ font is GM-mapped on bank 0 → play on channel 0; GM font uses drum channel 9.
                int remap = drumRemap_.load(std::memory_order_relaxed);
                if (remap == 0 && useHq && slot == k808Slot) remap = 1;
                drainDrumEvents(snd, useHq ? 0 : 9, remap, useHq ? slot : -1,
                        useHq ? pre : -1);
                tsf_render_float(snd, output, numFrames, 0);
                // Clean kits run hot (the fonts load with conservative gains and
                // sat far below the driven Metal kits); softClip catches peaks.
                // Metal keeps the old input scale — its saturation self-limits.
                float level = 1.05f + c6_ * 0.85f;
                float metalLevel = 0.50f + c6_ * 0.50f;
                float room = drumRoom_.load();
                for (int32_t frame = 0; frame < numFrames; ++frame) {
                    float xl = output[frame * 2] * (metal ? metalLevel : level);
                    float xr = output[frame * 2 + 1] * (metal ? metalLevel : level);
                    // Metal: saturate hard for an aggressive, compressed, gated edge.
                    float dl = metal ? drumMetal(0, xl) : xl;
                    float dr = metal ? drumMetal(1, xr) : xr;
                    float sl = dl + processReverb(0, dl) * room;
                    float sr = dr + processReverb(1, dr) * room;
                    output[frame * 2] = sl;
                    output[frame * 2 + 1] = sr;
                    sumSquares += sl * sl + sr * sr;
                }
                mixDrumPreview(output, numFrames);
                mixChimes(output, numFrames);
                mixSwells(output, numFrames);
                mixMetronome(output, numFrames);
                float outSq = finalizeOutput(output, numFrames);
                pushRecording(output, numFrames);
                updateMeter(sumSquares, numFrames * 2);
                updateOutMeter(outSq, numFrames * 2);
                return oboe::DataCallbackResult::Continue;
            }
        }
        int slot = fontSlot_.load();
        tsf *hf = (slot >= 0 && slot < kNumHqFonts) ? hqFonts_[slot].load() : nullptr;
        tsf *snd = hf != nullptr ? hf : gm;
        int effSlot = hf != nullptr ? slot : -1;
        if (instrument == kPiano && snd != nullptr) {
            if (effSlot != activeSlot_) {
                if (gm != nullptr) {
                    tsf_note_off_all(gm);
                }
                for (int k = 0; k < kNumHqFonts; ++k) {
                    tsf *f = hqFonts_[k].load();
                    if (f != nullptr) {
                        tsf_note_off_all(f);
                    }
                }
                activeSlot_ = effSlot;
                audioActiveNote_ = -1;
                appliedProgram_ = -1;
                appliedPitchWheel_ = -1;
                appliedCcVolume_ = -1;
                appliedCcExpression_ = -1;
                for (int n = 0; n < 128; ++n) {
                    pendingRelease_[n] = -1;
                }
            }
            sampleClock_ += numFrames;
            if (effSlot < 0) {
                int program = midiProgram_.load();
                if (program != appliedProgram_) {
                    tsf_channel_set_presetnumber(snd, 0, program, 0);
                    appliedProgram_ = program;
                }
            }
            // Sound 2 (channel 1) plays on its OWN dedicated GM instance (a copy of
            // the GM font) so it renders separately from Sound 1 and the layers —
            // this is what lets Live Controls B stay independent even for GM sounds.
            tsf *alt = nullptr;
            tsf *snd2 = sound2_.load();
            int layer = midiLayerProgram_.load();
            if (snd2 != nullptr && layer != appliedLayerProgram_) {
                if (layer >= 0) {
                    tsf_channel_set_presetnumber(snd2, 1, layer, 0);
                } else {
                    tsf_channel_note_off_all(snd2, 1);
                }
                appliedLayerProgram_ = layer;
            }
            if (snd2 != nullptr && layer >= 0) {
                alt = snd2;
            }
            // Extra layers 3 & 4 live on GM channels 2 & 3.
            if (gm != nullptr) {
                int l3 = layer3Program_.load();
                if (l3 != appliedLayer3_) {
                    if (l3 >= 0) tsf_channel_set_presetnumber(gm, 2, l3, 0);
                    else tsf_channel_note_off_all(gm, 2);
                    appliedLayer3_ = l3;
                }
                int l4 = layer4Program_.load();
                if (l4 != appliedLayer4_) {
                    if (l4 >= 0) tsf_channel_set_presetnumber(gm, 3, l4, 0);
                    else tsf_channel_note_off_all(gm, 3);
                    appliedLayer4_ = l4;
                }
                int l5 = layer5Program_.load();
                if (l5 != appliedLayer5_) {
                    if (l5 >= 0) tsf_channel_set_presetnumber(gm, 4, l5, 0);
                    else tsf_channel_note_off_all(gm, 4);
                    appliedLayer5_ = l5;
                }
                tsf_channel_set_volume(gm, 2, layer3Vol_.load());
                tsf_channel_set_volume(gm, 3, layer4Vol_.load());
                tsf_channel_set_volume(gm, 4, layer5Vol_.load());
            }
            // Sound 2 from an HQ font slot (Firefly, sampled pianos, library)
            // overrides the GM channel-1 program.
            int aslot = dualFontSlot_.load();
            tsf *af = (aslot >= 0 && aslot < kNumHqFonts) ? hqFonts_[aslot].load() : nullptr;
            if (af != nullptr) {
                if (af != appliedAltFont_) {
                    if (appliedAltFont_ != nullptr) tsf_channel_note_off_all(appliedAltFont_, 1);
                    tsf_channel_set_presetindex(af, 1, tsf_channel_get_preset_index(af, 0));
                    appliedAltFont_ = af;
                }
                alt = af;
            } else if (appliedAltFont_ != nullptr) {
                tsf_channel_note_off_all(appliedAltFont_, 1);
                appliedAltFont_ = nullptr;
            }
            tsf_channel_set_volume(snd, 0, layer1Vol_.load());
            if (alt != nullptr) tsf_channel_set_volume(alt, 1, layer2Vol_.load());
            // Keyboard B extra layers L6/L7/L8 on GM channels 5/6/7 (independent
            // sounds); only needed when a split is active (blend > 1).
            int blendNow = layerBlendMode_.load();
            if (blendNow != appliedBlend_) {
                if (blendNow == 0) {
                    layerStackAllOff(snd, alt, gm);
                    for (int c = 0; c < 8; ++c) {
                        if (!zapActive_[c] && zapOff_[c] == 0.0f) continue;
                        tsf *fallback = (c == 0) ? snd : (c == 1) ? alt : gm;
                        tsf *zi = layerFont(c, fallback);
                        if (zi != nullptr) {
                            tsf_channel_set_tuning(zi,
                                    layerFontChannel(c, zi, fallback), 0.0f);
                        }
                        zapActive_[c] = false; zapOff_[c] = 0.0f;
                    }
                }
                appliedBlend_ = blendNow;
            }
            if (gm != nullptr) {
                int l6 = layer6Program_.load();
                if (l6 != appliedLayer6_) {
                    if (l6 >= 0) tsf_channel_set_presetnumber(gm, 5, l6, 0);
                    else tsf_channel_note_off_all(gm, 5);
                    appliedLayer6_ = l6;
                }
                int l7 = layer7Program_.load();
                if (l7 != appliedLayer7_) {
                    if (l7 >= 0) tsf_channel_set_presetnumber(gm, 6, l7, 0);
                    else tsf_channel_note_off_all(gm, 6);
                    appliedLayer7_ = l7;
                }
                int l8 = layer8Program_.load();
                if (l8 != appliedLayer8_) {
                    if (l8 >= 0) tsf_channel_set_presetnumber(gm, 7, l8, 0);
                    else tsf_channel_note_off_all(gm, 7);
                    appliedLayer8_ = l8;
                }
                tsf_channel_set_volume(gm, 5, layer6Vol_.load());
                tsf_channel_set_volume(gm, 6, layer7Vol_.load());
                tsf_channel_set_volume(gm, 7, layer8Vol_.load());
            }
            {
                float layerVolume[8] = {layer1Vol_.load(), layer2Vol_.load(),
                        layer3Vol_.load(), layer4Vol_.load(), layer5Vol_.load(),
                        layer6Vol_.load(), layer7Vol_.load(), layer8Vol_.load()};
                for (int c = 2; c < 8; ++c) {
                    tsf *font = layerFont(c, gm);
                    if (font != nullptr && font != gm) {
                        tsf_channel_set_volume(font, 0, layerVolume[c]);
                    }
                }
            }
            drainEvents(snd, alt, gm);
            // Slide mode: chase the glide target a step per callback so the pitch
            // moves continuously (~60 semitones/second). Outside layer mode the
            // offset goes straight to snd ch0 / alt ch1; inside layer mode it is
            // applied per side to ALL of that side's channels in the loop below.
            int blendActive = layerBlendMode_.load();
            if (glideOn_.load(std::memory_order_relaxed)) {
                float step = glideRate_.load(std::memory_order_relaxed)
                        * static_cast<float>(numFrames)
                        / static_cast<float>(sampleRate_ > 0 ? sampleRate_ : 48000);
                if (glideOffCur_ != glideOffTarget_) {
                    glideOffCur_ += clampFloat(glideOffTarget_ - glideOffCur_, -step, step);
                    if (blendActive == 0) tsf_channel_set_tuning(snd, 0, glideOffCur_);
                }
                if (glide2OffCur_ != glide2OffTarget_) {
                    glide2OffCur_ += clampFloat(glide2OffTarget_ - glide2OffCur_, -step, step);
                    if (blendActive == 0 && alt != nullptr) tsf_channel_set_tuning(alt, 1, glide2OffCur_);
                }
            } else if (glideOffCur_ != 0.0f || glide2OffCur_ != 0.0f) {   // mode off mid-bend
                glideOffCur_ = 0.0f; glideOffTarget_ = 0.0f; glideAnchor_ = -1; glideStackN_ = 0;
                glide2OffCur_ = 0.0f; glide2OffTarget_ = 0.0f; glide2Anchor_ = -1; glide2StackN_ = 0;
                if (blendActive == 0) {
                    tsf_channel_set_tuning(snd, 0, 0.0f);
                    if (alt != nullptr) tsf_channel_set_tuning(alt, 1, 0.0f);
                }
            }
            // Layer mode: apply glide (per side) + attack-zap (per channel) as one
            // combined channel tuning, so slide and zap coexist across all 8 layers.
            if (blendActive > 0) {
                float zf = expf(-static_cast<float>(numFrames)
                        / (static_cast<float>(sampleRate_ > 0 ? sampleRate_ : 48000) * 0.016f));
                for (int c = 0; c < 8; ++c) {
                    tsf *fallback = (c == 0) ? snd : (c == 1) ? alt : gm;
                    tsf *zi = layerFont(c, fallback);
                    if (zi == nullptr) { zapActive_[c] = false; zapOff_[c] = 0.0f; continue; }
                    if (zapActive_[c]) {
                        zapOff_[c] *= zf;
                        if (zapOff_[c] < 0.02f) { zapOff_[c] = 0.0f; zapActive_[c] = false; }
                    }
                    bool sideB = (c == 1 || c >= 5);
                    float g = sideB ? glide2OffCur_ : glideOffCur_;
                    tsf_channel_set_tuning(zi,
                            layerFontChannel(c, zi, fallback), g + zapOff_[c]);
                }
            }
            // Pitch bend + vibrato: side A (snd ch0) and side B (alt ch1 / Sound 2)
            // each combine their own bend wheel with a shared ~5.5Hz LFO whose
            // depth is set per side. While vibrato is on we rewrite the wheel
            // every callback (base+LFO); a single bender sets both to the same.
            int pw = pitchWheel_.load();
            int pwB = pitchWheelB_.load();
            int br = bendRange_.load();
            float vibA = vibratoDepthA_.load();
            float vibB = vibratoDepthB_.load();
            if (vibA > 0.0f || vibB > 0.0f) {
                vibPhase_ += 2.0f * 3.14159265f * 5.5f * static_cast<float>(numFrames)
                        / static_cast<float>(sampleRate_ > 0 ? sampleRate_ : 48000);
                if (vibPhase_ > 6.2831853f) vibPhase_ -= 6.2831853f;
            }
            float vibS = sinf(vibPhase_);   // -1..1
            if (vibA > 0.0f) {
                float semis = vibS * vibA * 3.0f;   // up to ±3 semitones
                int eff = pw + static_cast<int>(semis / static_cast<float>(br) * 8192.0f);
                eff = eff < 0 ? 0 : (eff > 16383 ? 16383 : eff);
                tsf_channel_set_pitchrange(snd, 0, static_cast<float>(br));
                tsf_channel_set_pitchwheel(snd, 0, eff);
                appliedPitchWheel_ = -1;   // force a clean restore when vibrato stops
                appliedBendRange_ = br;
            } else if (pw != appliedPitchWheel_ || br != appliedBendRange_) {
                tsf_channel_set_pitchrange(snd, 0, static_cast<float>(br));
                tsf_channel_set_pitchwheel(snd, 0, pw);
                appliedPitchWheel_ = pw;
                appliedBendRange_ = br;
            }
            if (alt != nullptr && vibB > 0.0f) {
                float semis = vibS * vibB * 3.0f;
                int eff = pwB + static_cast<int>(semis / static_cast<float>(br) * 8192.0f);
                eff = eff < 0 ? 0 : (eff > 16383 ? 16383 : eff);
                tsf_channel_set_pitchrange(alt, 1, static_cast<float>(br));
                tsf_channel_set_pitchwheel(alt, 1, eff);
                appliedPitchWheelB_ = -1;
                appliedBendRangeB_ = br;
            } else if (alt != nullptr && (pwB != appliedPitchWheelB_ || br != appliedBendRangeB_)) {
                tsf_channel_set_pitchrange(alt, 1, static_cast<float>(br));
                tsf_channel_set_pitchwheel(alt, 1, pwB);
                appliedPitchWheelB_ = pwB;
                appliedBendRangeB_ = br;
            }
            // The six added Layer Mode voices use GM channels 2..7 or their
            // own external SF2 slot. They must follow the same physical lever
            // as their keyboard master: L2/L3/L4 follow side A, while
            // L6/L7/L8 follow side B.
            if (blendActive > 0) {
                for (int c = 2; c < 8; ++c) {
                    bool sideB = c >= 5;
                    int baseWheel = sideB ? pwB : pw;
                    float depth = sideB ? vibB : vibA;
                    int effectiveWheel = baseWheel;
                    if (depth > 0.0f) {
                        float semis = vibS * depth * 3.0f;
                        effectiveWheel += static_cast<int>(
                                semis / static_cast<float>(br) * 8192.0f);
                        effectiveWheel = effectiveWheel < 0 ? 0
                                : (effectiveWheel > 16383 ? 16383 : effectiveWheel);
                    }
                    tsf *layer = layerFont(c, gm);
                    if (layer == nullptr) continue;
                    int voiceChannel = layerFontChannel(c, layer, gm);
                    tsf_channel_set_pitchrange(
                            layer, voiceChannel, static_cast<float>(br));
                    tsf_channel_set_pitchwheel(
                            layer, voiceChannel, effectiveWheel);
                }
            }
            int vol = ccVolume_.load();
            if (vol != appliedCcVolume_) {
                tsf_channel_midi_control(snd, 0, 7, vol);
                if (alt != nullptr) tsf_channel_midi_control(alt, 1, 7, vol);
                appliedCcVolume_ = vol;
            }
            int expr = ccExpression_.load();
            if (expr != appliedCcExpression_) {
                tsf_channel_midi_control(snd, 0, 11, expr);
                if (alt != nullptr) tsf_channel_midi_control(alt, 1, 11, expr);
                appliedCcExpression_ = expr;
            }
            advanceMidiPlayback(snd, numFrames);
            if (inputRoute_.load() == kRouteUsb) {
                for (int32_t frame = 0; frame < numFrames; ++frame) {
                    float input = frame < framesRead ? inputBuffer_[frame] : 0.0f;
                    pushPitchSample(sanitize(input));
                }
                trackPitchNote(snd);
            }
            tsf_render_float(snd, output, numFrames, 0);   // Side A (Sound 1) into output
            if (virtualGuitarPlayer_.load(std::memory_order_relaxed)) {
                tsf *palm = hqFonts_[kVirtualGuitarPalmSlot].load();
                tsf *harm = hqFonts_[kVirtualGuitarHarmSlot].load();
                if (palm != nullptr && palm != snd) {
                    tsf_render_float(palm, output, numFrames, 1);
                }
                if (harm != nullptr && harm != snd && harm != palm) {
                    tsf_render_float(harm, output, numFrames, 1);
                }
            }
            // Side B (Sound 2) renders to its own buffer ONLY when it's a distinct
            // instance (HQ font). When Sound 2 is a GM program it shares the gm
            // instance with the layers and can't be split — it mixes into A.
            bool splitB = (alt != nullptr && alt != snd && alt != gm);
            int stereoN = numFrames * 2;
            if (splitB && stereoN <= static_cast<int>(fxBufB_.size())) {
                for (int i = 0; i < stereoN; ++i) fxBufB_[i] = 0.0f;
                tsf_render_float(alt, fxBufB_.data(), numFrames, 0);
            } else {
                splitB = false;
                if (alt != nullptr && alt != snd) tsf_render_float(alt, output, numFrames, 1);
            }
            // Layers 3 & 4 render from the GM font (channels 2 & 3). Skip if the
            // GM font was already rendered above as Sound 2 (alt == gm).
            if (gm != nullptr && gm != snd && alt != gm
                    && (layer3Program_.load() >= 0 || layer4Program_.load() >= 0
                        || layer5Program_.load() >= 0 || layer6Program_.load() >= 0
                        || layer7Program_.load() >= 0 || layer8Program_.load() >= 0)) {
                tsf_render_float(gm, output, numFrames, 1);
            }
            for (int c = 2; c < 8; ++c) {
                tsf *layer = layerFont(c, gm);
                if (layer != nullptr && layer != gm && layer != snd && layer != alt) {
                    tsf_render_float(layer, output, numFrames, 1);
                }
            }
            // Mixer visualization reads the signal each SoundFont actually
            // rendered this callback. This follows its waveform, envelope,
            // release tail and real loudness instead of estimating from MIDI
            // velocity or whether a note is held.
            for (int c = 0; c < 8; ++c) {
                tsf *fallback = c == 0 ? snd : (c == 1 ? alt : gm);
                tsf *font = layerFont(c, fallback);
                float rms = font == nullptr ? 0.0f
                        : tsf_channel_get_meter(
                                font, layerFontChannel(c, font, fallback));
                float db = 20.0f * log10f(rms + 0.0000001f);
                float target = clampFloat((db + 60.0f) / 60.0f, 0.0f, 1.0f);
                float previous = chanMeter_[c].load(std::memory_order_relaxed);
                float shown = target >= previous
                        ? target : previous * 0.84f + target * 0.16f;
                if (shown < 0.002f) shown = 0.0f;
                chanMeter_[c].store(shown, std::memory_order_relaxed);
            }
            float level = 0.45f + c6_ * 0.50f;
            float levelB = 0.45f + levelBCtl_.load() * 0.50f;
            bool reverb = reverbOn_.load();
            if (reverb && !reverbWasOn_) {
                for (int ch = 0; ch < 2; ++ch) {
                    for (int k = 0; k < 4; ++k) {
                        combStore_[ch][k] = 0.0f;
                        for (int i = 0; i < 1800; ++i) combBuf_[ch][k][i] = 0.0f;
                    }
                    for (int k = 0; k < 2; ++k) {
                        for (int i = 0; i < 700; ++i) apBuf_[ch][k][i] = 0.0f;
                    }
                }
            }
            reverbWasOn_ = reverb;
            float revSend = reverbLevel_.load();
            for (int32_t frame = 0; frame < numFrames; ++frame) {
                float aL = processPianoFx(output[frame * 2], 0) * level;
                float aR = processPianoFx(output[frame * 2 + 1], 1) * level;
                if (pianoGuitarRigA_.load(std::memory_order_relaxed)) {
                    aL = processPianoGuitarRig(aL, 0, 0);
                    aR = processPianoGuitarRig(aR, 0, 1);
                }
                float bL = 0.0f, bR = 0.0f;
                if (splitB) {
                    bL = processPianoFxB(fxBufB_[frame * 2], 0) * levelB;
                    bR = processPianoFxB(fxBufB_[frame * 2 + 1], 1) * levelB;
                    if (pianoGuitarRigB_.load(std::memory_order_relaxed)) {
                        bL = processPianoGuitarRig(bL, 1, 0);
                        bR = processPianoGuitarRig(bR, 1, 1);
                    }
                }
                float dryL = softClip(aL + bL);
                float dryR = softClip(aR + bR);
                output[frame * 2] = dryL;
                output[frame * 2 + 1] = dryR;
            }
            processNam(output, numFrames);
            for (int32_t frame = 0; frame < numFrames; ++frame) {
                float dryL = output[frame * 2];
                float dryR = output[frame * 2 + 1];
                float sl = reverb
                        ? softClip(dryL + processReverb(0, dryL) * revSend) : dryL;
                float sr = reverb
                        ? softClip(dryR + processReverb(1, dryR) * revSend) : dryR;
                float finalLevel = virtualGuitarMode_.load(std::memory_order_relaxed)
                        ? virtualGuitarOutput_.load(std::memory_order_relaxed) : 1.0f;
                output[frame * 2] = softKneeLimit(sl * finalLevel, 0.88f, 1.12f);
                output[frame * 2 + 1] = softKneeLimit(sr * finalLevel, 0.88f, 1.12f);
                sumSquares += sl * sl + sr * sr;
            }
            mixMetronome(output, numFrames);
            float outSq = finalizeOutput(output, numFrames);
            pushRecording(output, numFrames);
            updateMeter(sumSquares, numFrames * 2);
            updateOutMeter(outSq, numFrames * 2);
            return oboe::DataCallbackResult::Continue;
        }

        float gateThr = gateThresh_.load(std::memory_order_relaxed);
        for (int32_t frame = 0; frame < numFrames; ++frame) {
            float input = frame < framesRead ? inputBuffer_[frame] : 0.0f;
            input = sanitize(input);
            pushPitchSample(input);
            if (instrument == kElectricGuitar) {
                // Rack Input is the first stage and remains effective when NAM
                // or its cabinet is bypassed. It is intentionally pre-gate.
                input *= namInputGain_.load(std::memory_order_relaxed);
            }
            sumSquares += input * input;

            // Noise gate on the DRY input: idle hiss/hum (which high-gain amp
            // sims blow up) is muted between notes, but a real note opens it
            // instantly and its tail rings out. Hysteresis stops chattering.
            // Guitar/bass only — piano is never gated.
            if (gateThr > 0.0f && instrument != kPiano) {
                float ae = std::fabs(input);
                gateEnv_ = ae > gateEnv_ ? ae : gateEnv_ * gateEnvRel_;
                if (gateEnv_ > gateThr) gateState_ = true;
                else if (gateEnv_ < gateThr * 0.5f) gateState_ = false;
                float target = gateState_ ? 1.0f : 0.0f;
                gateGain_ += (target - gateGain_)
                        * (target > gateGain_ ? gateAtt_ : gateRel_);
                input *= gateGain_;
            }

            float processed = 0.0f;
            if (instrument == kPiano) {
                processed = processPiano(input, tone);
            } else if (instrument == kBass) {
                processed = processBass(input, tone);
            } else if (namEnabled_.load(std::memory_order_relaxed)) {
                // A NAM capture is already the amplifier. Feed it the gated DI
                // through the shared compressor/wah front end instead of
                // stacking the legacy built-in amp simulation ahead of it.
                processed = processMetalBoost(processGuitarFrontEnd(input));
            } else {
                processed = processGuitar(input, tone);
            }
            // The NAM boost already has its own bounded transfer function.
            // Limiting it again here flattened dense palm-muted chords before
            // the captured amp could react to their pick transients.
            float s = (instrument == kElectricGuitar
                    && namEnabled_.load(std::memory_order_relaxed))
                    ? processed : softKneeLimit(processed, 0.72f, 0.98f);
            output[frame * 2] = s;
            output[frame * 2 + 1] = s;
        }

        if (instrument == kElectricGuitar
                && namEnabled_.load(std::memory_order_relaxed)) {
            processNam(output, numFrames);
            for (int32_t frame = 0; frame < numFrames; ++frame) {
                float namSignal = (output[frame * 2] + output[frame * 2 + 1]) * 0.5f;
                // Match perceived loudness per rig after NAM + cabinet. The
                // tight 5153 capture has much lower RMS than the British model.
                int rigStyle = metalRigStyle_.load(std::memory_order_relaxed);
                namSignal *= rigStyle == 0 ? 1.70f : rigStyle == 1 ? 0.92f : 1.0f;
                float delayed = processMetalDelay(namSignal);
                delayed = processNamPostFx(delayed);
                // The normal amp path applies control 6 internally; NAM bypasses
                // that amp, so reconnect the visible rack Volume here.
                // This is the final app output, not another preamp drive.
                // Zero must mute; the upper half provides stage makeup.
                float namLevel = c6_ * 1.50f;
                delayed = softKneeLimit(delayed * namLevel, 0.78f, 1.04f);
                output[frame * 2] = delayed;
                output[frame * 2 + 1] = delayed;
            }
        }

        mixMetronome(output, numFrames);
        float outSq = finalizeOutput(output, numFrames);
        pushRecording(output, numFrames);
        updateMeter(sumSquares, numFrames);
        updateOutMeter(outSq, numFrames * 2);
        return oboe::DataCallbackResult::Continue;
    }

    void onErrorAfterClose(oboe::AudioStream *, oboe::Result error) override {
        std::ostringstream message;
        message << "Engine error: " << oboe::convertToText(error);
        setStatus(message.str());
        running_.store(false);
        inputStreamRaw_.store(nullptr);
        instStreamRaw_.store(nullptr);
        // Plugging/unplugging a headset or USB-C adapter disconnects the stream;
        // Android will NOT reroute it for us. Reopen on the new default devices
        // so the sound follows the route change (e.g. back to the speaker).
        if (error == oboe::Result::ErrorDisconnected) {
            EngineConfig cfg = lastConfig_;
            cfg.inputDeviceId = kNoDevice;
            cfg.outputDeviceId = kNoDevice;
            int gen = restartGen_.load();
            std::thread([this, cfg, gen]() {
                // An intentional stop() in the meantime cancels the restart.
                if (restartGen_.load() == gen) {
                    start(cfg);
                }
            }).detach();
        }
    }

private:
    oboe::Result openOutputStream(
            int deviceId,
            oboe::SharingMode sharingMode,
            std::shared_ptr<oboe::AudioStream> &stream
    ) {
        oboe::AudioStreamBuilder builder;
        // Usage::Game rides the fast mixer path on several OEM audio HALs
        // (the app already declares itself a game for Game Turbo).
        builder.setDirection(oboe::Direction::Output)
                ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
                ->setUsage(oboe::Usage::Game)
                ->setSharingMode(sharingMode)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(2)
                ->setDataCallback(this)
                ->setErrorCallback(this);
        if (deviceId != kNoDevice) {
            builder.setDeviceId(deviceId);
        }

        oboe::AudioStream *rawStream = nullptr;
        oboe::Result result = builder.openStream(&rawStream);
        if (result == oboe::Result::OK) {
            // ADPF performance hint: reports the callback's deadline to the
            // scheduler each cycle, so throttling-happy devices (HyperOS)
            // keep the audio thread fed instead of parking it — fewer
            // underrun "tak"s without paying for a bigger buffer.
            rawStream->setPerformanceHintEnabled(true);
            stream.reset(rawStream);
        }
        return result;
    }

    oboe::Result openInputStream(
            int deviceId,
            oboe::SharingMode sharingMode,
            int sampleRate,
            std::shared_ptr<oboe::AudioStream> &stream,
            bool lowLatency = true
    ) {
        oboe::AudioStreamBuilder builder;
        // Unprocessed input: the OS default (VoiceRecognition) runs AGC +
        // noise suppression, which pumps the level up on its own, chews up
        // sustained instrument tones, and adds latency. We want the raw mic.
        // lowLatency=false is the fallback for USB audio interfaces that reject
        // the fast capture path — they still open on the normal path.
        builder.setDirection(oboe::Direction::Input)
                ->setPerformanceMode(lowLatency ? oboe::PerformanceMode::LowLatency
                                                : oboe::PerformanceMode::None)
                ->setInputPreset(oboe::InputPreset::Unprocessed)
                ->setSharingMode(sharingMode)
                ->setFormat(oboe::AudioFormat::Float)
                ->setChannelCount(1)
                ->setSampleRate(sampleRate);
        if (deviceId != kNoDevice) {
            builder.setDeviceId(deviceId);
        }

        oboe::AudioStream *rawStream = nullptr;
        oboe::Result result = builder.openStream(&rawStream);
        if (result == oboe::Result::OK) {
            stream.reset(rawStream);
        }
        return result;
    }

    void stopLocked() {
        restartGen_.fetch_add(1);   // cancel any pending disconnect auto-restart
        running_.store(false);
        stopRecording();   // finalize the WAV if a capture is in progress
        seqPlaying_.store(false);   // don't auto-resume MIDI playback on next start
        stopPitchThread();

        std::shared_ptr<oboe::AudioStream> output = outputStream_;
        if (output != nullptr) {
            output->requestStop();
            output->close();
        }
        outputStream_.reset();

        inputStreamRaw_.store(nullptr);
        std::shared_ptr<oboe::AudioStream> input = inputStream_;
        if (input != nullptr) {
            input->requestStop();
            input->close();
        }
        inputStream_.reset();

        instStreamRaw_.store(nullptr);
        std::shared_ptr<oboe::AudioStream> inst = instStream_;
        if (inst != nullptr) {
            inst->requestStop();
            inst->close();
        }
        instStream_.reset();

        inputLevelDb_.store(-120.0f);
        outputLevelDb_.store(-120.0f);
        outMeterRms_ = 0.0f;
        pitchHz_.store(0.0f);
        setStatus("Engine: stopped");
    }

    void resetProcessor() {
        guitarHpX_ = 0.0f;
        guitarHpY_ = 0.0f;
        guitarToneState_ = 0.0f;
        gtEnv_ = 0.0f;
        gtLp1_ = 0.0f;
        gtLp2_ = 0.0f;
        gtDc_ = 0.0f;
        liveToneState_ = 0.0f;
        wahEnv_ = 0.0f;
        wahLow_ = 0.0f;
        wahBand_ = 0.0f;
        cab_.reset();
        for (int side = 0; side < 2; ++side) {
            for (int ch = 0; ch < 2; ++ch) {
                pianoGuitarDcX_[side][ch] = 0.0f;
                pianoGuitarDcY_[side][ch] = 0.0f;
                pianoGuitarTight_[side][ch] = 0.0f;
                pianoGuitarToneState_[side][ch] = 0.0f;
                pianoGuitarHarmDc_[side][ch] = 0.0f;
                pianoGuitarCabState_[side][ch].reset();
            }
        }
        resetVirtualGuitarPlayer();
        svfLow_ = 0.0f;
        svfBand_ = 0.0f;
        bassLpState_ = 0.0f;
        bassEnv_ = 0.0f;
        bassHpX_ = 0.0f;
        bassHpY_ = 0.0f;
        gateEnv_ = 0.0f;
        gateGain_ = 0.0f;
        gateState_ = false;
        bassPrev_ = 0.0f;
        bassSubPolarity_ = 1.0f;
        pianoEnv_ = 0.0f;
        midiEnv_ = 0.0f;
        decayEnv_ = 0.0f;
        brightEnv_ = 0.0f;
        lastNoteId_ = 0;
        noteOnId_.store(0);
        pianoPhase_ = 0.0;
        bassSynthPhase_ = 0.0;
        smoothedPitchHz_.store(0.0f);
        pitchWriteIndex_.store(0);
        pitchFilled_.store(0);
        pitchBuffer_.fill(0.0f);
        analysisBuffer_.fill(0.0f);

        audioActiveNote_ = -1;
        appliedProgram_ = -1;
        appliedDrumKit_ = -1;
        gkEnv_ = 0.0f;
        gkSlow_ = 0.0f;
        gkRef_ = 0.0f;
        gkHpX_ = 0.0f;
        gkHpY_ = 0.0f;
        gkFloor_ = 0.0f;
        gkGateOn_ = 0.012f;
        gkGateOff_ = 0.008f;
        gkQuietFrames_ = 0;
        gkNote_ = -1;
        gkCand_ = -1;
        gkOnsetHold_ = 0;
        gkLastSeq_ = -1;
        gkPolyMask_.store(0);
        gkSounding_ = 0;
        gkSoundTrans_ = 0;
        gkBendCur_ = 0.0f;
        gkBendSnd_ = nullptr;
        glideStackN_ = 0;
        glideAnchor_ = -1;
        glideOffCur_ = 0.0f;
        glideOffTarget_ = 0.0f;
        glide2StackN_ = 0;
        glide2Anchor_ = -1;
        glide2OffCur_ = 0.0f;
        glide2OffTarget_ = 0.0f;
        lkStackN_ = 0;
        lkAnchor_ = -1;
        lkOffCur_ = 0.0f;
        lkOffTarget_ = 0.0f;
        lk2StackN_ = 0;
        lk2Anchor_ = -1;
        lk2OffCur_ = 0.0f;
        lk2OffTarget_ = 0.0f;
        for (int i = 0; i < 64; ++i) {
            gkSeen_[i] = 0;
            gkMiss_[i] = 0;
            gkActive_[i] = false;
            gkPolyVel_[i] = 0.0f;
        }
        appliedLoopKeysProg_ = -1;
        appliedLayerProgram_ = -1;
        appliedLayer3_ = -1;
        appliedLayer4_ = -1;
        appliedLoopKeysLayer_ = -1;
        appliedLoopKeysBend_ = -1;
        appliedBendRange_ = -1;
        appliedLoopBendRange_ = -1;
        activeKeysFont_ = nullptr;
        appliedAltFont_ = nullptr;
        {
            std::lock_guard<std::mutex> lock(producerMutex_);
            eventHead_.store(0);
            eventTail_.store(0);
        }
        tsf *snd = sound_.load();
        if (snd != nullptr) {
            tsf_set_output(snd, TSF_STEREO_INTERLEAVED, sampleRate_, -8.8f);
            tsf_note_off_all(snd);
        }

        fxLp_[0] = fxLp_[1] = 0.0f;
        fxTremPhase_ = 0.0;
        fxChorusPhase_ = 0.0;
        fxDelay_[0].fill(0.0f);
        fxDelay_[1].fill(0.0f);
        fxDelayWrite_[0] = fxDelayWrite_[1] = 0;
        float fxRate = static_cast<float>(std::max(sampleRate_, 1));
        fxLpCoeff_ = 1.0f - std::exp(-2.0f * static_cast<float>(kPi) * 1800.0f / fxRate);
        fxTremInc_ = kTwoPi * 5.5 / static_cast<double>(fxRate);
        fxChorusInc_ = kTwoPi * 0.8 / static_cast<double>(fxRate);
        fxChorusBase_ = 0.012f * fxRate;
        fxChorusDepth_ = 0.004f * fxRate;

        sampleClock_ = 0;
        for (int i = 0; i < 128; ++i) {
            pendingRelease_[i] = -1;
            drumPieceSlot_[i].store(-1);
            drumPieceSrcNote_[i].store(-1);
            drumPieceGain_[i].store(1.0f);
            drumPiecePan_[i].store(0.5f);
        }
        int combTune[4] = {1116, 1188, 1277, 1356};
        int apTune[2] = {556, 441};
        int stereoSpread = 23;   // classic Freeverb L/R offset for width
        for (int ch = 0; ch < 2; ++ch) {
            int spread = (ch == 1) ? stereoSpread : 0;
            for (int k = 0; k < 4; ++k) {
                int len = static_cast<int>((combTune[k] + spread) * fxRate / 44100.0f);
                combLen_[ch][k] = std::max(1, std::min(len, 1800));
                combIdx_[ch][k] = 0;
                combStore_[ch][k] = 0.0f;
                for (int i = 0; i < 1800; ++i) {
                    combBuf_[ch][k][i] = 0.0f;
                }
            }
            for (int k = 0; k < 2; ++k) {
                int len = static_cast<int>((apTune[k] + spread) * fxRate / 44100.0f);
                apLen_[ch][k] = std::max(1, std::min(len, 700));
                apIdx_[ch][k] = 0;
                for (int i = 0; i < 700; ++i) {
                    apBuf_[ch][k][i] = 0.0f;
                }
            }
        }

        float sampleRate = static_cast<float>(std::max(sampleRate_, 1));
        float hpCutoff = 90.0f;
        float rc = 1.0f / (2.0f * static_cast<float>(kPi) * hpCutoff);
        float dt = 1.0f / sampleRate;
        guitarHpAlpha_ = rc / (rc + dt);

        float bassCutoff = 3200.0f;
        bassLpCoeff_ = 1.0f - std::exp(-2.0f * static_cast<float>(kPi) * bassCutoff / sampleRate);
        // Subsonic high-pass (~32 Hz) for the bass input: strips DC and the
        // sub-bass rumble that builds into speaker feedback, while leaving the
        // low-E fundamental (~41 Hz) intact.
        bassHpAlpha_ = 1.0f / (1.0f + 2.0f * static_cast<float>(kPi) * 32.0f / sampleRate);
        bassHpX_ = 0.0f;
        bassHpY_ = 0.0f;
        // Gate: 8 ms envelope release, ~1 ms open, ~35 ms close.
        gateEnvRel_ = std::exp(-1.0f / (0.008f * sampleRate));
        gateAtt_ = 1.0f - std::exp(-1.0f / (0.001f * sampleRate));
        gateRel_ = 1.0f - std::exp(-1.0f / (0.035f * sampleRate));

        float toneCutoff = 7200.0f;
        guitarToneCoeff_ = 1.0f - std::exp(-2.0f * static_cast<float>(kPi) * toneCutoff / sampleRate);

        // Metal-drum post-saturation low-pass (~9.5 kHz): keeps the attack/crack
        // but removes the aliased fizz that reads as a "tak" on hard-driven hits.
        drumMetalCoeff_ = 1.0f - std::exp(-2.0f * static_cast<float>(kPi) * 9500.0f / sampleRate);
        drumMetalLp_[0] = drumMetalLp_[1] = 0.0f;
        drumDcX_[0] = drumDcX_[1] = 0.0f;
        drumDcY_[0] = drumDcY_[1] = 0.0f;
    }

    float sanitize(float value) {
        if (!std::isfinite(value)) {
            return 0.0f;
        }
        return clampFloat(value, -1.0f, 1.0f);
    }

    float highPassGuitar(float input) {
        float output = guitarHpAlpha_ * (guitarHpY_ + input - guitarHpX_);
        guitarHpX_ = input;
        guitarHpY_ = output;
        return output;
    }

    void snapshotControls() {
        c1_ = control1_.load();
        c2_ = control2_.load();
        c3_ = control3_.load();
        c4_ = control4_.load();
        c5_ = control5_.load();
        c6_ = control6_.load();
        ft_ = fxTone_.load();
        fd_ = fxDrive_.load();
        fc_ = fxChorus_.load();
        ftr_ = fxTrem_.load();
        fs_ = fxSoft_.load();
        ftB_ = fxToneB_.load();
        fdB_ = fxDriveB_.load();
        fcB_ = fxChorusB_.load();
        ftrB_ = fxTremB_.load();
        fsB_ = fxSoftB_.load();
    }

    // Per-channel so L/R keep independent filter/chorus state for a true stereo
    // image. Parametrized over one FX param set + one state set, so Keyboard A
    // (Sound 1) and Keyboard B (Sound 2) can run fully independent tone chains.
    float processFxCore(float x, int ch, float ft, float fs, float fd, float fc, float ftr,
                        float *lpArr, std::array<float, 2048> *delayArr, int *writeArr,
                        double &chorusPhase, double &tremPhase) {
        float &lp = lpArr[ch];
        std::array<float, 2048> &delay = delayArr[ch];
        int &write = writeArr[ch];
        lp += (x - lp) * fxLpCoeff_;
        float high = x - lp;
        float toned = ft >= 0.0f ? x + ft * high * 2.0f : lp + (1.0f + ft) * high;
        if (fs > 0.0f) toned = lp + (toned - lp) * (1.0f - fs);

        float driven = softClip(toned * (1.0f + fd * 2.2f)) / (1.0f + fd * 1.1f);

        int size = static_cast<int>(delay.size());
        delay[write] = driven;
        float out = driven;
        if (fc > 0.001f) {
            if (ch == 0) {
                chorusPhase += fxChorusInc_;
                if (chorusPhase > kTwoPi) chorusPhase -= kTwoPi;
            }
            double phase = chorusPhase + (ch == 1 ? kPi * 0.5 : 0.0);
            float mod = 0.5f * (1.0f + static_cast<float>(std::sin(phase)));
            float delaySamp = fxChorusBase_ + mod * fxChorusDepth_;
            float readPos = static_cast<float>(write) - delaySamp;
            while (readPos < 0.0f) readPos += size;
            int i0 = static_cast<int>(readPos);
            float frac = readPos - i0;
            int i1 = (i0 + 1) % size;
            float delayed = delay[i0] * (1.0f - frac) + delay[i1] * frac;
            out = driven * (1.0f - fc * 0.5f) + delayed * (fc * 0.8f);
        }
        write = (write + 1) % size;

        if (ftr > 0.001f) {
            if (ch == 0) {
                tremPhase += fxTremInc_;
                if (tremPhase > kTwoPi) tremPhase -= kTwoPi;
            }
            float lfo = 0.5f * (1.0f + static_cast<float>(std::sin(tremPhase)));
            out *= 1.0f - ftr * 0.6f * lfo;
        }
        return out;
    }
    // Side A (Sound 1 + shared layers).
    float processPianoFx(float x, int ch) {
        return processFxCore(x, ch, ft_, fs_, fd_, fc_, ftr_,
                fxLp_, fxDelay_, fxDelayWrite_, fxChorusPhase_, fxTremPhase_);
    }
    // Side B (Sound 2), its own params + filter/chorus state.
    float processPianoFxB(float x, int ch) {
        return processFxCore(x, ch, ftB_, fsB_, fdB_, fcB_, ftrB_,
                fxLpB_, fxDelayB_, fxDelayWriteB_, fxChorusPhaseB_, fxTremPhaseB_);
    }

    float processPianoGuitarRig(float input, int side, int ch) {
        float hp = input - pianoGuitarDcX_[side][ch]
                + 0.995f * pianoGuitarDcY_[side][ch];
        pianoGuitarDcX_[side][ch] = input;
        pianoGuitarDcY_[side][ch] = hp;

        float drive = pianoGuitarDrive_.load(std::memory_order_relaxed);
        int amp = pianoGuitarAmp_.load(std::memory_order_relaxed);
        float wet;
        if (amp == 0) {
            wet = softClip(hp * (1.15f + drive * 1.6f)) * 0.82f;
        } else if (amp == 1) {
            wet = softClip(hp * (2.3f + drive * 4.8f)) * 0.72f;
        } else if (amp == 2) {
            float first = softClip(hp * (3.5f + drive * 7.5f));
            wet = softClip(first * 1.45f) * 0.66f;
        } else {
            pianoGuitarTight_[side][ch] += 0.035f
                    * (hp - pianoGuitarTight_[side][ch]);
            float tight = hp - pianoGuitarTight_[side][ch] * 0.82f;
            wet = softClip(tight * (6.0f + drive * 11.0f));
            wet = softClip(wet * 1.25f) * 0.61f;
        }

        // Prototype player layer: alternate picks emphasize slightly different
        // parts of the attack. This does not pretend that one SF2 sample is two
        // recordings, but makes the behavior testable before real up/downstroke
        // sample pairs are available.
        if (virtualGuitarPlayer_.load(std::memory_order_relaxed)) {
            int seq = virtualGuitarPickSeq_;
            if (virtualGuitarSeenPick_[side][ch] != seq) {
                virtualGuitarSeenPick_[side][ch] = seq;
                virtualGuitarPickEnv_[side][ch] =
                        0.20f + virtualGuitarStrike_ * 0.80f;
            }
            float transient = hp - pianoGuitarTight_[side][ch];
            float direction = virtualGuitarPickDown_ ? 1.0f : 0.72f;
            wet = softClip(wet + transient
                    * virtualGuitarPickEnv_[side][ch] * direction * 0.22f);
            virtualGuitarPickEnv_[side][ch] *= 0.9965f;
        }

        // A hard key strike adds pick bite and a restrained full-wave octave
        // overtone. SF2 velocity layers still trigger normally before this stage.
        float velocity = midiVelocity_.load(std::memory_order_relaxed);
        float hardStrike = clampFloat((velocity - 0.58f) / 0.42f, 0.0f, 1.0f);
        float amount = pianoGuitarHarmonics_.load(std::memory_order_relaxed) * hardStrike;
        if (amount > 0.001f) {
            float rectified = std::fabs(wet);
            pianoGuitarHarmDc_[side][ch] += 0.004f
                    * (rectified - pianoGuitarHarmDc_[side][ch]);
            float octave = rectified - pianoGuitarHarmDc_[side][ch];
            wet = softClip(wet + octave * amount * 1.15f);
        }

        float tone = pianoGuitarTone_.load(std::memory_order_relaxed);
        float coeff = 0.08f + tone * 0.62f;
        pianoGuitarToneState_[side][ch] += coeff
                * (wet - pianoGuitarToneState_[side][ch]);
        float shaped = pianoGuitarToneState_[side][ch]
                + (wet - pianoGuitarToneState_[side][ch]) * (0.35f + tone * 0.65f);

        int cabType = pianoGuitarCab_.load(std::memory_order_relaxed);
        GuitarCab &cab = pianoGuitarCabState_[side][ch];
        if (cabType != cab.builtType || static_cast<float>(sampleRate_) != cab.builtSr) {
            cab.configure(cabType, static_cast<float>(sampleRate_));
        }
        return softClip(cab.process(shaped) * 0.72f);
    }

    bool processNam(float *stereo, int32_t numFrames) {
        if (!namEnabled_.load(std::memory_order_relaxed)) return false;
        namReaders_.fetch_add(1, std::memory_order_acquire);
        nam::DSP *model = namModel_.load(std::memory_order_acquire);
        if (model == nullptr) {
            namReaders_.fetch_sub(1, std::memory_order_release);
            return false;
        }

        const float mix = namMix_.load(std::memory_order_relaxed);
        float inputGain = 1.0f;
        if (!virtualGuitarMode_.load(std::memory_order_relaxed)) {
            // PEDAL Gain is independent from the rack Input stage: it controls
            // how hard the DI hits the NAM capture.
            inputGain = 0.05f
                    + control1_.load(std::memory_order_relaxed) * 1.95f;
        }
        const float outputGain = namOutputGain_.load(std::memory_order_relaxed);
        int32_t offset = 0;
        while (offset < numFrames) {
            int32_t count = std::min<int32_t>(kNamBlockFrames, numFrames - offset);
            for (int32_t i = 0; i < count; ++i) {
                int32_t frame = offset + i;
                float polyGain = 1.0f;
                if (virtualGuitarMode_.load(std::memory_order_relaxed)) {
                    int notes = std::max(1, chanActive_[0]);
                    float target = 1.0f / std::sqrt(static_cast<float>(notes));
                    // Smooth note-count changes so releasing one note cannot
                    // click or abruptly jump the NAM input level.
                    virtualGuitarPolyGain_ += 0.012f
                            * (target - virtualGuitarPolyGain_);
                    polyGain = virtualGuitarPolyGain_;
                }
                namInput_[i] = (stereo[frame * 2] + stereo[frame * 2 + 1])
                        * 0.5f * inputGain * polyGain;
            }
            NAM_SAMPLE *inputs[] = {namInput_.data()};
            NAM_SAMPLE *outputs[] = {namOutput_.data()};
            model->process(inputs, outputs, count);
            for (int32_t i = 0; i < count; ++i) {
                int32_t frame = offset + i;
                float wet = namOutput_[i] * outputGain;
                if (!std::isfinite(wet)) wet = 0.0f;
                // NAM captures may legitimately overshoot 0 dBFS internally.
                // Preserve that transient through the cabinet instead of
                // hard-clamping it before convolution.
                wet = clampFloat(wet, -4.0f, 4.0f);
                if (namIrEnabled_.load(std::memory_order_relaxed)) {
                    wet = processNamIr(wet);
                }
                float dryL = stereo[frame * 2];
                float dryR = stereo[frame * 2 + 1];
                // Preserve cabinet and NAM level changes here. The chain has a
                // single final safety limiter after all post effects.
                stereo[frame * 2] = clampFloat(
                        dryL + (wet - dryL) * mix, -4.0f, 4.0f);
                stereo[frame * 2 + 1] = clampFloat(
                        dryR + (wet - dryR) * mix, -4.0f, 4.0f);
            }
            offset += count;
        }
        namReaders_.fetch_sub(1, std::memory_order_release);
        return true;
    }

    float processNamIr(float input) {
        int active = namIrActive_.load(std::memory_order_acquire);
        int length = namIrLength_[active].load(std::memory_order_acquire);
        if (length <= 0) return input;
        if (namIrReset_.exchange(false, std::memory_order_acq_rel)) {
            namIrHistory_.fill(0.0f);
            namIrWrite_ = 0;
        }
        namIrHistory_[namIrWrite_] = input;
        float output = 0.0f;
        int read = namIrWrite_;
        const auto &ir = namIr_[active];
        for (int tap = 0; tap < length; ++tap) {
            output += ir[tap] * namIrHistory_[read];
            if (--read < 0) read = kNamIrTaps - 1;
        }
        if (++namIrWrite_ >= kNamIrTaps) namIrWrite_ = 0;
        return output * namIrLevel_.load(std::memory_order_relaxed);
    }

    float processReverb(int ch, float in) {
        float wet = 0.0f;
        for (int k = 0; k < 4; ++k) {
            float y = combBuf_[ch][k][combIdx_[ch][k]];
            combStore_[ch][k] = y * (1.0f - reverbDamp_) + combStore_[ch][k] * reverbDamp_;
            combBuf_[ch][k][combIdx_[ch][k]] = in + combStore_[ch][k] * reverbFeedback_;
            combIdx_[ch][k] = (combIdx_[ch][k] + 1) % combLen_[ch][k];
            wet += y;
        }
        wet *= 0.25f;
        for (int k = 0; k < 2; ++k) {
            float bufout = apBuf_[ch][k][apIdx_[ch][k]];
            float output = -wet + bufout;
            apBuf_[ch][k][apIdx_[ch][k]] = wet + bufout * 0.5f;
            apIdx_[ch][k] = (apIdx_[ch][k] + 1) % apLen_[ch][k];
            wet = output;
        }
        return wet;
    }

    // Metal drum drive. The source is DC-blocked first so the heavy saturation
    // can't amplify any sample offset into an end-of-hit "tak", then a low-pass
    // tames the broadband aliasing that hard-clipping a hot transient produces
    // (the cause of the click on the louder driven kits).
    float drumMetal(int ch, float x) {
        float hp = x - drumDcX_[ch] + 0.9975f * drumDcY_[ch];
        drumDcX_[ch] = x;
        drumDcY_[ch] = hp;
        float sat = softClip(softClip(hp * 2.6f) * 1.35f);
        drumMetalLp_[ch] += drumMetalCoeff_ * (sat - drumMetalLp_[ch]);
        // Trim metal output ~20% — the saturated kits ran noticeably hotter than
        // the clean kits.
        return drumMetalLp_[ch] * 0.70f;
    }

    float processGuitarFrontEnd(float input) {
        if (guitarCompOn_.load(std::memory_order_relaxed)) {
            float amount = guitarCompAmount_.load(std::memory_order_relaxed);
            float level = std::fabs(input);
            guitarCompEnv_ += (level - guitarCompEnv_)
                    * (level > guitarCompEnv_ ? 0.08f : 0.0008f);
            float threshold = 0.22f - amount * 0.15f;
            float gain = guitarCompEnv_ > threshold
                    ? threshold / std::max(guitarCompEnv_, 0.0001f) : 1.0f;
            float makeup = 1.0f + amount * 1.15f;
            input = softClip(input * (1.0f - amount + amount * gain) * makeup);
        }
        // Manual wah-wah pedal, ahead of the drive like a real pedalboard.
        // wahPos_ sweeps the resonant bandpass 280 Hz (heel) → ~2.2 kHz (toe).
        if (wahOn_.load(std::memory_order_relaxed)) {
            float pos = wahPos_.load(std::memory_order_relaxed);
            float fc = 280.0f * std::pow(8.0f, pos);
            float f = 2.0f * std::sin(static_cast<float>(kPi) * fc / static_cast<float>(sampleRate_));
            if (f > 1.5f) {
                f = 1.5f;
            }
            // Resonant bandpass, ahead of the drive. Low damping (0.16 ≈ Q6)
            // gives a sharp, vocal peak that stays obvious even through heavy
            // distortion; states are clamped so the resonance can't run away.
            wahLow_ += f * wahBand_;
            float hp = input - wahLow_ - 0.16f * wahBand_;
            wahBand_ += f * hp;
            wahBand_ = clampFloat(wahBand_, -4.0f, 4.0f);
            wahLow_ = clampFloat(wahLow_, -4.0f, 4.0f);
            input = softClip(wahBand_ * 2.6f + input * 0.10f);
        }
        return input;
    }

    float processGuitar(float input, int tone) {
        input = processGuitarFrontEnd(input);
        float drive = c1_;
        float bass = c2_;
        float mid = c3_;
        float treble = c4_;
        float presence = c5_;
        float level = c6_;

        float x = highPassGuitar(input) * (0.70f + drive * 2.20f);
        float absX = std::fabs(x);

        if ((tone == kGuitarMetal || tone == kGuitarHardMetal
                || tone == kGuitarGtBrown || tone == kGuitarGtLead) && absX < 0.012f) {
            x *= 0.15f;   // high-gain rigs get the hard noise gate
        } else if (absX < 0.003f) {
            x *= 0.35f;
        }

        float driven = x;
        float wet = 0.0f;
        switch (tone) {
            case kGuitarClean:
                wet = hardClip(driven * 1.4f, 0.95f) * 0.85f;
                break;
            case kGuitarDistortion:
                wet = softClip(driven * 5.8f) * 0.78f;
                break;
            case kGuitarMetal:
                wet = softClip(driven * 9.5f);
                wet = onePoleLowPass(wet, guitarToneState_, guitarToneCoeff_) * 0.86f;
                break;
            case kGuitarHardMetal:
                wet = softClip(driven * 16.0f);
                wet = onePoleLowPass(wet, guitarToneState_, guitarToneCoeff_);
                wet = softClip(wet * 1.4f) * 0.80f;
                break;
            case kGuitarFuzz:
                wet = hardClip(driven * 10.0f, 0.65f);
                wet = softClip(wet * 2.2f) * 0.82f;
                break;
            case kGuitarWah: {
                float rect = std::fabs(driven);
                wahEnv_ += (rect - wahEnv_) * (rect > wahEnv_ ? 0.05f : 0.0025f);
                float fc = 350.0f + clampFloat(wahEnv_ * 7.0f, 0.0f, 1.0f) * 1900.0f;
                float f = 2.0f * std::sin(static_cast<float>(kPi) * fc / static_cast<float>(sampleRate_));
                if (f > 1.5f) {
                    f = 1.5f;
                }
                svfLow_ += f * svfBand_;
                float hp = driven - svfLow_ - 0.18f * svfBand_;
                svfBand_ += f * hp;
                wet = softClip(svfBand_ * 2.6f) * 0.85f;
                break;
            }
            case kGuitarChorus: {
                // Mono guitar path reuses channel 0 of the shared chorus delay line.
                std::array<float, 2048> &delay = fxDelay_[0];
                int &write = fxDelayWrite_[0];
                float c = softClip(driven * 1.8f) * 0.80f;
                int size = static_cast<int>(delay.size());
                delay[write] = c;
                fxChorusPhase_ += fxChorusInc_;
                if (fxChorusPhase_ > kTwoPi) {
                    fxChorusPhase_ -= kTwoPi;
                }
                float mod = 0.5f * (1.0f + static_cast<float>(std::sin(fxChorusPhase_)));
                float delaySamp = fxChorusBase_ + mod * fxChorusDepth_;
                float readPos = static_cast<float>(write) - delaySamp;
                while (readPos < 0.0f) {
                    readPos += size;
                }
                int i0 = static_cast<int>(readPos);
                float frac = readPos - i0;
                float delayed = delay[i0] * (1.0f - frac) + delay[(i0 + 1) % size] * frac;
                write = (write + 1) % size;
                wet = c * 0.7f + delayed * 0.7f;
                break;
            }
            case kGuitarGtClean: {
                // GT-1000 "Natural Clean": studio compressor into a sparkle
                // tilt — loud and glassy without ever breaking up.
                gtEnv_ = std::max(absX, gtEnv_ * 0.9995f);
                float comp = driven * (gtEnv_ > 0.15f ? 0.15f / gtEnv_ : 1.0f);
                float c = hardClip(comp * 1.9f, 0.95f);
                float lp = onePoleLowPass(c, gtLp1_, 0.25f);
                wet = (lp + (c - lp) * 1.35f) * 0.85f;
                break;
            }
            case kGuitarGtCrunch: {
                // GT-1000 "X-Crunch": tightened low end into an asymmetric
                // two-stage drive — cleans up from the guitar's volume knob.
                float tight = driven - onePoleLowPass(driven, gtLp1_, 0.035f);
                float st1 = softClip(tight * 4.2f + 0.10f);
                wet = softClip(st1 * 1.5f);
                wet -= onePoleLowPass(wet, gtDc_, 0.002f);   // strip the bias DC
                wet *= 0.80f;
                break;
            }
            case kGuitarGtBrown: {
                // GT-1000 "Brown" stack: pushed mids, thick sustain, smooth top.
                float mids = onePoleLowPass(driven, gtLp1_, 0.30f);
                wet = softClip((driven * 0.4f + mids * 1.3f) * 7.5f);
                wet = onePoleLowPass(wet, guitarToneState_, guitarToneCoeff_);
                wet = softClip(wet * 1.25f) * 0.82f;
                break;
            }
            case kGuitarGtLead: {
                // GT-1000 "X-Lead": compressed singing sustain, silky top end.
                gtEnv_ = std::max(absX, gtEnv_ * 0.9993f);
                float sustain = driven
                        * (gtEnv_ > 0.08f ? 0.08f / gtEnv_ * 0.6f + 0.4f : 1.0f);
                wet = softClip(sustain * 11.0f);
                wet = onePoleLowPass(wet, guitarToneState_, guitarToneCoeff_);
                wet = onePoleLowPass(wet, gtLp2_, 0.55f) * 0.86f;
                break;
            }
            case kGuitarOverdrive:
            default:
                wet = softClip(driven * 3.2f) * 0.78f;
                break;
        }

        float toneCoeff = 0.08f + treble * 0.62f;
        float rounded = onePoleLowPass(wet, liveToneState_, toneCoeff);
        float bite = wet - rounded;
        float shaped = rounded * (0.80f + bass * 0.40f)
                + bite * (0.55f + treble * 1.10f)
                + wet * (mid - 0.5f) * 0.22f;
        shaped = softClip(shaped * (0.90f + presence * 0.35f));
        // Speaker cabinet / IR stage — last in the chain, after the power-amp
        // clip, exactly like a real rig (the speaker filters, it doesn't clip).
        // This rolls off the fizzy top a raw DI has and gives it miked body.
        if (cabOn_.load(std::memory_order_relaxed)) {
            int ct = cabType_.load(std::memory_order_relaxed);
            if (ct != cab_.builtType
                    || static_cast<float>(sampleRate_) != cab_.builtSr) {
                cab_.configure(ct, static_cast<float>(sampleRate_));
            }
            float cabbed = cab_.process(shaped);
            float mix = cabMix_.load(std::memory_order_relaxed);
            shaped = shaped * (1.0f - mix) + cabbed * mix;
        }
        if (guitarModOn_.load(std::memory_order_relaxed)) {
            int size = static_cast<int>(guitarModDelay_.size());
            guitarModDelay_[guitarModWrite_] = shaped;
            float rate = 0.15f + guitarModRate_.load(std::memory_order_relaxed) * 4.85f;
            guitarModPhase_ += kTwoPi * rate / static_cast<float>(sampleRate_);
            if (guitarModPhase_ >= kTwoPi) guitarModPhase_ -= kTwoPi;
            float depth = guitarModDepth_.load(std::memory_order_relaxed);
            float delaySamples = (0.004f + depth * 0.010f
                    * (0.5f + 0.5f * std::sin(guitarModPhase_))) * sampleRate_;
            float read = guitarModWrite_ - delaySamples;
            while (read < 0.0f) read += size;
            int i0 = static_cast<int>(read);
            int i1 = (i0 + 1) % size;
            float frac = read - i0;
            float chorus = guitarModDelay_[i0] * (1.0f - frac)
                    + guitarModDelay_[i1] * frac;
            guitarModWrite_ = (guitarModWrite_ + 1) % size;
            shaped = shaped * (1.0f - depth * 0.28f) + chorus * depth * 0.48f;
        }
        if (guitarDelayOn_.load(std::memory_order_relaxed)) {
            int size = static_cast<int>(guitarDelay_.size());
            int delaySamples = static_cast<int>((0.06f
                    + guitarDelayTime_.load(std::memory_order_relaxed) * 0.84f) * sampleRate_);
            delaySamples = std::max(1, std::min(size - 1, delaySamples));
            int read = guitarDelayWrite_ - delaySamples;
            if (read < 0) read += size;
            float echo = guitarDelay_[read];
            float feedback = guitarDelayFeedback_.load(std::memory_order_relaxed);
            guitarDelay_[guitarDelayWrite_] = softClip(shaped + echo * feedback);
            guitarDelayWrite_ = (guitarDelayWrite_ + 1) % size;
            float mix = guitarDelayMix_.load(std::memory_order_relaxed);
            shaped = shaped * (1.0f - mix * 0.35f) + echo * mix;
        }
        if (guitarRoomOn_.load(std::memory_order_relaxed)) {
            shaped += processReverb(0, shaped)
                    * guitarRoomMix_.load(std::memory_order_relaxed);
            shaped = softClip(shaped);
        }
        // Max level = 0.55 peak: even a maxed-out knob stays clear of clipping
        // (and the meter's red zone) — matches the piano/drum balance targets.
        return shaped * (level * 0.64f);
    }

    float processMetalBoost(float input) {
        if (metalRigStyle_.load(std::memory_order_relaxed) < 0) return input;
        if (metalRigStyle_.load(std::memory_order_relaxed) == 1) {
            // Tight high-gain overdrive, not fuzz: trim flubby lows, add
            // controlled mid-focused drive, and preserve the pick transient.
            metalBoostLow_ += 0.035f * (input - metalBoostLow_);
            float tight = input - metalBoostLow_ * 0.70f;
            float drive = metalBoostDrive_.load(std::memory_order_relaxed);
            float driven = softClip(tight * (1.35f + drive * 4.8f));
            float tone = metalBoostTone_.load(std::memory_order_relaxed);
            metalBoostToneState_ += (0.12f + tone * 0.55f)
                    * (driven - metalBoostToneState_);
            float colored = metalBoostToneState_
                    + (driven - metalBoostToneState_) * (0.30f + tone * 0.65f);
            float level = metalBoostLevel_.load(std::memory_order_relaxed);
            return softKneeLimit(colored * (0.72f + level * 1.40f), 0.82f, 1.08f);
        }
        // Tube Screamer-style metal boost: trim lows before the neural amp,
        // use restrained clipping, then restore level to hit the amp harder.
        metalBoostLow_ += 0.025f * (input - metalBoostLow_);
        float tight = input - metalBoostLow_ * 0.68f;
        float drive = metalBoostDrive_.load(std::memory_order_relaxed);
        float clipped = softClip(tight * (1.15f + drive * 3.6f));
        float tone = metalBoostTone_.load(std::memory_order_relaxed);
        metalBoostToneState_ += (0.10f + tone * 0.58f)
                * (clipped - metalBoostToneState_);
        float bright = metalBoostToneState_
                + (clipped - metalBoostToneState_) * (0.25f + tone * 0.75f);
        float level = metalBoostLevel_.load(std::memory_order_relaxed);
        return softClip(bright * (0.72f + level * 1.65f));
    }

    float processMetalDelay(float input) {
        if (metalRigStyle_.load(std::memory_order_relaxed) < 0) return input;
        if (metalRigStyle_.load(std::memory_order_relaxed) == 1) return input;
        int size = static_cast<int>(metalDelay_.size());
        int delaySamples = static_cast<int>((0.10f
                + metalDelayTime_.load(std::memory_order_relaxed) * 0.62f)
                * sampleRate_);
        delaySamples = std::max(1, std::min(size - 1, delaySamples));
        int read = metalDelayWrite_ - delaySamples;
        if (read < 0) read += size;
        float echo = metalDelay_[read];
        float feedback = metalDelayFeedback_.load(std::memory_order_relaxed);
        metalDelay_[metalDelayWrite_] = softClip(input + echo * feedback);
        metalDelayWrite_ = (metalDelayWrite_ + 1) % size;
        float mix = metalDelayMix_.load(std::memory_order_relaxed);
        // Keep the direct chug at unity; delay is an added post-cab send.
        return softKneeLimit(input + echo * mix, 0.84f, 1.10f);
    }

    float processNamPostFx(float input) {
        // Lightweight post-NAM tone stack for the visible Guitar pedal knobs.
        // Center is neutral; each band spans approximately +/-9 dB.
        namToneLow_ += 0.025f * (input - namToneLow_);
        namToneMid_ += 0.12f * (input - namToneMid_);
        float high = input - namToneMid_;
        float midBand = namToneMid_ - namToneLow_;
        float bassGain = std::pow(10.0f, (c2_ - 0.5f) * 18.0f / 20.0f);
        float midGain = std::pow(10.0f, (c3_ - 0.5f) * 18.0f / 20.0f);
        float trebleGain = std::pow(10.0f, (c4_ - 0.5f) * 18.0f / 20.0f);
        float presenceGain = 0.65f + c5_ * 0.70f;
        float shaped = namToneLow_ * bassGain
                + midBand * midGain
                + high * trebleGain * presenceGain;
        if (guitarModOn_.load(std::memory_order_relaxed)) {
            int size = static_cast<int>(guitarModDelay_.size());
            guitarModDelay_[guitarModWrite_] = shaped;
            float rate = 0.15f + guitarModRate_.load(std::memory_order_relaxed) * 4.85f;
            guitarModPhase_ += kTwoPi * rate / static_cast<float>(sampleRate_);
            if (guitarModPhase_ >= kTwoPi) guitarModPhase_ -= kTwoPi;
            float depth = guitarModDepth_.load(std::memory_order_relaxed);
            float delaySamples = (0.004f + depth * 0.010f
                    * (0.5f + 0.5f * std::sin(guitarModPhase_))) * sampleRate_;
            float read = guitarModWrite_ - delaySamples;
            while (read < 0.0f) read += size;
            int i0 = static_cast<int>(read);
            int i1 = (i0 + 1) % size;
            float frac = read - i0;
            float chorus = guitarModDelay_[i0] * (1.0f - frac)
                    + guitarModDelay_[i1] * frac;
            guitarModWrite_ = (guitarModWrite_ + 1) % size;
            shaped = shaped * (1.0f - depth * 0.28f) + chorus * depth * 0.48f;
        }
        if (guitarDelayOn_.load(std::memory_order_relaxed)) {
            int size = static_cast<int>(guitarDelay_.size());
            int delaySamples = static_cast<int>((0.06f
                    + guitarDelayTime_.load(std::memory_order_relaxed) * 0.84f)
                    * sampleRate_);
            delaySamples = std::max(1, std::min(size - 1, delaySamples));
            int read = guitarDelayWrite_ - delaySamples;
            if (read < 0) read += size;
            float echo = guitarDelay_[read];
            float feedback = guitarDelayFeedback_.load(std::memory_order_relaxed);
            guitarDelay_[guitarDelayWrite_] = softClip(shaped + echo * feedback);
            guitarDelayWrite_ = (guitarDelayWrite_ + 1) % size;
            float mix = guitarDelayMix_.load(std::memory_order_relaxed);
            shaped = shaped * (1.0f - mix * 0.35f) + echo * mix;
        }
        if (guitarRoomOn_.load(std::memory_order_relaxed)) {
            shaped += processReverb(0, shaped)
                    * guitarRoomMix_.load(std::memory_order_relaxed);
        }
        return clampFloat(shaped, -4.0f, 4.0f);
    }

    float processBass(float input, int tone) {
        float gain = c1_;
        float lowControl = c2_;
        float lowMid = c3_;
        float hiMid = c4_;
        float blend = c5_;
        float level = c6_;

        // Kill DC / subsonic rumble before anything amplifies it (feedback).
        float hp = bassHpAlpha_ * (bassHpY_ + input - bassHpX_);
        bassHpX_ = input;
        bassHpY_ = hp;
        input = hp;

        float low = onePoleLowPass(input, bassLpState_, bassLpCoeff_);
        bassEnv_ = std::max(std::fabs(low), bassEnv_ * 0.997f);

        float compressed = low * (0.70f + gain * 2.10f);
        if (bassEnv_ > 0.20f) {
            compressed *= 0.20f / bassEnv_;
        }

        if (bassPrev_ <= 0.0f && low > 0.0f) {
            bassSubPolarity_ = -bassSubPolarity_;
        }
        bassPrev_ = low;
        float sub = bassSubPolarity_ * std::min(bassEnv_ * (1.1f + lowControl * 1.6f), 0.55f);

        float output = compressed;
        switch (tone) {
            case kBassClean:
                output = compressed * 1.15f;
                break;
            case kBassSynth:
                output = bassSynth(compressed, sub);
                break;
            case kBassDoom:
                output = softClip(compressed * 5.8f) * 0.58f + sub * 0.42f;
                break;
            case kBassGrit:
            default:
                output = softClip(compressed * 3.4f) * 0.70f + sub * 0.20f;
                break;
        }
        output = output * (0.72f + lowMid * 0.34f)
                + (input - low) * (hiMid - 0.5f) * 0.22f
                + sub * (blend - 0.5f) * 0.40f;
        // Same output ceiling as guitar: a maxed knob can't clip or go red.
        return output * (0.22f + level * 0.33f);
    }

    float bassSynth(float input, float sub) {
        float smoothed = smoothedPitchHz_.load();
        float pitch = smoothed > 20.0f ? smoothed : pitchHz_.load();
        if (pitch > 20.0f) {
            double increment = kTwoPi * pitch / static_cast<double>(sampleRate_);
            bassSynthPhase_ += increment;
            if (bassSynthPhase_ > kTwoPi) {
                bassSynthPhase_ -= kTwoPi;
            }
            float osc = static_cast<float>(std::sin(bassSynthPhase_));
            float square = osc >= 0.0f ? 1.0f : -1.0f;
            return softClip((osc * 0.42f + square * 0.20f + sub * 0.35f + input * 0.45f) * 1.2f);
        }
        // No confident pitch: don't amplify the raw input hard (that turned
        // room noise / bleed into a feedback-prone drone). Gentle passthrough.
        return softClip(input * 1.8f) * 0.55f + sub * 0.35f;
    }

    float processPiano(float input, int tone) {
        float attack = c1_;
        float toneControl = c2_;
        float modulation = c3_;
        float decay = c4_;
        float space = c5_;
        float level = c6_;

        float env = 0.0f;
        float pitch = 0.0f;
        bool midiMode = inputRoute_.load() == kRouteMidi;
        if (midiMode) {
            int id = noteOnId_.load(std::memory_order_relaxed);
            if (id != lastNoteId_) {
                lastNoteId_ = id;
                decayEnv_ = 1.0f;
                brightEnv_ = 1.0f;
            }
            float target = midiGate_.load() ? midiVelocity_.load() : 0.0f;
            float coeff = target > midiEnv_
                    ? 0.05f + attack * 0.20f
                    : 0.010f + decay * 0.020f;
            midiEnv_ += (target - midiEnv_) * coeff;
            env = clampFloat(midiEnv_, 0.0f, 0.98f);
            pitch = midiFrequency_.load();
        } else {
            float target = clampFloat(std::fabs(input) * 2.8f, 0.0f, 1.0f);
            float coeff = target > pianoEnv_
                    ? 0.006f + attack * 0.030f
                    : 0.0008f + decay * 0.0045f;
            pianoEnv_ += (target - pianoEnv_) * coeff;
            env = clampFloat(pianoEnv_, 0.0f, 0.95f);
            pitch = smoothedPitchHz_.load();
        }

        if (pitch < 35.0f) {
            return 0.0f;
        }

        double increment = kTwoPi * static_cast<double>(pitch) / static_cast<double>(sampleRate_);
        pianoPhase_ += increment;
        if (pianoPhase_ > kTwoPi) {
            pianoPhase_ -= kTwoPi;
        }

        bool percussive = midiMode && tone != kPianoOrgan;
        if (percussive) {
            float decaySeconds = 1.4f + decay * 5.5f;
            float brightSeconds = 0.18f + toneControl * 0.5f;
            decayEnv_ -= decayEnv_ / (decaySeconds * static_cast<float>(sampleRate_));
            brightEnv_ -= brightEnv_ / (brightSeconds * static_cast<float>(sampleRate_));
            if (decayEnv_ < 0.0f) {
                decayEnv_ = 0.0f;
            }
            if (brightEnv_ < 0.0f) {
                brightEnv_ = 0.0f;
            }
        }
        float voiceEnv = percussive ? env * decayEnv_ : env;

        double p = pianoPhase_;
        float output = 0.0f;
        switch (tone) {
            case kPianoElectric: {
                float bright = 0.25f + 0.75f * brightEnv_ + toneControl * 0.30f;
                output = static_cast<float>(
                        std::sin(p)
                                + 0.45 * bright * std::sin(p * 2.0)
                                + 0.30 * bright * std::sin(p * 4.0)
                                + 0.14 * bright * bright * std::sin(p * 6.0)
                );
                output = softClip(output * 0.70f) * voiceEnv * 0.85f;
                break;
            }
            case kPianoOrgan:
                output = static_cast<float>(
                        std::sin(p) * 0.55
                                + std::sin(p * 2.0) * 0.28
                                + std::sin(p * 4.0) * 0.18
                                + std::sin(p * 8.0) * 0.08
                );
                output *= env * 0.70f;
                break;
            case kPianoBell:
                output = static_cast<float>(
                        std::sin(p + (2.5 + modulation * 4.0) * std::sin(p * 3.0))
                                + 0.18 * std::sin(p * 5.0)
                );
                output *= voiceEnv * 0.62f;
                break;
            case kPianoFm:
            default: {
                float bright = 0.30f + 0.70f * brightEnv_ + toneControl * 0.35f;
                float b2 = bright * bright;
                output = static_cast<float>(
                        std::sin(p) * 1.00
                                + std::sin(p * 2.0) * 0.55 * bright
                                + std::sin(p * 3.0) * 0.36 * bright
                                + std::sin(p * 4.0) * 0.22 * b2
                                + std::sin(p * 5.0) * 0.14 * b2
                                + std::sin(p * 6.0) * 0.085 * b2 * bright
                );
                output = softClip(output * 0.50f) * voiceEnv * 0.92f;
                break;
            }
        }

        float ambience = static_cast<float>(std::sin(p * 0.5) * 0.06 * space) * voiceEnv;
        return (output + ambience) * (0.35f + level * 0.95f);
    }

    void updateMeter(float sumSquares, int32_t frames) {
        if (frames <= 0) {
            return;
        }
        float rms = std::sqrt(sumSquares / static_cast<float>(frames));
        float smoothed = meterRms_ * 0.88f + rms * 0.12f;
        meterRms_ = smoothed;
        float db = smoothed > 0.000001f ? 20.0f * std::log10(smoothed) : -120.0f;
        inputLevelDb_.store(clampFloat(db, -120.0f, 0.0f));
    }

    // One fixed post-mix gain for every route. There is no envelope, look-ahead,
    // or automatic gain recovery here, so playing harder never turns the rig
    // down. The ceiling only catches accidental summed peaks.
    // Play the loaded chime once. A new trigger is ignored while it is still
    // sounding, so it never stacks or restarts on itself.
    void mixChimes(float *out, int32_t numFrames) {
        if (chimeTrigger_.exchange(false)
                && chimeReady_.load(std::memory_order_acquire)
                && !chimeActive_.load(std::memory_order_relaxed)) {
            chimePos_ = 0.0;
            chimeActive_.store(true);
        }
        if (!chimeActive_.load(std::memory_order_relaxed)) return;
        int frames = chimeSampleFrames_;
        if (frames <= 1) { chimeActive_.store(false); return; }
        const float *s = chimeSample_.data();
        float g = chimeGain_.load(std::memory_order_relaxed);
        double step = static_cast<double>(chimeSampleRate_)
                / static_cast<double>(sampleRate_ > 0 ? sampleRate_ : 48000);
        for (int32_t n = 0; n < numFrames; ++n) {
            int i0 = static_cast<int>(chimePos_);
            if (i0 >= frames - 1) { chimeActive_.store(false); break; }
            float fr = static_cast<float>(chimePos_ - i0);
            float l = s[i0 * 2] * (1.0f - fr) + s[(i0 + 1) * 2] * fr;
            float r = s[i0 * 2 + 1] * (1.0f - fr) + s[(i0 + 1) * 2 + 1] * fr;
            out[n * 2] += l * g;
            out[n * 2 + 1] += r * g;
            chimePos_ += step;
        }
    }

    void mixSwells(float *out, int32_t numFrames) {
        int delay = std::max(1, sampleRate_ / 200);  // 5 ms at the output rate
        for (int sample = 0; sample < 6; ++sample) {
            int pending = swellPending_[sample].exchange(0, std::memory_order_acquire);
            if (!swellReady_[sample].load(std::memory_order_acquire)) continue;
            while (pending-- > 0) {
                for (int layer = 0; layer < kSwellLayers; ++layer) {
                    int voiceIndex = -1;
                    for (int scan = 0; scan < kMaxSwellVoices; ++scan) {
                        int candidate = (swellVoiceCursor_ + scan) % kMaxSwellVoices;
                        if (!swellVoice_[candidate].active) {
                            voiceIndex = candidate;
                            break;
                        }
                    }
                    if (voiceIndex < 0) voiceIndex = swellVoiceCursor_;
                    swellVoiceCursor_ = (voiceIndex + 1) % kMaxSwellVoices;
                    SwellVoice &voice = swellVoice_[voiceIndex];
                    voice.sample = sample;
                    voice.pos = 0.0;
                    voice.delay = layer * delay;
                    voice.layer = layer;
                    voice.active = true;
                }
            }
        }
        for (int voiceIndex = 0; voiceIndex < kMaxSwellVoices; ++voiceIndex) {
            SwellVoice &voice = swellVoice_[voiceIndex];
            if (!voice.active || voice.sample < 0 || voice.sample >= 6) continue;
            int sample = voice.sample, frames = swellSampleFrames_[sample];
            if (frames <= 1) { voice.active = false; continue; }
            const float *s = swellSample_[sample].data();
            double step = static_cast<double>(swellSampleRate_[sample])
                    / static_cast<double>(sampleRate_ > 0 ? sampleRate_ : 48000);
            for (int32_t n = 0; n < numFrames; ++n) {
                if (voice.delay > 0) { --voice.delay; continue; }
                int i0 = static_cast<int>(voice.pos);
                if (i0 >= frames - 1) { voice.active = false; break; }
                float fr = static_cast<float>(voice.pos - i0);
                float l = s[i0 * 2] * (1.0f - fr) + s[(i0 + 1) * 2] * fr;
                float r = s[i0 * 2 + 1] * (1.0f - fr) + s[(i0 + 1) * 2 + 1] * fr;
                // Five 5 ms-spaced layers reach 125% total: the first is 105%
                // and each repeat adds 5%. Equal-level copies would produce a
                // strong metallic comb filter and overload the output.
                float layerGain = voice.layer == 0 ? 1.05f : 0.05f;
                out[n * 2] += l * layerGain;
                out[n * 2 + 1] += r * layerGain;
                voice.pos += step;
            }
        }
    }

    float finalizeOutput(float *out, int32_t numFrames) {
        static constexpr float kOutputTrim = 0.80f;
        static constexpr float kOutputCeiling = 0.86f;
        bool mono = monoOut_.load(std::memory_order_relaxed);
        float sumSquares = 0.0f;
        for (int32_t f = 0; f < numFrames; ++f) {
            float l = out[f * 2];
            float r = out[f * 2 + 1];
            if (mono) {
                // Sum L+R to a single mono signal on both channels. Feeding one
                // mixer channel (or a mono PA send) then keeps everything —
                // panned parts and stereo-FX content don't drop or phase-cancel.
                float m = (l + r) * 0.5f;
                l = m;
                r = m;
            }
            l = clampFloat(l * kOutputTrim, -kOutputCeiling, kOutputCeiling);
            r = clampFloat(r * kOutputTrim, -kOutputCeiling, kOutputCeiling);
            out[f * 2] = l;
            out[f * 2 + 1] = r;
            sumSquares += l * l + r * r;
        }
        return sumSquares;
    }

    // Separate meter for what is actually sent to the speaker (the UI OUT bar).
    void updateOutMeter(float sumSquares, int32_t frames) {
        if (frames <= 0) {
            return;
        }
        float rms = std::sqrt(sumSquares / static_cast<float>(frames));
        float smoothed = outMeterRms_ * 0.88f + rms * 0.12f;
        outMeterRms_ = smoothed;
        float db = smoothed > 0.000001f ? 20.0f * std::log10(smoothed) : -120.0f;
        outputLevelDb_.store(clampFloat(db, -120.0f, 0.0f));
    }

    // Synthesize a click on each beat (downbeat of 4 accented) into the stereo mix.
    void mixMetronome(float *out, int32_t numFrames) {
        if (metroResetReq_.exchange(false)) {   // count-in: restart on beat 1
            metroPhase_ = 0;
            metroBeat_ = 0;
        }
        if (!metronomeOn_.load()) {
            return;
        }
        int bpm = metronomeBpm_.load();
        if (bpm < 30) bpm = 30;
        if (bpm > 300) bpm = 300;
        int period = static_cast<int>(static_cast<double>(sampleRate_) * 60.0 / bpm);
        if (period < 1) period = 1;
        int clickLen = sampleRate_ / 50;   // ~20 ms
        for (int32_t f = 0; f < numFrames; ++f) {
            if (metroPhase_ < clickLen) {
                float t = static_cast<float>(metroPhase_) / static_cast<float>(clickLen);
                float env = 1.0f - t;
                float freq = (metroBeat_ == 0) ? 1600.0f : 1000.0f;
                float s = std::sin(2.0 * kPi * freq * metroPhase_ / sampleRate_) * env * env * 0.30f;
                out[f * 2] += s;
                out[f * 2 + 1] += s;
            }
            if (++metroPhase_ >= period) {
                metroPhase_ = 0;
                int beats = metroBeats_.load();
                metroBeat_ = (metroBeat_ + 1) % (beats < 1 ? 1 : beats);
            }
        }
    }

    void setSeqBit(int key, bool on) {
        if (key < 0 || key > 127) return;
        std::atomic<uint64_t> &m = (key < 64) ? seqActiveLo_ : seqActiveHi_;
        uint64_t bit = 1ULL << (key & 63);
        if (on) m.fetch_or(bit); else m.fetch_and(~bit);
    }

    // Audio thread: advance the MIDI file player, firing due notes into the piano
    // font (all merged to channel 0, drum channel skipped). Non-blocking.
    void advanceMidiPlayback(tsf *snd, int32_t numFrames) {
        if (seqFlushReq_.exchange(false)) {
            tsf_channel_note_off_all(snd, 0);
            seqActiveLo_.store(0); seqActiveHi_.store(0);
            glideStackN_ = 0;
            glideAnchor_ = -1;
        }
        if (!seqPlaying_.load()) {
            if (seqWasPlaying_) { seqActiveLo_.store(0); seqActiveHi_.store(0); }
            seqWasPlaying_ = false;
            return;
        }
        std::unique_lock<std::mutex> lock(seqMutex_, std::try_to_lock);
        if (!lock.owns_lock()) {
            return;
        }
        seqWasPlaying_ = true;
        seqMs_ += static_cast<double>(numFrames) * 1000.0 / static_cast<double>(sampleRate_);
        bool glide = glideOn_.load(std::memory_order_relaxed);
        while (seqPos_ < seq_.size() && seq_[seqPos_].t <= seqMs_) {
            const SeqEvent &e = seq_[seqPos_++];
            if (e.ch == 9) continue;   // skip drum channel
            if (e.on && e.vel > 0) {
                float v = e.vel / 127.0f;
                if (v > 0.8f) v = 0.8f;
                // Slide mode covers the MIDI player too: overlapping (legato)
                // notes in the file bend instead of re-attacking. Shares the
                // live-keys glide state — both play the same channel.
                if (glide && glideStackN_ > 0 && glideAnchor_ >= 0) {
                    glidePush(e.key);
                    glideOffTarget_ = static_cast<float>(e.key - glideAnchor_);
                } else {
                    if (glide) {
                        glideStackN_ = 0;
                        glidePush(e.key);
                        glideAnchor_ = e.key;
                        glideOffTarget_ = 0.0f;
                        if (glideOffCur_ != 0.0f) {
                            glideOffCur_ = 0.0f;
                            tsf_channel_set_tuning(snd, 0, 0.0f);
                        }
                    }
                    tsf_channel_note_on(snd, 0, e.key, v);
                }
                setSeqBit(e.key, true);
            } else {
                if (glide) {
                    glideRemove(e.key);
                    if (glideStackN_ > 0 && glideAnchor_ >= 0) {
                        glideOffTarget_ = static_cast<float>(
                                glideStack_[glideStackN_ - 1] - glideAnchor_);
                    } else {
                        int rel = glideAnchor_ >= 0 ? glideAnchor_ : e.key;
                        glideAnchor_ = -1;
                        tsf_channel_note_off(snd, 0, rel);
                    }
                } else {
                    tsf_channel_note_off(snd, 0, e.key);
                }
                setSeqBit(e.key, false);
            }
        }
        if (seqPos_ >= seq_.size()) {
            if (seqLoop_.load()) {
                seqPos_ = 0;
                seqMs_ = 0.0;
                tsf_channel_note_off_all(snd, 0);
                seqActiveLo_.store(0); seqActiveHi_.store(0);
                glideStackN_ = 0;
                glideAnchor_ = -1;
            } else {
                seqPlaying_.store(false);
                seqActiveLo_.store(0); seqActiveHi_.store(0);
            }
        }
        seqPositionMs_.store(seqMs_);
    }

    // Audio-thread tap: push the clean stereo mix into the record ring (lock-free).
    void pushRecording(const float *out, int32_t numFrames) {
        if (!recording_.load(std::memory_order_acquire)) {
            return;
        }
        size_t cap = recordRing_.size();
        if (cap == 0) return;
        size_t w = recordWrite_.load(std::memory_order_relaxed);
        size_t r = recordRead_.load(std::memory_order_acquire);
        int32_t count = numFrames * 2;
        for (int32_t i = 0; i < count; ++i) {
            size_t next = (w + 1) % cap;
            if (next == r) break;   // ring full: drop (writer fell behind)
            recordRing_[w] = out[i];
            w = next;
        }
        recordWrite_.store(w, std::memory_order_release);
    }

    void startRecording(const std::string &path) {
        if (recording_.load()) return;
        if (recordRing_.empty()) {
            recordRing_.resize(static_cast<size_t>(sampleRate_) * 2 * 4);   // 4 s stereo
        }
        recordPath_ = path;
        recordWrite_.store(0);
        recordRead_.store(0);
        recording_.store(true, std::memory_order_release);
        recordThread_ = std::thread(&InstrumentalEngine::recordWriterLoop, this);
    }

    void stopRecording() {
        if (!recording_.load()) return;
        recording_.store(false, std::memory_order_release);
        if (recordThread_.joinable()) {
            recordThread_.join();
        }
    }

    // Writer thread: drain the ring to a 16-bit PCM stereo WAV, fixing up the
    // header sizes once recording stops.
    void recordWriterLoop() {
        FILE *fp = std::fopen(recordPath_.c_str(), "wb");
        if (fp == nullptr) {
            recording_.store(false);
            return;
        }
        writeWavHeader(fp, 0);
        uint32_t dataBytes = 0;
        std::vector<int16_t> buf;
        buf.reserve(4096);
        for (;;) {
            size_t r = recordRead_.load(std::memory_order_relaxed);
            size_t w = recordWrite_.load(std::memory_order_acquire);
            if (r == w) {
                if (!recording_.load(std::memory_order_acquire)) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(5));
                continue;
            }
            buf.clear();
            size_t cap = recordRing_.size();
            while (r != w) {
                float s = recordRing_[r];
                int v = static_cast<int>(s * 32767.0f);
                if (v > 32767) v = 32767;
                if (v < -32768) v = -32768;
                buf.push_back(static_cast<int16_t>(v));
                r = (r + 1) % cap;
            }
            recordRead_.store(r, std::memory_order_release);
            std::fwrite(buf.data(), sizeof(int16_t), buf.size(), fp);
            dataBytes += static_cast<uint32_t>(buf.size() * sizeof(int16_t));
        }
        std::fseek(fp, 0, SEEK_SET);
        writeWavHeader(fp, dataBytes);
        std::fclose(fp);
    }

    void writeWavHeader(FILE *fp, uint32_t dataBytes) {
        uint32_t rate = static_cast<uint32_t>(sampleRate_);
        uint16_t channels = 2, bits = 16;
        uint32_t byteRate = rate * channels * (bits / 8);
        uint16_t blockAlign = channels * (bits / 8);
        uint32_t riff = 36 + dataBytes;
        auto w32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, fp); };
        auto w16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, fp); };
        std::fwrite("RIFF", 1, 4, fp); w32(riff); std::fwrite("WAVE", 1, 4, fp);
        std::fwrite("fmt ", 1, 4, fp); w32(16); w16(1); w16(channels);
        w32(rate); w32(byteRate); w16(blockAlign); w16(bits);
        std::fwrite("data", 1, 4, fp); w32(dataBytes);
    }

    // Producer side (MIDI binder thread, UI tap thread). Cheap mutex; never the
    // audio thread.
    void enqueueEvent(int type, int key, int vel) {
        std::lock_guard<std::mutex> lock(producerMutex_);
        int head = eventHead_.load(std::memory_order_relaxed);
        int next = (head + 1) % kEventQueueSize;
        if (next == eventTail_.load(std::memory_order_acquire)) {
            return;   // ring full
        }
        eventBuffer_[head][0] = type;
        eventBuffer_[head][1] = key;
        eventBuffer_[head][2] = vel;
        eventHead_.store(next, std::memory_order_release);
    }

    // Audio thread: drain queued note events into the SoundFont synth. Lock-free:
    // the consumer never blocks, so events are always processed on the next callback.
    // alt = font whose channel 1 plays the layer / dual sound 2 (usually the GM
    // font; may equal snd). With a key split set, notes >= split play ONLY the
    // alt sound; otherwise alt doubles every note (layered preset).
    // Slide mode bookkeeping: keys currently held, newest last.
    static void glideStackPush(int *stack, int &n, int note) {
        glideStackRemove(stack, n, note);
        if (n < 16) stack[n++] = note;
    }

    static void glideStackRemove(int *stack, int &n, int note) {
        for (int i = 0; i < n; ++i) {
            if (stack[i] == note) {
                for (int j = i; j < n - 1; ++j) stack[j] = stack[j + 1];
                n--;
                return;
            }
        }
    }

    void glidePush(int note) { glideStackPush(glideStack_, glideStackN_, note); }

    void glideRemove(int note) { glideStackRemove(glideStack_, glideStackN_, note); }

    // Shared legato logic for one glide stream. Returns true when the note-on
    // was consumed as a bend of the sounding anchor voice (no new attack).
    static bool glideOnHit(int *stack, int &n, int &anchor, float &offTarget,
                           float &offCur, tsf *f, int ch, int note) {
        if (n > 0 && anchor >= 0) {
            glideStackPush(stack, n, note);
            offTarget = static_cast<float>(note - anchor);
            return true;
        }
        n = 0;
        glideStackPush(stack, n, note);
        anchor = note;
        offTarget = 0.0f;
        if (offCur != 0.0f) {   // fresh attack starts in tune
            offCur = 0.0f;
            if (f != nullptr) tsf_channel_set_tuning(f, ch, 0.0f);
        }
        return false;
    }

    // Note-off for one glide stream. Returns the key to release, or -1 while
    // other held keys keep the voice (slide back to the newest of them).
    static int glideOffHit(int *stack, int &n, int &anchor, float &offTarget, int note) {
        glideStackRemove(stack, n, note);
        if (n > 0 && anchor >= 0) {
            offTarget = static_cast<float>(stack[n - 1] - anchor);
            return -1;
        }
        int rel = anchor >= 0 ? anchor : note;
        anchor = -1;
        return rel;
    }

    void drainEvents(tsf *snd, tsf *alt, tsf *gm = nullptr) {
        if (virtualGuitarReset_.exchange(false, std::memory_order_relaxed)) {
            resetVirtualGuitarPlayer();
        }
        // A live MIDI sustain pedal (CC64) holds notes until lifted; the on-screen
        // toggle holds them for a fixed time. Effective sustain = either active.
        bool pedal = sustainPedal_.load();
        bool toggle = sustainOn_.load();
        bool sustain = pedal || toggle;
        int split = alt != nullptr ? midiKeySplit_.load(std::memory_order_relaxed) : -1;
        // Slide (glide) mode: a key pressed while another is held bends the
        // sounding voice to the new pitch instead of re-attacking — string
        // bends / slides on a keyboard. With dual active, each sound has its
        // OWN glide stream (stream 2 = the Sound 2 / upper-split channel), so
        // slide works on both sides at once.
        bool glide = glideOn_.load(std::memory_order_relaxed);
        bool mono = glide && glideMono_.load(std::memory_order_relaxed);
        if (!glide && (glideStackN_ != 0 || glide2StackN_ != 0)) {   // mode left mid-hold
            glideStackN_ = 0;
            glideAnchor_ = -1;
            glide2StackN_ = 0;
            glide2Anchor_ = -1;
        }
        int64_t sustainSamples = static_cast<int64_t>(sustainSeconds_.load() * static_cast<float>(sampleRate_));
        int head = eventHead_.load(std::memory_order_acquire);
        int tail = eventTail_.load(std::memory_order_relaxed);
        int blend = layerBlendMode_.load();
        while (tail != head) {
            int *e = eventBuffer_[tail];
            int note = e[1] & 0x7F;
            if (blend > 0) {
                blendEvent(snd, alt, gm, e, blend, pedal, toggle, sustainSamples);
                tail = (tail + 1) % kEventQueueSize;
                continue;
            }
            if (e[0] == kEvNoteOn) {
                pendingRelease_[note] = -1;
                // Cap velocity: several dedicated fonts have no sample zone at
                // 127, so full-velocity hits would produce no voice (silence).
                float vel = static_cast<float>(e[2]) / 127.0f;
                if (vel > 0.8f) {
                    vel = 0.8f;
                }
                if (virtualGuitarPlayer_.load(std::memory_order_relaxed)) {
                    int replaced = allocateVirtualGuitarString(note);
                    if (replaced >= 0 && replaced != note) {
                        stopVirtualGuitarNote(snd, alt, replaced);
                        extraLayersOff(gm, replaced);
                        pendingRelease_[replaced] = -1;
                    }
                    virtualGuitarPickDown_ = !virtualGuitarPickDown_;
                    virtualGuitarStrike_ = vel;
                    ++virtualGuitarPickSeq_;
                    tsf *art = nullptr;
                    int source = 0;
                    if (vel < 0.38f) {
                        art = hqFonts_[kVirtualGuitarPalmSlot].load();
                        source = art != nullptr ? 1 : 0;
                    } else if (vel > 0.72f) {
                        art = hqFonts_[kVirtualGuitarHarmSlot].load();
                        source = art != nullptr ? 2 : 0;
                    }
                    if (art != nullptr) {
                        stopVirtualGuitarNote(snd, alt, note);
                        chokeSameKey(art, 0, note);
                        tsf_channel_note_on(art, 0, note, vel);
                        virtualGuitarNoteSource_[note] = source;
                        tail = (tail + 1) % kEventQueueSize;
                        continue;
                    }
                    virtualGuitarNoteSource_[note] = 0;
                }
                if (split >= 0) {
                    // Dual split: each side is its own sound AND its own
                    // glide stream, so slide works on both keyboards.
                    bool up = note >= split;
                    tsf *f = up ? alt : snd;
                    int ch = up ? 1 : 0;
                    bool legato = glide && glideOnHit(
                            up ? glide2Stack_ : glideStack_,
                            up ? glide2StackN_ : glideStackN_,
                            up ? glide2Anchor_ : glideAnchor_,
                            up ? glide2OffTarget_ : glideOffTarget_,
                            up ? glide2OffCur_ : glideOffCur_, f, ch, note);
                    if (!legato) {
                        // Mono slide: the previous voice's tail must not stack
                        // under a detached press — release it before attacking.
                        if (mono) {
                            tsf_channel_note_off_all(f, ch);
                        } else {
                            chokeSameKey(f, ch, note);   // replace, don't stack
                        }
                        tsf_channel_note_on(f, ch, note, vel);
                        extraLayersOn(gm, note, vel);   // layers apply across the split too
                    }
                } else if (manualSplit_.load(std::memory_order_relaxed)) {
                    // Full Keys per-board routing: noteOn is Sound 1 ONLY. Sound 2
                    // arrives as note2On from the upper board — never auto-layered.
                    bool legato = glide && glideOnHit(glideStack_, glideStackN_,
                            glideAnchor_, glideOffTarget_, glideOffCur_, snd, 0, note);
                    if (!legato) {
                        if (mono) tsf_channel_note_off_all(snd, 0);
                        else chokeSameKey(snd, 0, note);
                        tsf_channel_note_on(snd, 0, note, vel);
                        extraLayersOn(gm, note, vel);
                    }
                } else {
                    // Dual layer mode: Sound 2 mirrors the same notes, stream 2
                    // simply tracks stream 1 so both channels bend together.
                    bool legato = glide && glideOnHit(glideStack_, glideStackN_,
                            glideAnchor_, glideOffTarget_, glideOffCur_, snd, 0, note);
                    if (glide && alt != nullptr) {
                        glideOnHit(glide2Stack_, glide2StackN_, glide2Anchor_,
                                glide2OffTarget_, glide2OffCur_, alt, 1, note);
                    }
                    if (!legato) {
                        if (mono) {
                            tsf_channel_note_off_all(snd, 0);
                            if (alt != nullptr) tsf_channel_note_off_all(alt, 1);
                        } else {
                            chokeSameKey(snd, 0, note);   // replace, don't stack
                            if (alt != nullptr) chokeSameKey(alt, 1, note);
                        }
                        tsf_channel_note_on(snd, 0, note, vel);
                        if (alt != nullptr) {
                            tsf_channel_note_on(alt, 1, note, vel * 0.9f);
                        }
                        extraLayersOn(gm, note, vel);   // layers 3 & 4 (ch 2 & 3)
                    }
                }
            } else if (e[0] == kEvNoteOff
                    && virtualGuitarPlayer_.load(std::memory_order_relaxed)) {
                stopVirtualGuitarNote(snd, alt, note);
                pendingRelease_[note] = -1;
                releaseVirtualGuitarString(note);
                extraLayersOff(gm, note);
            } else if (e[0] == kEvAllOff) {
                tsf_note_off_all(snd);
                if (alt != nullptr && alt != snd) {
                    tsf_note_off_all(alt);
                }
                tsf *palm = hqFonts_[kVirtualGuitarPalmSlot].load();
                tsf *harm = hqFonts_[kVirtualGuitarHarmSlot].load();
                if (palm != nullptr) tsf_note_off_all(palm);
                if (harm != nullptr) tsf_note_off_all(harm);
                extraLayersAllOff(gm);
                for (int n = 0; n < 128; ++n) {
                    pendingRelease_[n] = -1;
                }
                glideStackN_ = 0;
                glideAnchor_ = -1;
                glideOffCur_ = 0.0f;
                glideOffTarget_ = 0.0f;
                glide2StackN_ = 0;
                glide2Anchor_ = -1;
                glide2OffCur_ = 0.0f;
                glide2OffTarget_ = 0.0f;
                tsf_channel_set_tuning(snd, 0, 0.0f);
                if (alt != nullptr) tsf_channel_set_tuning(alt, 1, 0.0f);
                resetVirtualGuitarPlayer();
            } else if (e[0] == kEvNote2On) {
                // Dual Sound 2, routed in Java: the layer channel, with its
                // own glide stream so slide works on Sound 2 too.
                pendingRelease_[note] = -1;
                float vel = static_cast<float>(e[2]) / 127.0f;
                if (vel > 0.8f) vel = 0.8f;
                tsf *f = alt != nullptr ? alt : snd;
                int ch = alt != nullptr ? 1 : 0;
                bool legato = glide && alt != nullptr && glideOnHit(glide2Stack_,
                        glide2StackN_, glide2Anchor_, glide2OffTarget_, glide2OffCur_,
                        f, ch, note);
                if (!legato) {
                    if (mono && alt != nullptr) {
                        tsf_channel_note_off_all(f, ch);
                    } else {
                        chokeSameKey(f, ch, note);   // replace, don't stack
                    }
                    tsf_channel_note_on(f, ch, note, vel);
                    extraLayersOn(gm, note, vel);   // layers apply to the Sound 2 manual too
                }
            } else if (e[0] == kEvNote2Off) {
                if (glide && alt != nullptr) {
                    int rel = glideOffHit(glide2Stack_, glide2StackN_, glide2Anchor_,
                            glide2OffTarget_, note);
                    if (rel >= 0) {
                        if (pedal) {
                            pendingRelease_[rel] = kSustainHeld;
                        } else if (toggle) {
                            pendingRelease_[rel] = sampleClock_ + sustainSamples;
                        } else {
                            tsf_channel_note_off(alt, 1, rel);
                        }
                    }
                } else if (pedal) {
                    pendingRelease_[note] = kSustainHeld;
                } else if (toggle) {
                    pendingRelease_[note] = sampleClock_ + sustainSamples;
                } else if (alt != nullptr) {
                    tsf_channel_note_off(alt, 1, note);
                    extraLayersOff(gm, note);
                } else {
                    tsf_channel_note_off(snd, 0, note);
                    extraLayersOff(gm, note);
                }
            } else if (glide) {
                // Note-off on the stream the key belongs to; the released
                // voice (anchor) honors sustain as usual and its tail decays
                // at the current bent pitch.
                bool up = split >= 0 && note >= split;
                int rel = up
                        ? glideOffHit(glide2Stack_, glide2StackN_, glide2Anchor_,
                                glide2OffTarget_, note)
                        : glideOffHit(glideStack_, glideStackN_, glideAnchor_,
                                glideOffTarget_, note);
                if (!up && split < 0 && alt != nullptr) {
                    glideOffHit(glide2Stack_, glide2StackN_, glide2Anchor_,
                            glide2OffTarget_, note);
                }
                if (rel >= 0) {
                    if (pedal) {
                        pendingRelease_[rel] = kSustainHeld;
                    } else if (toggle) {
                        pendingRelease_[rel] = sampleClock_ + sustainSamples;
                    } else if (up) {
                        tsf_channel_note_off(alt, 1, rel);
                    } else {
                        tsf_channel_note_off(snd, 0, rel);
                        if (split < 0 && alt != nullptr && !manualSplit_.load(std::memory_order_relaxed)) {
                            tsf_channel_note_off(alt, 1, rel);
                        }
                        if (split < 0) extraLayersOff(gm, rel);
                    }
                }
            } else if (pedal) {
                pendingRelease_[note] = kSustainHeld;   // hold until the pedal lifts
            } else if (toggle) {
                pendingRelease_[note] = sampleClock_ + sustainSamples;
            } else {
                releaseVirtualGuitarString(note);
                tsf_channel_note_off(snd, 0, note);
                // Per-board (Full Keys): a Sound 1 note-off must NOT cut a Sound 2
                // note at the same pitch (upper board owns alt via note2Off).
                if (alt != nullptr && !manualSplit_.load(std::memory_order_relaxed)) {
                    tsf_channel_note_off(alt, 1, note);
                }
                extraLayersOff(gm, note);
            }
            tail = (tail + 1) % kEventQueueSize;
        }
        eventTail_.store(tail, std::memory_order_release);
        // Release held notes when their timer elapses, or immediately once sustain
        // is fully released (pedal up and toggle off). Pedal holds (kSustainHeld)
        // never time out — they wait for the pedal.
        for (int n = 0; n < 128; ++n) {
            int64_t pr = pendingRelease_[n];
            if (pr < 0) continue;
            bool timedOut = (pr != kSustainHeld) && (pr <= sampleClock_);
            if (!sustain || (timedOut && !pedal)) {
                tsf_channel_note_off(snd, 0, n);
                if (alt != nullptr) {
                    tsf_channel_note_off(alt, 1, n);
                }
                extraLayersOff(gm, n);
                if (blend > 0) layerStackOffNote(snd, alt, gm, n);
                pendingRelease_[n] = -1;
                releaseVirtualGuitarString(n);
            }
        }
    }

    // TR-808 has gaps (no ride, no pedal hat, missing toms) — steer every empty
    // pad to a real 808 voice so the whole kit plays.
    static int remap808(int n) {
        switch (n) {
            case 51: return 56;  // Ride pad   -> 808 cowbell
            case 47: return 64;  // Tom 2 pad  -> 808 low conga
            case 43: return 45;  // Tom 3 pad  -> 808 low tom
            case 44: return 42;  // Pedal hat  -> 808 closed hat
            case 41: return 45;  // Low floor  -> 808 low tom
            case 48: return 50;  // Tom 1 pad  -> 808 high tom
            default: return n;
        }
    }

    // Reggae toms/floor (shared by both reggae kits): the font's rock toms don't
    // fit — reggae fills are high-tuned, OPEN, melodic — so play them on the
    // font's own timbale/bongo/conga voices, descending high -> low.
    static int reggaeToms(int n) {
        switch (n) {
            case 50: return 61;  // High tom   -> bongo tone (60 is the RIM CLICK, not a tone)
            case 48: return 61;  // Tom 1      -> low bongo
            case 47: return 63;  // Tom 2      -> open high conga
            case 45: return 64;  // Tom 3      -> low tumba (the earlier reggae tom 3)
            case 43: return 65;  // Floor      -> the open "kwam" (hi timbale)
            case 41: return 65;  // Low floor  -> kwam
            default: return n;
        }
    }

    // Reggae (remap 2, on the real reggae font): natural full-skin snare, rim
    // toggle gives the cross-stick; tuned open toms with the kwam on the floor.
    static int remapReggae(int n) {
        if (n == 40) return 37;  // Rim toggle -> cross-stick
        return reggaeToms(n);
    }

    // Mambo / Latin: remap the standard drum pads onto the GM Latin percussion
    // voices (congas, timbales, bongos, cowbell, claves, maracas, guiro) so the
    // kit plays like a Latin percussion section instead of a trap kit.
    static int remapMambo(int n) {
        switch (n) {
            case 38: return 66;  // Snare      -> low timbale
            case 40: return 65;  // Snare 2    -> high timbale
            case 37: return 75;  // Side stick -> claves
            case 50: return 60;  // High tom   -> hi bongo
            case 48: return 62;  // Hi-mid tom -> mute hi conga
            case 47: return 63;  // Low-mid tom-> open hi conga
            case 45: return 64;  // Low tom    -> low conga
            case 43: return 61;  // High floor -> low bongo
            case 41: return 64;  // Low floor  -> low conga
            case 42: return 69;  // Closed hat -> cabasa
            case 44: return 73;  // Pedal hat  -> short guiro
            case 46: return 70;  // Open hat   -> maracas
            case 49: return 56;  // Crash      -> cowbell
            case 57: return 56;  // Crash 2    -> cowbell
            case 51: return 68;  // Ride       -> low agogo
            case 59: return 67;  // Ride 2     -> high agogo
            default: return n;   // kick stays as the bombo
        }
    }

    // Beatbox / boom-bap: the 808 machine, but the snare is a hand clap — the
    // signature beatbox backbeat. Keeps the 808 pad fixes underneath.
    static int remapBeatbox(int n) {
        n = remap808(n);
        if (n == 38 || n == 40) return 39;  // Snare -> hand clap
        return n;
    }

    // Congas: the MEINL conga font only maps chromatic congas across MIDI 60-72,
    // so the standard drum pads (36-51) would be silent. Spread the pads across
    // the conga pitches low→high so every pad plays a real conga tone.
    // Reggae one-drop (remap 6): the SNARE carries the true reggae RIMSHOT
    // sample (font 37 = Rimshot_Reg); the RIM piece plays the Rock 3 snare
    // click GRAFTED into this font at note 33 (user: the bongo-rim tick
    // sounded like a mug/cowbell — wanted the Rock 3 rimshot). Font 38/40 are
    // big layered snare cracks (JV-1080/room) and are NOT used here.
    static int remapOneDrop(int n) {
        if (n == 38) return 37;
        if (n == 37) return 33;
        return reggaeToms(n);
    }

    static int remapCongas(int n) {
        switch (n) {
            case 35: case 36: case 44: return 60;   // kick / pedal -> lowest
            case 41: case 43: return 62;            // floor toms
            case 45: case 47: return 64;
            case 37: case 38: case 40: case 48: return 65;  // snare region -> mid
            case 50: return 67;
            case 42: return 69;                     // closed hat
            case 46: return 71;                     // open hat
            case 49: case 51: case 52: case 53: case 55: case 57: return 72;  // cymbals -> highest
            default: return (n >= 60 && n <= 72) ? n : 65;
        }
    }

    void resetVirtualGuitarPlayer() {
        for (int i = 0; i < 8; ++i) virtualGuitarStringNote_[i] = -1;
        for (int i = 0; i < 128; ++i) {
            virtualGuitarNoteString_[i] = -1;
            virtualGuitarNoteSource_[i] = -1;
        }
        for (int side = 0; side < 2; ++side) {
            for (int ch = 0; ch < 2; ++ch) {
                virtualGuitarPickEnv_[side][ch] = 0.0f;
                virtualGuitarSeenPick_[side][ch] = virtualGuitarPickSeq_;
            }
        }
    }

    int allocateVirtualGuitarString(int note) {
        if (note < 0 || note > 127) return -1;
        int current = virtualGuitarNoteString_[note];
        if (current >= 0 && current < 8) return virtualGuitarStringNote_[current];
        // F#1-B1-E2-A2-D3-G3-B3-E4. Prefer an unused string with a fret near
        // position five; if all playable strings are occupied, replace the
        // closest-position voice, preserving the physical eight-string limit.
        static constexpr int tuning[8] = {30, 35, 40, 45, 50, 55, 59, 64};
        int best = -1;
        int bestScore = 100000;
        for (int s = 0; s < 8; ++s) {
            int fret = note - tuning[s];
            if (fret < 0 || fret > 24) continue;
            int score = std::abs(fret - 5) * 4 - s;
            if (virtualGuitarStringNote_[s] >= 0) score += 1000;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        if (best < 0) return -1;   // outside the physical fretboard: play normally
        int replaced = virtualGuitarStringNote_[best];
        if (replaced >= 0 && replaced < 128) virtualGuitarNoteString_[replaced] = -1;
        virtualGuitarStringNote_[best] = note;
        virtualGuitarNoteString_[note] = best;
        return replaced;
    }

    void releaseVirtualGuitarString(int note) {
        if (!virtualGuitarPlayer_.load(std::memory_order_relaxed)
                || note < 0 || note > 127) return;
        int string = virtualGuitarNoteString_[note];
        if (string >= 0 && string < 8 && virtualGuitarStringNote_[string] == note) {
            virtualGuitarStringNote_[string] = -1;
        }
        virtualGuitarNoteString_[note] = -1;
        virtualGuitarNoteSource_[note] = -1;
    }

    void stopVirtualGuitarNote(tsf *snd, tsf *alt, int note) {
        if (snd != nullptr) tsf_channel_note_off_quick(snd, 0, note);
        if (alt != nullptr) tsf_channel_note_off_quick(alt, 1, note);
        tsf *palm = hqFonts_[kVirtualGuitarPalmSlot].load();
        tsf *harm = hqFonts_[kVirtualGuitarHarmSlot].load();
        if (palm != nullptr) tsf_channel_note_off_quick(palm, 0, note);
        if (harm != nullptr) tsf_channel_note_off_quick(harm, 0, note);
    }

    // Custom-kit source codes identify a font, not one of its presets. Most
    // fonts put a complete kit at preset 0; Studio is the exception, with
    // component-only presets first and its dry complete kit at index 9.
    static int fullKitPreset(int slot, tsf *snd) {
        int preferred = slot == 0 ? 9 : 0;
        int count = snd != nullptr ? tsf_get_presetcount(snd) : 0;
        return preferred >= 0 && preferred < count ? preferred : 0;
    }

    static bool presetHasNote(tsf *snd, int preset, int note) {
        if (snd == nullptr || preset < 0 || preset >= snd->presetNum) return false;
        struct tsf_preset *p = &snd->presets[preset];
        for (int r = 0; r < p->regionNum; ++r) {
            if (note >= p->regions[r].lokey && note <= p->regions[r].hikey) return true;
        }
        return false;
    }

    // drums_natural.sf2 (Rock 3, slot 3) declares MIDI 44 but its pedal-hat
    // region is silent. Route that articulation to the kit's audible tight hat.
    // Keep this slot-specific so every other kit retains its own pedal sample.
    static int kitSpecificDrumNote(int slot, int note) {
        return slot == 3 && note == 44 ? 42 : note;
    }

    // SoundFonts do not all implement the full GM percussion map. Keep a pad
    // musical by choosing another articulation from the same family and font,
    // then use the nearest available sample only as the final fallback.
    static int playableDrumNote(tsf *snd, int preset, int note) {
        if (presetHasNote(snd, preset, note)) return note;
        static const int kicks[] = {36, 35};
        static const int snares[] = {38, 40, 37, 39};
        static const int toms[] = {50, 48, 47, 45, 43, 41};
        static const int hats[] = {42, 44, 46};
        static const int cymbals[] = {49, 57, 51, 59, 52, 55, 53};
        const int *choices = nullptr;
        int count = 0;
        if (note == 35 || note == 36) {
            choices = kicks; count = static_cast<int>(sizeof(kicks) / sizeof(kicks[0]));
        } else if (note == 37 || note == 38 || note == 39 || note == 40) {
            choices = snares; count = static_cast<int>(sizeof(snares) / sizeof(snares[0]));
        } else if (note == 41 || note == 43 || note == 45 || note == 47
                || note == 48 || note == 50) {
            choices = toms; count = static_cast<int>(sizeof(toms) / sizeof(toms[0]));
        } else if (note == 42 || note == 44 || note == 46) {
            choices = hats; count = static_cast<int>(sizeof(hats) / sizeof(hats[0]));
        } else if (note == 49 || note == 51 || note == 52 || note == 53
                || note == 55 || note == 57 || note == 59) {
            choices = cymbals; count = static_cast<int>(sizeof(cymbals) / sizeof(cymbals[0]));
        }
        for (int i = 0; i < count; ++i) {
            if (presetHasNote(snd, preset, choices[i])) return choices[i];
        }
        for (int distance = 1; distance < 128; ++distance) {
            int lower = note - distance;
            int upper = note + distance;
            if (lower >= 0 && presetHasNote(snd, preset, lower)) return lower;
            if (upper < 128 && presetHasNote(snd, preset, upper)) return upper;
        }
        return note;
    }

    // Hi-hat exclusivity: closed (42), pedal (44) and open (46) are one hat — a
    // new hat articulation chokes the others (e.g. closing the pedal cuts the
    // ringing open hat). Font-independent (doesn't rely on exclusive-class data).
    enum LoopState { kLoopEmpty = 0, kLoopRecording = 1, kLoopPlaying = 2, kLoopOverdub = 3, kLoopPaused = 4, kLoopArmed = 5 };
    // Record gates: an armed track starts recording only when real audio hits --
    // a singing voice for the vocals track, an actual instrument hit for 1..3 --
    // so taps never capture leading silence or room noise.
    static constexpr float kLoopVocalGate = 0.09f;
    static constexpr float kLoopInstGate = 0.025f;

    // Granular pitch shifter: one shared delay line with up to four read heads
    // (two harmony voices + a detuned choir pair), each crossfading two grains
    // half a window apart. The grain window follows the tracked voice period
    // (pitch-synchronous), so grain seams line up with the waveform instead of
    // chopping it mid-cycle — that alignment is what kills the warble.
    float harmVoice(int v, float ratio) {
        const float window = harmWin_;
        harmPh_[v] -= (ratio - 1.0f);
        while (harmPh_[v] < 0.0f) harmPh_[v] += window;
        while (harmPh_[v] >= window) harmPh_[v] -= window;
        float d1 = harmPh_[v];
        float d2 = d1 + window * 0.5f;
        if (d2 >= window) d2 -= window;
        auto rd = [&](float d) {
            float pos = static_cast<float>(harmW_) - 1.0f - d;
            while (pos < 0.0f) pos += 4096.0f;
            int i = static_cast<int>(pos);
            float fr = pos - static_cast<float>(i);
            return harmBuf_[i & 4095] * (1.0f - fr) + harmBuf_[(i + 1) & 4095] * fr;
        };
        float half = window * 0.5f;
        float lin = d1 < half ? d1 / half : (window - d1) / half;
        // Equal-power crossfade: no level dip where the two grains meet.
        float fade1 = std::sin(lin * 1.5707964f);
        float fade2 = std::cos(lin * 1.5707964f);
        return rd(d1) * fade1 + rd(d2) * fade2;
    }

    // Pitch tracker feeding the grain window and the autotune snap:
    // autocorrelation on a 12kHz decimated copy of the mic, lags 24..150
    // (~80..500Hz). For the harmony grains an octave error is harmless, but the
    // autotune targets a real note, so we pick the true fundamental (not an
    // octave-down multiple) and interpolate the peak for a warble-free period.
    void harmDetectPitch() {
        float buf[512];
        for (int i = 0; i < 512; ++i) buf[i] = harmDecim_[(harmDecW_ + i) & 511];
        float energy = 1e-9f;
        for (int i = 0; i < 512; ++i) energy += buf[i] * buf[i];
        float score[151];
        float best = 0.0f;
        for (int lag = 24; lag <= 150; ++lag) {
            float sum = 0.0f;
            for (int i = 0; i < 512 - lag; ++i) sum += buf[i] * buf[i + lag];
            float s = sum / energy;
            score[lag] = s;
            if (s > best) best = s;
        }
        if (best <= 0.30f) return;   // unvoiced / silent: hold the last period
        // Pick the SHORTEST lag that reaches most of the peak — the true
        // fundamental, not one of the octave-down multiples (2T, 3T…) that
        // autocorrelation also scores highly. That octave error was making
        // autotune snap to the wrong note and the harmony sit an octave low.
        int bestLag = 24;
        float thresh = best * 0.85f;
        for (int lag = 24; lag <= 150; ++lag) {
            if (score[lag] >= thresh) { bestLag = lag; break; }
        }
        // Parabolic interpolation around the chosen peak → sub-sample period,
        // which is what removes the warble from the shifted voices.
        float lagI = static_cast<float>(bestLag);
        if (bestLag > 24 && bestLag < 150) {
            float sm = score[bestLag - 1], s0 = score[bestLag], sp = score[bestLag + 1];
            float denom = sm - 2.0f * s0 + sp;
            if (denom < -1e-6f) {
                lagI += clampFloat(0.5f * (sm - sp) / denom, -1.0f, 1.0f);
            }
        }
        float period = lagI * 4.0f;   // back to 48kHz samples
        harmPeriod_ += 0.5f * (period - harmPeriod_);
    }

    // Rough formant counter-tilt: a plain shifter drags the vocal resonances
    // along with the pitch, so big down-shifts boom ("dragon") and big
    // up-shifts turn shrill ("chipmunk"). Thin the lows of deep voices and
    // soften the top of high ones to pull both back toward "person".
    float harmTilt(int v, float x, float semi) {
        harmTiltLp_[v] += 0.18f * (x - harmTiltLp_[v]);
        if (semi <= -6.0f) return x - harmTiltLp_[v] * 0.45f;
        if (semi >= 11.0f) return harmTiltLp_[v];   // oct+: full warm-down, kills the squeak
        if (semi >= 6.0f) return harmTiltLp_[v] + (x - harmTiltLp_[v]) * 0.55f;
        return x;
    }

    // Semitone -> playback ratio, cached per head (pow is too hot per-sample).
    float harmRatio(int v, float semi) {
        if (semi != harmSemiCache_[v]) {
            harmSemiCache_[v] = semi;
            harmRatioCache_[v] = std::pow(2.0f, semi / 12.0f);
        }
        return harmRatioCache_[v];
    }

    // Per-sample voice analysis: delay line, gate envelope, pitch tracking,
    // grain window and pitch marks. Runs once whenever any voice FX is active.
    void harmAnalyze(float in) {
        harmBuf_[harmW_ & 4095] = in;
        harmW_++;
        // Gate envelope: ~1ms attack so the effects enter *with* the voice.
        float a = std::fabs(in);
        harmEnv_ += (a > harmEnv_ ? 0.02f : 0.0005f) * (a - harmEnv_);
        if (++harmDecPhase_ >= 4) {
            harmDecPhase_ = 0;
            harmDecim_[harmDecW_ & 511] = in;
            harmDecW_++;
            if (++harmDetCount_ >= 256) {   // re-detect every ~21ms (was 42ms)
                harmDetCount_ = 0;
                harmDetectPitch();
            }
        }
        float targetW = clampFloat(harmPeriod_, 260.0f, 1200.0f);
        harmWin_ += 0.002f * (targetW - harmWin_);
        // Approximate pitch marks (one per detected period) anchor PSOLA grains.
        harmMarkPhase_ += 1.0f;
        if (harmMarkPhase_ >= harmPeriod_) {
            harmMarkPhase_ -= harmPeriod_;
            harmLastMark_ = harmW_;
        }
    }

    // True TD-PSOLA voice: period-length grains of the input re-spaced at the
    // target pitch, each played back at ORIGINAL speed. The pitch moves but the
    // vocal-tract resonances (formants) stay put — this is what commercial
    // harmonizer pedals do, and why their shifted voices still sound human.
    float harmPsola(int v, float ratio) {
        float p = harmPeriod_;
        hgSpawn_[v] += 1.0f;
        if (hgSpawn_[v] >= p / ratio) {
            hgSpawn_[v] -= p / ratio;
            for (int g = 0; g < kHarmGrains; ++g) {
                if (hgOn_[v][g]) continue;
                float len = 2.0f * p;
                float d = static_cast<float>(harmW_ - harmLastMark_) + len;
                hgDi_[v][g] = static_cast<int>(d);
                hgDf_[v][g] = d - static_cast<float>(hgDi_[v][g]);
                hgPhase_[v][g] = 0.0f;
                hgStep_[v][g] = 1.0f / len;
                hgOn_[v][g] = true;
                break;
            }
        }
        int base = (harmW_ - 1) + (1 << 20);   // offset keeps the & mask positive
        float sum = 0.0f;
        for (int g = 0; g < kHarmGrains; ++g) {
            if (!hgOn_[v][g]) continue;
            float a = harmBuf_[(base - hgDi_[v][g]) & 4095];
            float b = harmBuf_[(base - hgDi_[v][g] - 1) & 4095];
            float s = a * (1.0f - hgDf_[v][g]) + b * hgDf_[v][g];
            float wnd = 0.5f - 0.5f * std::cos(6.2831853f * hgPhase_[v][g]);
            sum += s * wnd;
            hgPhase_[v][g] += hgStep_[v][g];
            if (hgPhase_[v][g] >= 1.0f) hgOn_[v][g] = false;
        }
        // Overlapping Hann grains sum to ~ratio; normalize back to unity.
        return sum / std::max(0.7f, ratio);
    }

    // Harmony bus: the configured companion voices + tone + gate.
    float harmVoicesOut() {
        float s1 = harmSemi1_.load(std::memory_order_relaxed);
        float s2 = harmSemi2_.load(std::memory_order_relaxed);
        // PSOLA keeps the singer's formants, so octaves only need a slight tuck.
        auto vgain = [](float s) {
            if (s <= -11.0f || s >= 11.0f) return 0.85f;
            return 1.0f;
        };
        float out = 0.0f;
        if (s1 < 90.0f) out += harmPsola(0, harmRatio(0, s1)) * vgain(s1);
        if (s2 < 90.0f) out += harmPsola(1, harmRatio(1, s2)) * 0.9f * vgain(s2);
        if (harmChoir_.load(std::memory_order_relaxed)) {
            out += (harmVoice(2, harmRatio(2, 0.22f))
                    + harmVoice(3, harmRatio(3, -0.22f))) * 0.55f;
        }
        int tone = harmTone_.load(std::memory_order_relaxed);
        if (tone == 1) {            // warm: roll the grain shimmer off (~2.5kHz)
            harmToneLp_ += 0.28f * (out - harmToneLp_);
            out = harmToneLp_;
        } else if (tone == 2) {     // bright: tilt the top up
            harmToneLp_ += 0.10f * (out - harmToneLp_);
            out += (out - harmToneLp_) * 0.8f;
        }
        // Duck the harmony when nobody is singing — no warbled room noise.
        float gate = clampFloat((harmEnv_ - 0.015f) * 80.0f, 0.0f, 1.0f);
        return out * gate;
    }

    // Autotune: snap the tracked pitch to the nearest semitone and PSOLA the
    // dry voice onto it. Hard snap + fast glide = the classic effect. Unvoiced
    // sounds and silence pass through dry via the gate crossfade.
    float harmTuneOut(float in) {
        float sr = static_cast<float>(sampleRate_ > 0 ? sampleRate_ : 48000);
        float f = sr / harmPeriod_;
        float n = 12.0f * std::log2(f / 440.0f);
        float target = 440.0f * std::pow(2.0f, std::round(n) / 12.0f);
        float ratio = clampFloat(target / f, 0.80f, 1.25f);
        // Crisp retune toward the grid. This only sounds right now that the
        // tracker locks the true fundamental — an octave-off period made
        // target/f collapse to ~1, which is why autotune seemed to do nothing.
        harmTuneRatio_ += 0.30f * (ratio - harmTuneRatio_);
        float g = clampFloat((harmEnv_ - 0.015f) * 80.0f, 0.0f, 1.0f);
        float tuned = harmPsola(2, harmTuneRatio_);
        return tuned * g + in * (1.0f - g);
    }

    // Audio thread: apply one pending UI command to a loop track's state machine.
    void loopApplyCommand(int t) {
        int cmd = loopCmd_[t].exchange(0);
        if (cmd == 0) return;
        int st = loopState_[t].load();
        int maxFrames = static_cast<int>(loopBuf_[t].size() / 2);
        if (cmd == 1) {           // tap: empty->armed->rec->overdub<->play, paused->play
            if (st == kLoopEmpty && maxFrames > 0) {
                loopLen_[t] = 0; loopPos_[t] = 0; st = kLoopArmed;
            } else if (st == kLoopArmed) {
                st = kLoopEmpty;      // tap again before any sound = cancel
            } else if (st == kLoopRecording) {
                loopLen_[t] = loopPos_[t]; loopPos_[t] = 0;
                if (loopLen_[t] > 0) {
                    st = kLoopOverdub;
                    beginOverdubSession(t);
                } else {
                    st = kLoopEmpty;
                }
            } else if (st == kLoopPlaying) {
                st = kLoopOverdub;
                beginOverdubSession(t);
            } else if (st == kLoopOverdub) {
                st = kLoopPlaying;
            } else if (st == kLoopPaused) {
                st = loopLen_[t] > 0 ? kLoopPlaying : kLoopEmpty;
            }
        } else if (cmd == 2) {    // pause toggle
            if (st == kLoopArmed) {
                st = kLoopEmpty;
            } else if (st == kLoopRecording) {
                loopLen_[t] = loopPos_[t]; loopPos_[t] = 0;
                st = loopLen_[t] > 0 ? kLoopPaused : kLoopEmpty;
            } else if (st == kLoopPlaying || st == kLoopOverdub) {
                st = kLoopPaused;
            } else if (st == kLoopPaused) {
                st = kLoopPlaying;
            }
        } else if (cmd == 3) {    // clear
            loopLen_[t] = 0; loopPos_[t] = 0; st = kLoopEmpty;
            snapActive_[t] = false; restoreActive_[t] = false; undoValid_[t] = false;
        } else if (cmd == 5) {    // mute toggle (loop keeps running silently)
            loopMuted_[t].store(!loopMuted_[t].load());
        } else if (cmd == 4) {    // undo last overdub session
            if (undoValid_[t] && (st == kLoopOverdub || st == kLoopPlaying)) {
                if (st == kLoopOverdub) st = kLoopPlaying;
                restoreExtent_[t] = snapDone_[t];
                restoreDone_[t] = 0;
                restoreActive_[t] = true;
                snapActive_[t] = false;
                undoValid_[t] = false;
            }
        }
        loopState_[t].store(st);
        loopLenShared_[t].store(loopLen_[t]);
    }

    // Audio thread: pause everything, or restart every loop together from 0
    // (the "all drop back in on the downbeat" move). Muted tracks resume too,
    // silently, so they stay in sync and can be unmuted mid-song.
    void loopApplyGlobal() {
        int cmd = loopGlobalCmd_.exchange(0);
        if (cmd == 0) return;
        for (int t = 0; t < kNumLoops; ++t) {
            int st = loopState_[t].load();
            if (cmd == 1) {
                if (st == kLoopRecording) {
                    loopLen_[t] = loopPos_[t]; loopPos_[t] = 0;
                    loopState_[t].store(loopLen_[t] > 0 ? kLoopPaused : kLoopEmpty);
                    loopLenShared_[t].store(loopLen_[t]);
                } else if (st == kLoopPlaying || st == kLoopOverdub) {
                    loopState_[t].store(kLoopPaused);
                }
            } else if (cmd == 2) {
                if (loopLen_[t] > 0 && (st == kLoopPaused || st == kLoopPlaying || st == kLoopOverdub)) {
                    loopPos_[t] = 0;
                    loopState_[t].store(kLoopPlaying);
                }
            }
        }
    }

    void beginOverdubSession(int t) {
        if (undoBuf_[t].empty() || loopLen_[t] <= 0) return;
        snapBase_[t] = loopPos_[t];
        snapDone_[t] = 0;
        snapActive_[t] = true;
        restoreActive_[t] = false;
        undoValid_[t] = true;
        lastOverdubTrack_.store(t);
    }

    // Audio thread, once per callback: advance the chunked snapshot (runs ahead
    // of the overdub writer, saving pre-session samples) and the chunked undo
    // restore (copies the saved samples back while playback continues).
    void loopMaintain(int t) {
        int len = loopLen_[t];
        if (len <= 0 || undoBuf_[t].empty()) return;
        float *buf = loopBuf_[t].data();
        float *ub = undoBuf_[t].data();
        if (snapActive_[t]) {
            int n = std::min(kLoopCopyChunk, len - snapDone_[t]);
            for (int i = 0; i < n; ++i) {
                int p = (snapBase_[t] + snapDone_[t] + i) % len;
                ub[p * 2] = buf[p * 2];
                ub[p * 2 + 1] = buf[p * 2 + 1];
            }
            snapDone_[t] += n;
            if (snapDone_[t] >= len) snapActive_[t] = false;
        }
        if (restoreActive_[t]) {
            int n = std::min(kLoopCopyChunk, restoreExtent_[t] - restoreDone_[t]);
            for (int i = 0; i < n; ++i) {
                int p = (snapBase_[t] + restoreDone_[t] + i) % len;
                buf[p * 2] = ub[p * 2];
                buf[p * 2 + 1] = ub[p * 2 + 1];
            }
            restoreDone_[t] += n;
            if (restoreDone_[t] >= restoreExtent_[t]) restoreActive_[t] = false;
        }
    }

    // Audio thread: record/overdub src into track t and mix its playback into L/R.
    void loopTick(int t, float srcL, float srcR, float &outL, float &outR) {
        int st = loopState_[t].load(std::memory_order_relaxed);
        if (st == kLoopEmpty || st == kLoopPaused) return;
        float *buf = loopBuf_[t].data();
        int maxFrames = static_cast<int>(loopBuf_[t].size() / 2);
        if (maxFrames <= 0) return;
        if (st == kLoopArmed) {
            float gate = t == 0 ? kLoopVocalGate : kLoopInstGate;
            float lvl = std::max(std::fabs(srcL), std::fabs(srcR));
            if (lvl < gate) return;              // still waiting for real audio
            st = kLoopRecording;                 // first loud sample starts the take
            loopState_[t].store(kLoopRecording);
        }
        int pos = loopPos_[t];
        if (st == kLoopRecording) {
            buf[pos * 2] = srcL;
            buf[pos * 2 + 1] = srcR;
            int limit = maxFrames;
            int bars = loopRecBars_[t].load(std::memory_order_relaxed);
            if (t > 0 && bars > 0) {
                // Hands-free take (loops 1-3): close after N bars at the
                // metronome tempo/signature and go straight to Play — no tap,
                // no accidental overdub while both hands are on the strings.
                int bpm = metronomeBpm_.load(std::memory_order_relaxed);
                int beats = metroBeats_.load(std::memory_order_relaxed);
                long f = static_cast<long>(sampleRate_) * 60L * beats * bars
                        / (bpm < 30 ? 30 : bpm);
                if (f > 0 && f < limit) limit = static_cast<int>(f);
            }
            if (++pos >= limit) {          // hit the cap or bar limit: close it
                loopLen_[t] = limit; pos = 0;
                loopState_[t].store(kLoopPlaying);
                loopLenShared_[t].store(limit);
            }
        } else {                            // playing / overdub
            int len = loopLen_[t];
            if (len <= 0) return;
            if (pos >= len) pos = 0;
            if (!loopMuted_[t].load(std::memory_order_relaxed)) {
                // Slight trim: several loops + overdub stacks sum up fast.
                outL += buf[pos * 2] * 0.90f;
                outR += buf[pos * 2 + 1] * 0.90f;
            }
            if (st == kLoopOverdub) {
                buf[pos * 2] = clampFloat(buf[pos * 2] + srcL, -1.2f, 1.2f);
                buf[pos * 2 + 1] = clampFloat(buf[pos * 2 + 1] + srcR, -1.2f, 1.2f);
            }
            if (++pos >= len) pos = 0;
        }
        loopPos_[t] = pos;
    }

public:
    void loopCommand(int track, int cmd) {
        if (track >= 0 && track < kNumLoops) loopCmd_[track].store(cmd);
    }
    // Low nibble = LoopState, bit 4 = muted.
    int loopState(int track) const {
        if (track < 0 || track >= kNumLoops) return 0;
        return loopState_[track].load() | (loopMuted_[track].load() ? 16 : 0);
    }

    void loopGlobal(int cmd) { loopGlobalCmd_.store(cmd); }
    void setLoopMonitor(bool on) { loopMonitor_.store(on); }
    float loopPosNorm(int track) const {
        return (track >= 0 && track < kNumLoops) ? loopPosNorm_[track].load() : 0.0f;
    }
    float loopLenMs(int track) const {
        if (track < 0 || track >= kNumLoops) return 0.0f;
        return static_cast<float>(loopLenShared_[track].load()) * 1000.0f
                / static_cast<float>(std::max(sampleRate_, 1));
    }
    int loopLastOverdub() const { return lastOverdubTrack_.load(); }

    // Downsampled peak envelope of a loop for the ring waveform display.
    // UI thread; racing the audio thread is benign for a meter.
    void loopWave(int track, float *out, int bins) {
        for (int i = 0; i < bins; ++i) out[i] = 0.0f;
        if (track < 0 || track >= kNumLoops || loopBuf_[track].empty()) return;
        int st = loopState_[track].load();
        int extent = (st == kLoopRecording) ? loopPos_[track] : loopLenShared_[track].load();
        if (extent <= 0) return;
        const float *buf = loopBuf_[track].data();
        for (int b = 0; b < bins; ++b) {
            long start = static_cast<long>(b) * extent / bins;
            long end = static_cast<long>(b + 1) * extent / bins;
            long step = std::max(1L, (end - start) / 24);
            float pk = 0.0f;
            for (long f = start; f < end; f += step) {
                float a = std::max(std::fabs(buf[f * 2]), std::fabs(buf[f * 2 + 1]));
                if (a > pk) pk = a;
            }
            out[b] = pk;
        }
    }
    void setHarmonizer(bool on) { harmOn_.store(on); }
    bool harmonizerOn() const { return harmOn_.load(); }

    // Ignore the capture stream entirely (kills mixer feedback loops).
    void setInputMute(bool mute) { inputMute_.store(mute); }

    void setAutotune(bool on) { harmTune_.store(on); }

    void setVocalReverb(float level) { vocalRev_.store(clampFloat(level, 0.0f, 1.0f)); }

    void setPianoGlide(bool on) { glideOn_.store(on); }

    void setPianoGlideMono(bool on) { glideMono_.store(on); }

    void setPianoGlideRate(float semisPerSec) {
        glideRate_.store(clampFloat(semisPerSec, 5.0f, 400.0f));
    }

    void setLoopKeysGlide(bool on) { loopGlideOn_.store(on); }

    void setLoopKeysGlideMono(bool on) { loopGlideMono_.store(on); }

    void setGkPoly(bool on) { gkPoly_.store(on); }

    void setGkTranspose(int semis) { gkTranspose_.store(std::max(-24, std::min(24, semis))); }

    void setGkBendFollow(bool on) { gkBendFollow_.store(on); }

    uint64_t gkNotesMask() const { return gkPolyMask_.load(std::memory_order_relaxed); }

    // Which capture device feeds loops 1-3 (kNoDevice = no instrument input).
    // Takes effect on the next start(); the UI restarts the looper on change.
    void setLoopInstDevice(int deviceId) { loopInstDevice_.store(deviceId); }

    // Per track: 0 = record until tapped; N = auto-close after N bars.
    void setLoopRecBars(int track, int bars) {
        if (track >= 0 && track < kNumLoops) {
            loopRecBars_[track].store(bars < 0 ? 0 : (bars > 16 ? 16 : bars));
        }
    }

    void setHarmonizerParams(float semi1, float semi2, bool choir,
                             float level, int tone, bool rev) {
        harmSemi1_.store(semi1);
        harmSemi2_.store(semi2);
        harmChoir_.store(choir);
        harmLevel_.store(clampFloat(level, 0.0f, 1.5f));
        harmTone_.store(tone);
        harmRev_.store(rev);
    }

    // Export a finished loop as a 16-bit stereo WAV (UI thread; racing playback
    // reads is benign for a bounce).
    bool loopSaveWav(int track, const std::string &path) {
        if (track < 0 || track >= kNumLoops) return false;
        int len = loopLenShared_[track].load();
        if (len <= 0 || loopBuf_[track].empty()) return false;
        FILE *fp = std::fopen(path.c_str(), "wb");
        if (fp == nullptr) return false;
        uint32_t dataBytes = static_cast<uint32_t>(len) * 2 * 2;
        writeWavHeader(fp, dataBytes);
        const float *buf = loopBuf_[track].data();
        std::vector<int16_t> block(4096);
        int total = len * 2, done = 0;
        while (done < total) {
            int n = std::min(4096, total - done);
            for (int i = 0; i < n; ++i) {
                float v = clampFloat(buf[done + i], -1.0f, 1.0f);
                block[i] = static_cast<int16_t>(v * 32767.0f);
            }
            std::fwrite(block.data(), 2, n, fp);
            done += n;
        }
        std::fclose(fp);
        return true;
    }

private:
    // Per-piece level trims (host-measured, per kit slot, notes 35..81):
    // each kit's pieces are pulled toward the kit's median piece level so no
    // drum sticks out or disappears. Applied as a velocity scale at note-on
    // (TSF velocity->gain is linear, so this is an exact per-piece gain).
    static constexpr int kTrimLoNote = 35, kTrimHiNote = 81;
    static constexpr float kKitNoteTrim[12][47] = {
    {0.98f, 0.98f, 1.02f, 0.98f, 0.99f, 0.98f, 0.99f, 1.00f, 0.99f, 2.50f, 1.00f, 1.24f, 0.99f, 0.99f, 1.02f, 1.00f, 1.03f, 1.05f, 1.19f, 2.50f, 1.06f, 1.67f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f},
    {0.98f, 1.36f, 1.07f, 0.99f, 1.00f, 1.02f, 1.00f, 1.17f, 1.00f, 1.00f, 1.00f, 1.11f, 1.00f, 1.00f, 1.36f, 0.99f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 0.99f, 0.99f, 0.98f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.25f, 1.00f, 1.00f, 1.00f, 1.00f, 1.01f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f},
    {1.02f, 1.00f, 1.00f, 0.97f, 0.99f, 0.98f, 1.00f, 0.89f, 1.01f, 0.98f, 1.01f, 0.98f, 1.02f, 1.02f, 0.95f, 1.00f, 1.01f, 1.00f, 1.00f, 1.00f, 1.00f, 1.46f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.60f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f},
    {1.00f, 1.08f, 1.00f, 0.52f, 1.00f, 1.00f, 1.00f, 0.85f, 0.97f, 1.00f, 1.00f, 0.81f, 1.00f, 1.00f, 1.14f, 0.99f, 1.04f, 1.17f, 1.01f, 1.00f, 1.00f, 1.15f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f},
    {0.56f, 0.56f, 0.85f, 0.59f, 0.58f, 0.60f, 1.04f, 2.50f, 1.00f, 2.50f, 0.88f, 2.50f, 0.88f, 0.93f, 2.50f, 0.92f, 2.50f, 2.50f, 2.21f, 2.50f, 1.00f, 2.15f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f},
    {0.94f, 0.94f, 0.88f, 0.89f, 1.05f, 1.31f, 0.94f, 1.24f, 0.95f, 1.14f, 0.96f, 1.24f, 0.96f, 1.01f, 1.04f, 0.85f, 1.07f, 1.04f, 1.00f, 1.17f, 1.00f, 1.02f, 1.00f, 1.09f, 1.00f, 0.89f, 0.86f, 0.90f, 0.83f, 0.86f, 1.12f, 1.13f, 0.97f, 1.33f, 0.92f, 1.22f, 1.25f, 0.91f, 0.83f, 0.96f, 0.96f, 1.74f, 1.74f, 1.19f, 0.94f, 1.33f, 0.94f},
    {0.69f, 0.69f, 1.33f, 0.80f, 1.46f, 0.80f, 1.05f, 2.50f, 1.36f, 2.40f, 0.82f, 2.50f, 0.93f, 0.73f, 0.97f, 0.72f, 0.80f, 1.30f, 1.04f, 1.36f, 1.59f, 0.90f, 0.95f, 1.19f, 0.82f, 1.35f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f},
    {0.96f, 0.97f, 0.96f, 0.97f, 1.03f, 0.97f, 0.78f, 2.50f, 0.96f, 2.50f, 0.86f, 2.50f, 0.97f, 0.85f, 1.89f, 0.95f, 2.44f, 1.74f, 1.96f, 1.32f, 1.88f, 0.89f, 1.94f, 1.00f, 2.18f, 0.96f, 2.50f, 1.03f, 1.00f, 0.95f, 0.96f, 1.03f, 0.96f, 0.97f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f},
    {0.95f, 0.95f, 0.99f, 1.01f, 0.90f, 0.95f, 0.99f, 1.08f, 1.01f, 1.25f, 0.89f, 1.11f, 0.84f, 0.76f, 1.05f, 0.76f, 1.34f, 1.11f, 1.62f, 1.13f, 1.00f, 0.90f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 0.91f, 1.21f, 1.21f, 1.00f, 1.00f, 1.00f, 1.00f, 1.34f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f},
    {0.91f, 0.91f, 1.00f, 0.91f, 1.07f, 1.01f, 0.99f, 1.35f, 1.00f, 1.72f, 0.99f, 1.43f, 0.99f, 1.00f, 1.06f, 1.01f, 0.94f, 1.05f, 1.00f, 1.07f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 0.99f, 0.99f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.75f, 1.08f, 1.00f, 1.10f, 1.13f, 1.00f, 1.00f, 2.50f, 1.22f},
    {1.04f, 0.94f, 1.06f, 0.95f, 0.99f, 1.12f, 1.03f, 0.94f, 0.85f, 1.01f, 0.90f, 0.96f, 1.16f, 1.16f, 0.97f, 0.87f, 1.21f, 1.18f, 1.06f, 0.90f, 1.41f, 1.17f, 0.97f, 1.15f, 1.13f, 1.63f, 0.88f, 0.98f, 0.93f, 0.92f, 0.88f, 0.97f, 2.03f, 1.15f, 1.23f, 1.08f, 1.82f, 1.07f, 1.02f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f},
    {1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f, 1.00f},
    };

    static float kitTrim(int slot, int note) {
        if (slot < 0 || slot >= 12 || note < kTrimLoNote || note > kTrimHiNote) {
            return 1.0f;
        }
        return kKitNoteTrim[slot][note - kTrimLoNote];
    }

    // Cymbals sit low on GM kits — lift them so they cut through. The three
    // group gains are user-adjustable (long-press the pad); defaults keep the
    // previous hardcoded lift (ride 1.40, crash 1.30, hat 1.15).
    float cymbalGain(int note) {
        switch (note) {
            case 51: case 59: return cymGainRide_.load(std::memory_order_relaxed);
            case 49: case 52: case 53: case 55: case 57:
                return cymGainCrash_.load(std::memory_order_relaxed);
            case 42: case 44: case 46: return cymGainHat_.load(std::memory_order_relaxed);
            default: return 1.0f;
        }
    }

    static void hatChoke(tsf *snd, int channel, int note) {
        if (note == 42 || note == 44 || note == 46) {
            if (note != 42) tsf_channel_note_off(snd, channel, 42);
            if (note != 44) tsf_channel_note_off(snd, channel, 44);
            if (note != 46) tsf_channel_note_off(snd, channel, 46);
        }
    }

    // Drums: trigger percussion notes on the GM drum channel (9). One-shot
    // samples ring out, so note-offs are ignored. Loop Mix also routes its
    // melodic key events through here (keys != nullptr): GM channel 0, with
    // real note-offs so organs/strings/choirs stop when the finger lifts.
    // altKeys = font whose channel 1 plays the keys' layer / dual sound 2.
    void drainDrumEvents(tsf *snd, int channel, int remap, int trimSlot, int presetIndex,
                         tsf *keys = nullptr, tsf *altKeys = nullptr) {
        int split = altKeys != nullptr ? loopKeySplit_.load(std::memory_order_relaxed) : -1;
        // Cymbal choke: an edge/light touch on the pad kills the ringing
        // cymbals (crash 1/2, china, splash, rides) so they stop dead, like
        // grabbing the cymbal by hand.
        if (snd != nullptr && cymbalChoke_.exchange(false, std::memory_order_relaxed)) {
            chokeCymbalVoices(snd, channel);
        }
        int head = eventHead_.load(std::memory_order_acquire);
        int tail = eventTail_.load(std::memory_order_relaxed);
        while (tail != head) {
            int *e = eventBuffer_[tail];
            if (e[0] == kEvNoteOn && snd != nullptr) {
                int note = e[1] & 0x7F;
                switch (remap) {
                    case 1: note = remap808(note); break;
                    case 2: note = remapReggae(note); break;
                    case 3: note = remapMambo(note); break;
                    case 4: note = remapBeatbox(note); break;
                    case 5: note = remapCongas(note); break;
                    case 6: note = remapOneDrop(note); break;
                    default: break;
                }
                if (presetIndex >= 0) {
                    note = kitSpecificDrumNote(trimSlot, note);
                    note = playableDrumNote(snd, presetIndex, note);
                }
                hatChoke(snd, channel, note);
                float vel = static_cast<float>(e[2]) / 127.0f
                        * kitTrim(trimSlot, note) * cymbalGain(note);
                tsf_channel_note_on(snd, channel, note, vel > 1.0f ? 1.0f : vel);
            } else if (e[0] == kEvKeyOn && keys != nullptr) {
                // Same 0.8 velocity cap as the piano path: some fonts have no
                // sample zone at full velocity and would go silent.
                int note = e[1] & 0x7F;
                float vel = static_cast<float>(e[2]) / 127.0f;
                if (vel > 0.8f) vel = 0.8f;
                bool lg = loopGlideOn_.load(std::memory_order_relaxed);
                bool lgMono = lg && loopGlideMono_.load(std::memory_order_relaxed);
                if (!lg && (lkStackN_ != 0 || lk2StackN_ != 0)) {   // mode left mid-hold
                    lkStackN_ = 0;
                    lkAnchor_ = -1;
                    lk2StackN_ = 0;
                    lk2Anchor_ = -1;
                }
                if (split >= 0) {
                    // Dual split/boards: each side has its own sound and its
                    // own glide stream, so slide works on both keyboards.
                    bool up = note >= split;
                    tsf *f = up ? altKeys : keys;
                    int ch = up ? 1 : 0;
                    bool legato = lg && glideOnHit(
                            up ? lk2Stack_ : lkStack_,
                            up ? lk2StackN_ : lkStackN_,
                            up ? lk2Anchor_ : lkAnchor_,
                            up ? lk2OffTarget_ : lkOffTarget_,
                            up ? lk2OffCur_ : lkOffCur_, f, ch, note);
                    if (!legato) {
                        // Mono slide: silence the last voice on a detached
                        // press instead of stacking its tail.
                        if (lgMono) {
                            tsf_channel_note_off_all(f, ch);
                        }
                        tsf_channel_note_on(f, ch, note, vel);
                    }
                } else {
                    bool legato = lg && glideOnHit(lkStack_, lkStackN_, lkAnchor_,
                            lkOffTarget_, lkOffCur_, keys, 0, note);
                    if (lg && altKeys != nullptr) {
                        glideOnHit(lk2Stack_, lk2StackN_, lk2Anchor_,
                                lk2OffTarget_, lk2OffCur_, altKeys, 1, note);
                    }
                    if (!legato) {
                        if (lgMono) {
                            tsf_channel_note_off_all(keys, 0);
                            if (altKeys != nullptr) tsf_channel_note_off_all(altKeys, 1);
                        }
                        tsf_channel_note_on(keys, 0, note, vel);
                        if (altKeys != nullptr) {
                            tsf_channel_note_on(altKeys, 1, note, vel * 0.9f);
                        }
                    }
                }
            } else if (e[0] == kEvKeyOff && keys != nullptr) {
                int note = e[1] & 0x7F;
                bool lg = loopGlideOn_.load(std::memory_order_relaxed);
                if (lg) {
                    bool up = split >= 0 && note >= split;
                    int rel = up
                            ? glideOffHit(lk2Stack_, lk2StackN_, lk2Anchor_,
                                    lk2OffTarget_, note)
                            : glideOffHit(lkStack_, lkStackN_, lkAnchor_,
                                    lkOffTarget_, note);
                    if (!up && split < 0 && altKeys != nullptr) {
                        glideOffHit(lk2Stack_, lk2StackN_, lk2Anchor_, lk2OffTarget_, note);
                    }
                    if (rel >= 0) {
                        if (up) {
                            tsf_channel_note_off(altKeys, 1, rel);
                        } else {
                            tsf_channel_note_off(keys, 0, rel);
                            if (split < 0 && altKeys != nullptr) {
                                tsf_channel_note_off(altKeys, 1, rel);
                            }
                        }
                    }
                } else {
                    tsf_channel_note_off(keys, 0, note);
                    if (altKeys != nullptr) {
                        tsf_channel_note_off(altKeys, 1, note);
                    }
                }
            } else if (e[0] == kEvKey2On && keys != nullptr) {
                // Separate dual mode: the upper keyboard plays sound 2 directly,
                // with its own glide stream so slide works there too.
                int note = e[1] & 0x7F;
                float vel = static_cast<float>(e[2]) / 127.0f;
                if (vel > 0.8f) vel = 0.8f;
                tsf *f = altKeys != nullptr ? altKeys : keys;
                int ch = altKeys != nullptr ? 1 : 0;
                bool lg = loopGlideOn_.load(std::memory_order_relaxed) && altKeys != nullptr;
                bool legato = lg && glideOnHit(lk2Stack_, lk2StackN_, lk2Anchor_,
                        lk2OffTarget_, lk2OffCur_, f, ch, note);
                if (!legato) {
                    if (lg && loopGlideMono_.load(std::memory_order_relaxed)) {
                        tsf_channel_note_off_all(f, ch);
                    }
                    tsf_channel_note_on(f, ch, note, vel);
                }
            } else if (e[0] == kEvKey2Off && keys != nullptr) {
                int note = e[1] & 0x7F;
                if (loopGlideOn_.load(std::memory_order_relaxed) && altKeys != nullptr) {
                    int rel = glideOffHit(lk2Stack_, lk2StackN_, lk2Anchor_,
                            lk2OffTarget_, note);
                    if (rel >= 0) {
                        tsf_channel_note_off(altKeys, 1, rel);
                    }
                } else if (altKeys != nullptr) {
                    tsf_channel_note_off(altKeys, 1, note);
                } else {
                    tsf_channel_note_off(keys, 0, note);
                }
            } else if (e[0] == kEvNoteOff && keys != nullptr) {
                // A piano note-off that crossed a screen switch would be
                // dropped here — honor it on the key channels so looped
                // sounds (strings, organs) can never ring forever.
                int note = e[1] & 0x7F;
                tsf_channel_note_off(keys, 0, note);
                if (altKeys != nullptr) {
                    tsf_channel_note_off(altKeys, 1, note);
                }
            } else if (e[0] == kEvAllOff && keys != nullptr) {
                // Panic: silence the key channels only — drums are one-shots.
                tsf_channel_note_off_all(keys, 0);
                tsf_channel_note_off_all(keys, 1);
                if (altKeys != nullptr && altKeys != keys) {
                    tsf_channel_note_off_all(altKeys, 1);
                }
                lkStackN_ = 0;
                lkAnchor_ = -1;
                lkOffCur_ = 0.0f;
                lkOffTarget_ = 0.0f;
                lk2StackN_ = 0;
                lk2Anchor_ = -1;
                lk2OffCur_ = 0.0f;
                lk2OffTarget_ = 0.0f;
                tsf_channel_set_tuning(keys, 0, 0.0f);
                if (altKeys != nullptr) tsf_channel_set_tuning(altKeys, 1, 0.0f);
            }
            tail = (tail + 1) % kEventQueueSize;
        }
        eventTail_.store(tail, std::memory_order_release);
    }

    // Custom kit: route each note to its assigned source (HQ slot, metal slot, or GM kit).
    void drainCustomDrumEvents() {
        tsf *gm = sound_.load();
        // Cymbal choke on custom kits: note-off the cymbals across every source
        // (GM channel 9 + each drum font on channel 0) since pieces may differ.
        if (cymbalChoke_.exchange(false, std::memory_order_relaxed)) {
            chokeCymbalVoices(gm, 9);
            chokeCymbalVoices(gm, kSelectedKitChannel);
            for (int s = 0; s < kNumDrumFonts; ++s) {
                chokeCymbalVoices(drumFonts_[s].load(), 0);
                chokeCymbalVoices(drumFonts_[s].load(), kSelectedKitChannel);
            }
        }
        int head = eventHead_.load(std::memory_order_acquire);
        int tail = eventTail_.load(std::memory_order_relaxed);
        while (tail != head) {
            int *e = eventBuffer_[tail];
            if (e[0] == kEvNoteOn) {
                int note = e[1] & 0x7F;               // the pad's trigger note (key into the tables)
                int code = drumPieceSlot_[note].load();
                // A piece may play a DIFFERENT note than its trigger (sample-library
                // fonts put each sample on its own note), so two pieces on the same
                // trigger note can still source distinct sounds. -1 = play the trigger note.
                int src = drumPieceSrcNote_[note].load();
                int playNote = (src >= 0 && src < 128) ? src : note;
                float vel = static_cast<float>(e[2]) / 127.0f
                        * drumPieceGain_[note].load() * cymbalGain(note);
                if (vel > 1.0f) vel = 1.0f;
                float pan = drumPiecePan_[note].load();
                // Pan is per-channel: setting it before the hit places this piece (exact
                // when pieces use different fonts; shared-font pieces share the last pan).
                if (code >= kPieceSelectedBase) {
                    int raw = code - kPieceSelectedBase;
                    int kit = raw >= kMetalDriveBase ? raw - kMetalDriveBase : raw;
                    if (kit < kHqDrumBase) {
                        if (gm != nullptr) {
                            tsf_channel_set_presetnumber(
                                    gm, kSelectedKitChannel, kit, 1);
                            hatChoke(gm, kSelectedKitChannel, playNote);
                            tsf_channel_set_pan(gm, kSelectedKitChannel, pan);
                            tsf_channel_note_on(
                                    gm, kSelectedKitChannel, playNote, vel);
                        }
                    } else {
                        int slot = (kit - kHqDrumBase) / 100;
                        int preset = (kit - kHqDrumBase) % 100;
                        if (slot >= 0 && slot < kNumDrumFonts) {
                            tsf *df = drumFonts_[slot].load();
                            if (df != nullptr) {
                                int count = tsf_get_presetcount(df);
                                if (preset < 0 || preset >= count) preset = 0;
                                tsf_channel_set_presetindex(
                                        df, kSelectedKitChannel, preset);
                                // Java has already applied the selected kit's
                                // exact Pad Mode remap to Default-piece notes.
                                int n2 = kitSpecificDrumNote(slot, playNote);
                                n2 = playableDrumNote(df, preset, n2);
                                hatChoke(df, kSelectedKitChannel, n2);
                                tsf_channel_set_pan(
                                        df, kSelectedKitChannel, pan);
                                float tv = vel * kitTrim(slot, n2);
                                tsf_channel_note_on(df, kSelectedKitChannel, n2,
                                        tv > 1.0f ? 1.0f : tv);
                            }
                        }
                    }
                } else if (code >= kPieceGmBase && code < kPieceGmBase + kGmKitCount) {
                    if (gm != nullptr) {
                        int ch = kGmDrumChannel0 + (code - kPieceGmBase);
                        hatChoke(gm, ch, playNote);
                        tsf_channel_set_pan(gm, ch, pan);
                        tsf_channel_note_on(gm, ch, playNote, vel);
                    }
                } else {
                    int slot = (code >= kPieceDriveBase) ? code - kPieceDriveBase : code;
                    if (slot >= 0 && slot < kNumDrumFonts) {
                        tsf *df = drumFonts_[slot].load();
                        if (df != nullptr) {
                            int n2 = (slot == k808Slot) ? remap808(playNote) : playNote;
                            n2 = kitSpecificDrumNote(slot, n2);
                            int preset = fullKitPreset(slot, df);
                            // A lazy-loaded source may arrive after its custom
                            // routing mask was applied, so assert its full kit.
                            tsf_channel_set_presetindex(df, 0, preset);
                            n2 = playableDrumNote(df, preset, n2);
                            hatChoke(df, 0, n2);
                            tsf_channel_set_pan(df, 0, pan);
                            float tv = vel * kitTrim(slot, n2);
                            tsf_channel_note_on(df, 0, n2, tv > 1.0f ? 1.0f : tv);
                        }
                    }
                }
            }
            tail = (tail + 1) % kEventQueueSize;
        }
        eventTail_.store(tail, std::memory_order_release);
    }

    // Poly-mode notes carry the transpose they were struck with, so a
    // transpose change can release them on the right keys.
    int gkPolyNote(int i) const {
        return std::max(0, std::min(127, 36 + i + gkSoundTrans_));
    }

    // Continuous pitch: steer channel 0's wheel to `dev` semitones (range 4).
    // snap = jump straight there (new anchor); otherwise glide a little so
    // 20ms tracker steps don't zipper.
    void gkWheel(tsf *snd, float dev, bool snap) {
        dev = clampFloat(dev, -3.9f, 3.9f);
        gkBendCur_ = snap ? dev : gkBendCur_ + (dev - gkBendCur_) * 0.5f;
        int wheel = 8192 + static_cast<int>(std::lround(gkBendCur_ / 4.0f * 8192.0f));
        tsf_channel_set_pitchwheel(snd, 0, std::max(0, std::min(16383, wheel)));
    }

    // Guitar→Keys note logic: envelope gate + velocity from pick strength.
    // Mono mode: semitone hysteresis (a new pitch must be seen twice before
    // switching), pick-attack retriggers, and optional bend-follow (the
    // wheel rides the guitar's exact pitch — bends, vibrato, slides).
    // Poly mode: plays the analyzer's voted note set (up to six strings).
    void gkTrack(tsf *snd, float peak, int32_t numFrames) {
        float decay = std::exp(-static_cast<float>(numFrames)
                / (0.050f * static_cast<float>(sampleRate_ > 0 ? sampleRate_ : 48000)));
        gkEnv_ = std::max(peak, gkEnv_ * decay);
        gkSlow_ += (gkEnv_ - gkSlow_) * 0.06f;
        if (gkOnsetHold_ > 0) gkOnsetHold_--;

        // Sudden mute: the player blocked the strings (palm/fret-hand damp).
        // The signal collapses to a small fraction of what it just was far
        // faster than any natural decay, so release everything right away
        // instead of waiting ~200 ms for the envelope gate and the FFT
        // window to notice. Applies to mono, poly and bass mode alike.
        int sr = sampleRate_ > 0 ? sampleRate_ : 48000;
        float ref = gkRef_;
        float refDecay = std::exp(-static_cast<float>(numFrames)
                / (0.25f * static_cast<float>(sr)));
        gkRef_ = std::max(peak, gkRef_ * refDecay);
        // Ambient noise floor: drops instantly in any quiet moment, creeps up
        // only slowly (~8 s) under sustained sound, capped so playing never
        // raises it far. The note gates ride above the floor, so steady hiss
        // or charger whine through the raw mic can never fire a note on its
        // own — a real pluck still jumps well past it.
        if (peak < gkFloor_) {
            gkFloor_ = peak;
        } else {
            gkFloor_ += (peak - gkFloor_)
                    * (static_cast<float>(numFrames) / (8.0f * static_cast<float>(sr)));
        }
        gkFloor_ = std::min(gkFloor_, 0.04f);
        gkGateOn_ = std::max(0.012f, gkFloor_ * 3.0f);
        gkGateOff_ = std::max(0.008f, gkFloor_ * 2.2f);
        if (ref > 0.035f && peak < ref * 0.07f) {
            gkQuietFrames_ += numFrames;
        } else {
            gkQuietFrames_ = 0;
        }
        if (gkQuietFrames_ >= sr / 45) {   // ~22 ms of collapse = a real mute
            gkQuietFrames_ = 0;
            gkRef_ = peak;
            gkEnv_ = peak;    // drop the envelope's decay memory with it
            if (gkNote_ >= 0) {
                tsf_channel_note_off(snd, 0, gkNote_);
                gkNote_ = -1;
            }
            gkCand_ = -1;
            if (gkSounding_ != 0) {
                for (int i = 0; i < 64; ++i) {
                    if (gkSounding_ & (1ULL << i)) tsf_channel_note_off(snd, 0, gkPolyNote(i));
                }
                gkSounding_ = 0;
            }
            // Tell the pitch thread to forget its votes: the FFT ring still
            // holds the dead chord and must not re-fire it.
            gkMuteFlush_.store(true, std::memory_order_relaxed);
            return;
        }

        // Watchdog: when the tracker owns nothing (no mono note, no poly
        // notes, empty analyzer mask), no voice may keep SUSTAINING on the
        // GK channel — a leaked bookkeeping path would ring forever. This
        // only triggers releases; natural decay tails are unaffected.
        if (gkNote_ < 0 && gkSounding_ == 0
                && gkPolyMask_.load(std::memory_order_relaxed) == 0) {
            if (++gkIdleTicks_ >= 3) {
                gkIdleTicks_ = 0;
                tsf_channel_note_off_all(snd, 0);
            }
        } else {
            gkIdleTicks_ = 0;
        }

        if (gkPoly_.load(std::memory_order_relaxed)) {
            if (gkNote_ >= 0) {   // left over from mono mode
                tsf_channel_note_off(snd, 0, gkNote_);
                gkNote_ = -1;
                gkCand_ = -1;
            }
            gkTrackPoly(snd);
            return;
        }
        if (gkSounding_ != 0) {   // left over from poly mode
            for (int i = 0; i < 64; ++i) {
                if (gkSounding_ & (1ULL << i)) tsf_channel_note_off(snd, 0, gkPolyNote(i));
            }
            gkSounding_ = 0;
        }
        float hz = smoothedPitchHz_.load();
        // Note-switch confirmations must count DISTINCT detector readings.
        // Audio callbacks (~5 ms) outpace the pitch thread (~15 ms), so two
        // consecutive callbacks see the same reading — "confirmed twice"
        // was trivially true and one bad reading double-fired the key.
        int seq = pitchSeq_.load(std::memory_order_relaxed);
        bool fresh = seq != gkLastSeq_;
        gkLastSeq_ = seq;
        int trans = gkTranspose_.load(std::memory_order_relaxed);
        bool follow = gkBendFollow_.load(std::memory_order_relaxed);
        float exact = -1000.0f;
        int note = -1;
        if (hz >= 35.0f) {
            exact = 69.0f + 12.0f * std::log2(hz / 440.0f) + static_cast<float>(trans);
            note = static_cast<int>(std::lround(exact));
            note = std::max(0, std::min(127, note));
        }
        if (gkEnv_ < gkGateOn_ || note < 0) {
            // String muted / decayed away: release (with a little hysteresis).
            if (gkNote_ >= 0 && (gkEnv_ < gkGateOff_ || note < 0)) {
                tsf_channel_note_off(snd, 0, gkNote_);
                gkNote_ = -1;
            }
            gkCand_ = -1;
            return;
        }
        float vel = clampFloat(0.30f + gkEnv_ * 3.5f, 0.30f, 0.8f);
        if (follow && snd != gkBendSnd_) {
            // Wheel range 4 semitones: full-step bends with headroom.
            tsf_channel_set_pitchrange(snd, 0, 4.0f);
            gkBendSnd_ = snd;
        }
        if (!follow && gkBendSnd_ != nullptr) {
            tsf_channel_set_pitchwheel(gkBendSnd_, 0, 8192);
            gkBendCur_ = 0.0f;
            gkBendSnd_ = nullptr;
        }
        if (gkNote_ < 0) {
            // A real pluck (amplitude jump) fires instantly. A pitch that
            // appears WITHOUT a transient — bleed, ambience, detector drift —
            // must read the same note on two DISTINCT detector readings first:
            // that's what "randomly spouted keys" were. Counting callbacks
            // (which outpace readings) let one blip through — count readings.
            bool pluck = gkEnv_ > gkSlow_ * 1.6f;
            if (!pluck) {
                if (!fresh) return;             // wait for a new reading
                if (note != gkCand_) {
                    gkCand_ = note;
                    return;
                }
            }
            if (follow) gkWheel(snd, exact - static_cast<float>(note), true);
            tsf_channel_note_on(snd, 0, note, vel);
            gkNote_ = note;
            gkCand_ = -1;
            gkOnsetHold_ = 16;
            return;
        }
        if (follow) {
            // Ride the exact pitch with the wheel: bends, vibrato and slides
            // stay continuous like on the real instrument; only a genuine
            // jump (beyond a bend's reach) re-anchors onto a new note.
            float dev = exact - static_cast<float>(gkNote_);
            // 3.0x: the raw (Unprocessed) mic keeps full dynamics, so vibrato
            // and finger noise reach the old 2.4x bar and doubled the key.
            // A genuine re-pick is a far bigger jump and still passes.
            bool spike = gkEnv_ > gkSlow_ * 3.0f && gkOnsetHold_ == 0;
            if (std::fabs(dev) <= 2.6f) {
                gkCand_ = -1;
                if (spike) {   // re-picked (maybe while bent): re-anchor
                    tsf_channel_note_off(snd, 0, gkNote_);
                    gkNote_ = note;
                    gkWheel(snd, exact - static_cast<float>(note), true);
                    tsf_channel_note_on(snd, 0, note, vel);
                    gkOnsetHold_ = 16;
                } else {
                    gkWheel(snd, dev, false);
                }
            } else if (spike || (fresh && note == gkCand_)) {
                // A re-pluck (spike) jumps straight to the new note; a driftless
                // wander still needs two distinct readings. This is what lets a
                // re-picked earlier note be heard again while another still rings.
                tsf_channel_note_off(snd, 0, gkNote_);
                gkNote_ = note;
                gkWheel(snd, exact - static_cast<float>(note), true);
                tsf_channel_note_on(snd, 0, note, vel);
                gkCand_ = -1;
                gkOnsetHold_ = 16;
            } else if (fresh) {
                gkCand_ = note;
            }
            return;
        }
        if (note != gkNote_) {
            // A re-pluck (amplitude transient) switches instantly — a note the
            // player picks again is heard even while an earlier one still rings.
            // Without a transient, two distinct readings must agree (no double).
            bool repick = gkEnv_ > gkSlow_ * 3.0f && gkOnsetHold_ == 0;
            if (repick || (fresh && note == gkCand_)) {
                tsf_channel_note_off(snd, 0, gkNote_);
                tsf_channel_note_on(snd, 0, note, vel);
                gkNote_ = note;
                gkCand_ = -1;
                gkOnsetHold_ = 16;
            } else if (fresh) {
                gkCand_ = note;
            }
        } else {
            gkCand_ = -1;
            // Same note re-picked: envelope jumps well above its running average.
            if (gkEnv_ > gkSlow_ * 3.0f && gkOnsetHold_ == 0) {
                tsf_channel_note_off(snd, 0, gkNote_);
                tsf_channel_note_on(snd, 0, note, vel);
                gkOnsetHold_ = 16;
            }
        }
    }

    // Poly mode: diff the analyzer's voted mask against what is sounding.
    // A re-strum (envelope spike) retriggers the whole detected chord.
    void gkTrackPoly(tsf *snd) {
        int trans = gkTranspose_.load(std::memory_order_relaxed);
        if (trans != gkSoundTrans_) {   // transpose changed: restrike cleanly
            if (gkSounding_ != 0) {
                for (int i = 0; i < 64; ++i) {
                    if (gkSounding_ & (1ULL << i)) tsf_channel_note_off(snd, 0, gkPolyNote(i));
                }
                gkSounding_ = 0;
            }
            gkSoundTrans_ = trans;
        }
        if (gkEnv_ < gkGateOff_) {   // strings muted / decayed away
            if (gkSounding_ != 0) {
                for (int i = 0; i < 64; ++i) {
                    if (gkSounding_ & (1ULL << i)) tsf_channel_note_off(snd, 0, gkPolyNote(i));
                }
                gkSounding_ = 0;
            }
            return;
        }
        uint64_t mask = gkPolyMask_.load(std::memory_order_relaxed);
        uint64_t gone = gkSounding_ & ~mask;
        if (gone != 0) {
            for (int i = 0; i < 64; ++i) {
                if (gone & (1ULL << i)) tsf_channel_note_off(snd, 0, gkPolyNote(i));
            }
            gkSounding_ &= ~gone;
        }
        if (gkEnv_ < gkGateOn_) return;   // too quiet for new onsets
        bool strum = gkEnv_ > gkSlow_ * 3.0f && gkOnsetHold_ == 0;
        uint64_t fresh = strum ? mask : (mask & ~gkSounding_);
        if (fresh != 0) {
            float base = clampFloat(0.25f + gkEnv_ * 3.0f, 0.25f, 0.85f);
            for (int i = 0; i < 64; ++i) {
                uint64_t bit = 1ULL << i;
                if (!(fresh & bit)) continue;
                if (gkSounding_ & bit) tsf_channel_note_off(snd, 0, gkPolyNote(i));
                float rel = clampFloat(gkPolyVel_[i], 0.0f, 1.0f);
                tsf_channel_note_on(snd, 0, gkPolyNote(i),
                        clampFloat(base * (0.55f + 0.45f * rel), 0.20f, 0.90f));
            }
            gkSounding_ |= fresh;
            if (strum) gkOnsetHold_ = 16;
        }
    }

    // USB-audio piano: drive one monophonic SoundFont voice from detected pitch.
    void trackPitchNote(tsf *snd) {
        float hz = smoothedPitchHz_.load();
        int note = -1;
        if (hz >= 35.0f) {
            note = static_cast<int>(std::lround(69.0f + 12.0f * std::log2(hz / 440.0f)));
            note = std::max(0, std::min(127, note));
        }
        if (note != audioActiveNote_) {
            if (audioActiveNote_ >= 0) {
                tsf_channel_note_off(snd, 0, audioActiveNote_);
            }
            if (note >= 0) {
                tsf_channel_note_on(snd, 0, note, 0.8f);
            }
            audioActiveNote_ = note;
        }
    }

    // Audio-thread writer of the analysis tap. The pitch worker reads this ring
    // buffer concurrently; a torn read only nudges a pitch estimate, so the race
    // is benign and avoids any locking on the real-time thread.
    void pushPitchSample(float sample) {
        int index = pitchWriteIndex_.load(std::memory_order_relaxed);
        pitchBuffer_[index] = sample;
        pitchWriteIndex_.store((index + 1) % kGkPolyWindow, std::memory_order_relaxed);
        int filled = pitchFilled_.load(std::memory_order_relaxed);
        if (filled < kGkPolyWindow) {
            pitchFilled_.store(filled + 1, std::memory_order_relaxed);
        }
    }

    void startPitchThread() {
        stopPitchThread();
        pitchThreadRunning_.store(true);
        pitchThread_ = std::thread(&InstrumentalEngine::pitchThreadLoop, this);
    }

    void stopPitchThread() {
        pitchThreadRunning_.store(false);
        if (pitchThread_.joinable()) {
            pitchThread_.join();
        }
    }

    // Autocorrelation pitch detection is O(N * maxLag) and must never run on the
    // audio callback. This worker estimates pitch off-thread and publishes the
    // result through atomics consumed by the DSP.
    void pitchThreadLoop() {
        while (pitchThreadRunning_.load()) {
            // 15 ms cadence: one pitch reading sooner per note — measurable
            // response gain for guitar tracking at modest worker-thread cost.
            std::this_thread::sleep_for(std::chrono::milliseconds(15));
            if (!running_.load()) {
                continue;
            }

            int instrument = instrument_.load();
            int tone = tone_.load();
            bool needsPitch = instrument == kPiano || tone == kBassSynth || instrument == kTuner
                    || instrument == kGuitarKeys;
            if (!needsPitch) {
                smoothedPitchHz_.store(0.0f);
                pitchHz_.store(0.0f);
                gkPolyReset();
                continue;
            }
            if (gkMuteFlush_.exchange(false, std::memory_order_relaxed)) {
                // The audio thread saw a sudden string mute: clear the voting
                // state so the still-ringing analysis window can't re-fire
                // the chord that was just damped.
                gkPolyReset();
            }
            if (instrument == kGuitarKeys && gkPoly_.load(std::memory_order_relaxed)) {
                // Polyphonic path: full 170ms ring, FFT analysis + voting.
                if (pitchFilled_.load() < kGkPolyWindow) {
                    continue;
                }
                int wi = pitchWriteIndex_.load();
                for (int i = 0; i < kGkPolyWindow; ++i) {
                    analysisBuffer_[i] = pitchBuffer_[(wi + i) % kGkPolyWindow];
                }
                gkAnalyzePoly(analysisBuffer_.data(), kGkPolyWindow, sampleRate_);
                continue;
            }
            gkPolyReset();
            if (pitchFilled_.load() < kPitchWindowSize) {
                continue;
            }

            // Newest kPitchWindowSize samples of the (larger) ring.
            int writeIndex = pitchWriteIndex_.load();
            for (int i = 0; i < kPitchWindowSize; ++i) {
                int index = (writeIndex + (kGkPolyWindow - kPitchWindowSize) + i) % kGkPolyWindow;
                analysisBuffer_[i] = pitchBuffer_[index];
            }

            float detected = detectPitch(analysisBuffer_.data(), kPitchWindowSize, sampleRate_);
            // Median of the last five readings: a single-frame octave blip can
            // no longer yank the displayed pitch around.
            pitchHist_[pitchHistIdx_ % 5] = detected;
            pitchHistIdx_++;
            if (instrument == kGuitarKeys) {
                // Guitar→Keys wants speed over display stability: median of the
                // THREE newest readings, applied directly (the note logic's own
                // hysteresis handles jitter). The tuner keeps its slow path.
                float m3[3] = {
                        pitchHist_[(pitchHistIdx_ + 2) % 5],
                        pitchHist_[(pitchHistIdx_ + 3) % 5],
                        pitchHist_[(pitchHistIdx_ + 4) % 5]};
                std::sort(m3, m3 + 3);
                smoothedPitchHz_.store(m3[1]);
                pitchHz_.store(m3[1]);
                pitchSeq_.fetch_add(1, std::memory_order_relaxed);   // new reading
                continue;
            }
            float sortedHist[5];
            for (int i = 0; i < 5; ++i) sortedHist[i] = pitchHist_[i];
            std::sort(sortedHist, sortedHist + 5);
            detected = sortedHist[2];
            float current = smoothedPitchHz_.load();
            if (detected > 0.0f) {
                float updated = current <= 0.0f ? detected : current * 0.82f + detected * 0.18f;
                smoothedPitchHz_.store(updated);
                pitchHz_.store(updated);
            } else {
                float decayed = current * 0.94f;
                if (decayed < 35.0f) {
                    decayed = 0.0f;
                }
                smoothedPitchHz_.store(decayed);
                pitchHz_.store(decayed);
            }
        }
    }

    float detectPitch(const float *samples, int count, int sampleRate) {
        if (samples == nullptr || count < 512 || sampleRate <= 0) {
            return 0.0f;
        }

        float energy = 0.0f;
        for (int i = 0; i < count; ++i) {
            energy += samples[i] * samples[i];
        }
        float rms = std::sqrt(energy / static_cast<float>(count));
        if (rms < 0.006f) {
            return 0.0f;
        }

        int minLag = std::max(1, sampleRate / 1200);
        int maxLag = std::min(sampleRate / 38, count / 2);
        if (static_cast<int>(pitchScores_.size()) < maxLag + 2) {
            pitchScores_.resize(maxLag + 2, 0.0f);
        }
        float bestScore = 0.0f;
        int bestLag = 0;

        for (int lag = minLag; lag <= maxLag; ++lag) {
            float corr = 0.0f;
            float e0 = 0.0f;
            float e1 = 0.0f;
            int limit = count - lag;
            for (int i = 0; i < limit; ++i) {
                float a = samples[i];
                float b = samples[i + lag];
                corr += a * b;
                e0 += a * a;
                e1 += b * b;
            }
            float denom = std::sqrt(e0 * e1) + 0.000001f;
            float score = corr / denom;
            pitchScores_[lag] = score;
            if (score > bestScore) {
                bestScore = score;
                bestLag = lag;
            }
        }

        if (bestLag <= 0 || bestScore < 0.38f) {
            return 0.0f;
        }
        // The global max flips between the true period and its octave multiples
        // frame to frame (the "rolling" readout). Take the smallest local peak
        // that is nearly as strong as the best instead — it stays put.
        int chosen = bestLag;
        for (int lag = minLag + 1; lag < bestLag; ++lag) {
            if (pitchScores_[lag] >= 0.90f * bestScore
                    && pitchScores_[lag] >= pitchScores_[lag - 1]
                    && pitchScores_[lag] >= pitchScores_[lag + 1]) {
                chosen = lag;
                break;
            }
        }
        // Parabolic interpolation between neighboring lags: sub-sample (cents
        // level) precision so the needle doesn't quantize-wobble on high notes.
        float refined = static_cast<float>(chosen);
        if (chosen > minLag && chosen < maxLag) {
            float sA = pitchScores_[chosen - 1];
            float sB = pitchScores_[chosen];
            float sC = pitchScores_[chosen + 1];
            float den = sA - 2.0f * sB + sC;
            if (std::fabs(den) > 1e-9f) {
                float delta = 0.5f * (sA - sC) / den;
                if (delta > -1.0f && delta < 1.0f) refined += delta;
            }
        }
        return static_cast<float>(sampleRate) / refined;
    }

    float midiNoteToHz(int note) const {
        if (note < 0 || note > 127) {
            return 0.0f;
        }
        return 440.0f * std::pow(2.0f, (static_cast<float>(note) - 69.0f) / 12.0f);
    }

    static void gkFft(float *re, float *im, int n) {
        for (int i = 1, j = 0; i < n; ++i) {
            int bit = n >> 1;
            for (; j & bit; bit >>= 1) j ^= bit;
            j |= bit;
            if (i < j) { std::swap(re[i], re[j]); std::swap(im[i], im[j]); }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2.0 * kPi / len;
            double wr = std::cos(ang), wi = std::sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1.0, ci = 0.0;
                for (int k = 0; k < len / 2; ++k) {
                    float vr = static_cast<float>(re[i + k + len / 2] * cr - im[i + k + len / 2] * ci);
                    float vi = static_cast<float>(re[i + k + len / 2] * ci + im[i + k + len / 2] * cr);
                    float ur = re[i + k], ui = im[i + k];
                    re[i + k] = ur + vr;
                    im[i + k] = ui + vi;
                    re[i + k + len / 2] = ur - vr;
                    im[i + k + len / 2] = ui - vi;
                    double ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }

    // Guitar Keys multi-pitch detection over two time scales: an 85ms window
    // (fast band, candidates >= G3) and a 170ms window decimated further
    // (slow band — bass strings, where 85ms cannot separate neighboring
    // fundamentals). Hann + zero-padded FFT, Klapuri-style harmonic salience,
    // iterative max picking with sub-harmonic preference and spectral-
    // smoothness subtraction. Runs on the pitch thread only (~150us on host).
    int gkPolyDetectCore(const float *buf, int count, int sampleRate,
                         int *notesOut, float *relOut) {
        const int kN = 4096;
        const int kHalf = kN / 2;
        const int kLowSplit = 55;   // below G3 the slow band judges
        // Fast band: newest half of the buffer, decimate x2.
        int half = count / 4;
        const float *recent = buf + count / 2;
        float rms = 0.0f;
        for (int i = 0; i < half; ++i) {
            float v = 0.5f * (recent[2 * i] + recent[2 * i + 1]);
            rms += v * v;
            float w = 0.5f - 0.5f * std::cos(2.0f * (float) kPi * i / (half - 1));
            gkFftRe_[i] = v * w;
            gkFftIm_[i] = 0.0f;
        }
        rms = std::sqrt(rms / half);
        for (int i = half; i < kN; ++i) { gkFftRe_[i] = 0.0f; gkFftIm_[i] = 0.0f; }
        if (rms < 0.004f) return 0;
        gkFft(gkFftRe_, gkFftIm_, kN);
        for (int k = 0; k <= kHalf; ++k) {
            gkMagA_[k] = std::sqrt(gkFftRe_[k] * gkFftRe_[k] + gkFftIm_[k] * gkFftIm_[k]);
        }
        // Slow band: whole buffer, decimate x4 (reaches sampleRate/8).
        for (int i = 0; i < half; ++i) {
            float v = 0.25f * (buf[4 * i] + buf[4 * i + 1] + buf[4 * i + 2] + buf[4 * i + 3]);
            float w = 0.5f - 0.5f * std::cos(2.0f * (float) kPi * i / (half - 1));
            gkFftRe_[i] = v * w;
            gkFftIm_[i] = 0.0f;
        }
        for (int i = half; i < kN; ++i) { gkFftRe_[i] = 0.0f; gkFftIm_[i] = 0.0f; }
        gkFft(gkFftRe_, gkFftIm_, kN);
        for (int k = 0; k <= kHalf; ++k) {
            gkMagB_[k] = std::sqrt(gkFftRe_[k] * gkFftRe_[k] + gkFftIm_[k] * gkFftIm_[k]);
        }
        float binHzA = (sampleRate * 0.5f) / kN;
        float binHzB = (sampleRate * 0.25f) / kN;
        float avgA = 0.0f, avgB = 0.0f;
        for (int k = 1; k <= kHalf; ++k) { avgA += gkMagA_[k]; avgB += gkMagB_[k]; }
        avgA /= kHalf;
        avgB /= kHalf;
        // Real spectral resolution: Hann mainlobe half-width of each window
        // (zero-padded bins are finer than this, the lobe is not).
        float lobeHzA = 2.0f * (sampleRate * 0.5f) / half;
        float lobeHzB = 2.0f * (sampleRate * 0.25f) / half;

        auto midiHz = [](int m) { return 440.0f * std::exp2((m - 69) / 12.0f); };
        auto specFor = [&](float f0, float *&mag, float &binHz, float &avg, float &lobe) {
            if (f0 < midiHz(kLowSplit)) {
                mag = gkMagB_; binHz = binHzB; avg = avgB; lobe = lobeHzB;
            } else {
                mag = gkMagA_; binHz = binHzA; avg = avgA; lobe = lobeHzA;
            }
        };
        auto peakIn = [&](const float *mag, float binHz, float f) -> float {
            float tol = std::max(f * 0.03f, binHz * 1.3f);
            int lo = std::max(1, (int) std::floor((f - tol) / binHz));
            int hi = std::min(kHalf - 1, (int) std::ceil((f + tol) / binHz));
            float m = 0.0f;
            for (int k = lo; k <= hi; ++k) if (mag[k] > m) m = mag[k];
            return m;
        };
        // skip > 0 excludes harmonics h that are multiples of skip — used to
        // measure a sub-harmonic candidate on only the partials it does NOT
        // share with the upper candidate.
        auto salienceX = [&](int m, int skip, int &cnt) -> float {
            float f0 = midiHz(m);
            float *mag;
            float binHz, avg, lobe;
            specFor(f0, mag, binHz, avg, lobe);
            float s = 0.0f;
            cnt = 0;
            for (int h = 1; h <= 8; ++h) {
                if (skip > 0 && h % skip == 0) continue;
                float fh = f0 * h;
                if (fh > binHz * (kHalf - 2)) break;
                float mg = peakIn(mag, binHz, fh);
                if (mg > avg * 3.0f) cnt++;
                s += mg * (f0 + 27.0f) / (h * f0 + 320.0f);
            }
            return s;
        };
        auto salience = [&](int m, int &cnt) -> float { return salienceX(m, 0, cnt); };
        // The fundamental must be a genuine local spectral peak within 60
        // cents of the candidate — skirt energy leaked from a neighbor's
        // mainlobe has no peak of its own, and a peak a semitone away
        // belongs to that note.
        auto fundIsPeak = [&](int m) -> bool {
            float f0 = midiHz(m);
            float *mag;
            float binHz, avg, lobe;
            specFor(f0, mag, binHz, avg, lobe);
            float tol = std::max(f0 * 0.035f, binHz * 2.6f);
            int lo = std::max(2, (int) std::floor((f0 - tol) / binHz));
            int hi = std::min(kHalf - 2, (int) std::ceil((f0 + tol) / binHz));
            for (int k = lo; k <= hi; ++k) {
                if (mag[k] > mag[k - 1] && mag[k] >= mag[k + 1]
                        && mag[k] > avg * 2.5f) {
                    float den = mag[k - 1] - 2.0f * mag[k] + mag[k + 1];
                    float d = std::fabs(den) > 1e-9f
                            ? 0.5f * (mag[k - 1] - mag[k + 1]) / den : 0.0f;
                    if (d < -1.0f || d > 1.0f) d = 0.0f;
                    float fpk = (k + d) * binHz;
                    if (fpk > 1.0f
                            && std::fabs(1200.0f * std::log2(fpk / f0)) < 60.0f) {
                        return true;
                    }
                }
            }
            return false;
        };
        // Subtract the picked note's smooth-envelope contribution in BOTH
        // spectra (Klapuri smoothness: a real doubled octave keeps its
        // excess energy, a phantom that fits the envelope is wiped).
        auto subtractNote = [&](int note) {
            float f0 = midiHz(note);
            float *specs[2] = {gkMagA_, gkMagB_};
            float bins[2] = {binHzA, binHzB};
            float lobes[2] = {lobeHzA, lobeHzB};
            for (int si = 0; si < 2; ++si) {
                float *mag = specs[si];
                float binHz = bins[si], lobe = lobes[si];
                float M[18] = {0.0f};
                int K = 0;
                for (int h = 1; h <= 16; ++h) {
                    float fh = f0 * h;
                    if (fh > binHz * (kHalf - 2)) break;
                    M[h] = peakIn(mag, binHz, fh);
                    K = h;
                }
                for (int h = 1; h <= K; ++h) {
                    float nb1 = h > 1 ? M[h - 1] : M[h + 1];
                    float nb2 = h < K ? M[h + 1] : M[h - 1];
                    float smooth = std::min(M[h], 0.5f * (nb1 + nb2));
                    float scale = M[h] > 1e-9f
                            ? std::max(M[h] - smooth, 0.0f) / M[h] : 0.0f;
                    float fh = f0 * h;
                    float tol = std::max(fh * 0.03f, lobe);
                    int lo = std::max(1, (int) std::floor((fh - tol) / binHz));
                    int hi = std::min(kHalf - 1, (int) std::ceil((fh + tol) / binHz));
                    for (int k = lo; k <= hi; ++k) mag[k] *= scale;
                }
            }
        };

        bool picked[128] = {false};
        float pickedHz[6];
        int n = 0;
        float sFirst = 0.0f;
        // Within one mainlobe of a picked fundamental everything is the same
        // smeared peak — no second pick there.
        auto blockedAt = [&](int m) -> bool {
            if (picked[m]) return true;
            float f0 = midiHz(m);
            float lobe = f0 < midiHz(kLowSplit) ? lobeHzB : lobeHzA;
            for (int i = 0; i < n; ++i) {
                if (std::fabs(f0 - pickedHz[i]) < lobe) return true;
            }
            return (m > 0 && picked[m - 1]) || picked[m + 1];
        };
        // A candidate sitting on an exact harmonic of a picked note shares
        // that note's entire partial set; only a strong post-subtraction
        // excess (a real doubled string) may claim it. Returns the harmonic
        // number (0 = not a multiple): an octave (2x) is normal playing, but
        // 3x/5x "notes" are how a played octave (E2+E3) grows a phantom
        // B3/G#4 and turns into a chord that was never played — those need
        // far more independent energy to pass.
        auto multipleOfPicked = [&](int m) -> int {
            float f0 = midiHz(m);
            float lobe = f0 < midiHz(kLowSplit) ? lobeHzB : lobeHzA;
            for (int i = 0; i < n; ++i) {
                int h = (int) std::lround(f0 / pickedHz[i]);
                if (h >= 2 && h <= 8
                        && std::fabs(f0 - h * pickedHz[i])
                                < std::max(f0 * 0.035f, lobe * 0.6f)) {
                    return h;
                }
            }
            return 0;
        };
        for (int pick = 0; pick < 6; ++pick) {
            int best = -1;
            float bs = 0.0f;
            for (int m = 38; m <= 88; ++m) {
                if (blockedAt(m)) continue;
                int c;
                float s = salience(m, c);
                if (c < (m >= 74 ? 3 : 2) || s <= bs) continue;
                if (n > 0) {
                    int mh = multipleOfPicked(m);
                    float gate = mh == 0 ? 0.12f : mh == 2 ? 0.20f : 0.50f;
                    if (s < gate * sFirst) continue;
                }
                if (!fundIsPeak(m)) continue;
                bs = s;
                best = m;
            }
            if (best < 0) break;
            // A candidate at 2x/3x/4x a real note shares that note's partial
            // set; prefer the sub-harmonic when it has enough life of its own
            // on the partials the upper candidate can't explain.
            for (int guard = 0; guard < 2; ++guard) {
                static const int kSubs[3][2] = {{12, 2}, {19, 3}, {24, 4}};
                int sub = -1;
                float subS = 0.0f;
                for (const auto &sd : kSubs) {
                    int m2 = best - sd[0];
                    if (m2 < 38 || blockedAt(m2)) continue;
                    int c2;
                    float s2 = salience(m2, c2);
                    if (c2 < 2 || s2 <= 0.45f * bs || s2 <= subS || !fundIsPeak(m2)) continue;
                    int cx;
                    float sx = salienceX(m2, sd[1], cx);
                    if (cx >= 2 && sx > 0.30f * s2) { sub = m2; subS = s2; }
                }
                if (sub < 0) break;
                best = sub;
                bs = subS;
            }
            if (pick == 0) {
                sFirst = bs;
                if (bs < std::min(avgA, avgB) * 9.0f) break;
            }
            picked[best] = true;
            pickedHz[n] = midiHz(best);
            notesOut[n] = best;
            relOut[n] = std::min(1.0f, bs / sFirst);
            n++;
            subtractNote(best);
        }
        return n;
    }

    // One poly analysis frame: detect, then vote over time — a note needs
    // 2 consecutive frames to appear and 3 missing frames to drop. Publishes
    // the active set as a bitmask (bit i = MIDI note 36+i) for the audio
    // thread. Pitch thread only.
    void gkAnalyzePoly(const float *buf, int count, int sampleRate) {
        int notes[6];
        float rel[6];
        int nd = gkPolyDetectCore(buf, count, sampleRate, notes, rel);
        bool hit[64] = {false};
        for (int i = 0; i < nd; ++i) {
            int b = notes[i] - 36;
            if (b >= 0 && b < 64) {
                hit[b] = true;
                gkPolyVel_[b] = rel[i];
            }
        }
        uint64_t mask = 0;
        float strongest = -1.0f;
        float strongHz = 0.0f;
        for (int i = 0; i < 64; ++i) {
            if (hit[i]) {
                gkMiss_[i] = 0;
                if (++gkSeen_[i] >= 2) gkActive_[i] = true;
            } else {
                gkSeen_[i] = 0;
                if (gkActive_[i] && ++gkMiss_[i] >= 3) gkActive_[i] = false;
            }
            if (gkActive_[i]) {
                mask |= 1ULL << i;
                if (gkPolyVel_[i] > strongest) {
                    strongest = gkPolyVel_[i];
                    strongHz = midiNoteToHz(i + 36);
                }
            }
        }
        gkPolyMask_.store(mask, std::memory_order_relaxed);
        smoothedPitchHz_.store(strongHz);
        pitchHz_.store(strongHz);
    }

    // Pitch-thread reset of the poly voting state (mode switch / idle).
    void gkPolyReset() {
        if (gkPolyMask_.load(std::memory_order_relaxed) == 0) {
            bool clean = true;
            for (int i = 0; i < 64 && clean; ++i) clean = gkSeen_[i] == 0 && !gkActive_[i];
            if (clean) return;
        }
        for (int i = 0; i < 64; ++i) {
            gkSeen_[i] = 0;
            gkMiss_[i] = 0;
            gkActive_[i] = false;
        }
        gkPolyMask_.store(0, std::memory_order_relaxed);
    }

    void setStatus(const std::string &status) {
        std::lock_guard<std::mutex> lock(statusMutex_);
        status_ = status;
    }

    std::mutex streamMutex_;
    EngineConfig lastConfig_{};        // last start() config, for auto-restart
    std::atomic<int> restartGen_{0};
    mutable std::mutex statusMutex_;
    std::string status_ = "Engine: stopped";

    std::shared_ptr<oboe::AudioStream> inputStream_;
    std::shared_ptr<oboe::AudioStream> instStream_;
    std::shared_ptr<oboe::AudioStream> outputStream_;
    std::atomic<oboe::AudioStream *> inputStreamRaw_{nullptr};
    std::atomic<oboe::AudioStream *> instStreamRaw_{nullptr};
    std::atomic<int> loopInstDevice_{kNoDevice};

    std::atomic<bool> running_{false};
    std::atomic<int> instrument_{kElectricGuitar};
    std::atomic<int> tone_{kGuitarOverdrive};
    std::atomic<int> inputRoute_{0};
    std::atomic<int> inputDeviceId_{kNoDevice};
    std::atomic<int> outputDeviceId_{kNoDevice};
    std::atomic<bool> monoOut_{false};   // sum output to mono (mixer/PA safe)
    // ---- Looper (Loop Mix screen) ----
    // Track 0 = vocals (mic), 1..3 = instrument (drums + harmonized voice).
    static constexpr int kNumLoops = 4;
    // Per-track capacity: long-form vocals, tighter instrument loops.
    // (RAM: ~0.77 MB per stereo second per track, undo copy included.)
    static constexpr int kLoopSeconds[kNumLoops] = {60, 24, 24, 24};
    static constexpr int kLoopCopyChunk = 32768;   // frames copied per callback for undo jobs
    std::vector<float> loopBuf_[kNumLoops];          // stereo interleaved, preallocated
    std::atomic<int> loopState_[kNumLoops]{};        // LoopState, written by audio thread
    std::atomic<int> loopCmd_[kNumLoops]{};          // pending UI command (1 tap/2 pause/3 clear)
    std::atomic<int> loopLenShared_[kNumLoops]{};    // frames, for UI + save
    std::atomic<float> loopPosNorm_[kNumLoops]{};    // 0..1 playhead for the ring arc
    int loopLen_[kNumLoops] = {0, 0, 0, 0};          // audio-thread-owned
    int loopPos_[kNumLoops] = {0, 0, 0, 0};
    // Single-level overdub undo: before an overdub session touches a sample, its
    // old value is saved to undoBuf_ by a chunked snapshot job that runs just
    // ahead of the writer (no big copies on the audio thread).
    std::vector<float> undoBuf_[kNumLoops];
    int snapBase_[kNumLoops] = {0, 0, 0, 0};         // session start position
    int snapDone_[kNumLoops] = {0, 0, 0, 0};         // frames snapshotted so far
    bool snapActive_[kNumLoops] = {false, false, false, false};
    int restoreDone_[kNumLoops] = {0, 0, 0, 0};
    int restoreExtent_[kNumLoops] = {0, 0, 0, 0};
    bool restoreActive_[kNumLoops] = {false, false, false, false};
    bool undoValid_[kNumLoops] = {false, false, false, false};
    std::atomic<int> lastOverdubTrack_{-1};
    std::atomic<bool> loopMuted_[kNumLoops]{};       // muted tracks keep looping silently (stay in sync)
    std::atomic<int> loopGlobalCmd_{0};              // 1 = pause all, 2 = resume all from 0
    std::atomic<bool> loopMonitor_{false};           // hear the live mic in the output or not
    std::atomic<bool> inputMute_{false};
    std::atomic<bool> harmOn_{false};
    std::atomic<float> harmSemi1_{-12.0f};           // voice intervals in semitones,
    std::atomic<float> harmSemi2_{99.0f};            // 99 = voice off
    std::atomic<bool> harmChoir_{false};             // add a detuned unison pair
    std::atomic<float> harmLevel_{0.85f};
    std::atomic<int> harmTone_{0};                   // 0 flat, 1 warm, 2 bright
    std::atomic<bool> harmRev_{false};
    std::array<float, 4096> harmBuf_{};              // harmonizer delay line (mono)
    int harmW_ = 0;
    float harmPh_[4] = {0.0f, 0.0f, 0.0f, 0.0f};
    float harmSemiCache_[4] = {999.0f, 999.0f, 999.0f, 999.0f};
    float harmRatioCache_[4] = {1.0f, 1.0f, 1.0f, 1.0f};
    float harmToneLp_ = 0.0f;
    float harmTiltLp_[2] = {0.0f, 0.0f};             // formant counter-tilt state
    float harmDecim_[512] = {};                      // 12kHz ring for pitch tracking
    int harmDecW_ = 0;
    int harmDecPhase_ = 0;
    int harmDetCount_ = 0;
    float harmPeriod_ = 480.0f;                      // smoothed period @48k (~100Hz)
    float harmWin_ = 480.0f;                         // current grain window (smoothed)
    float harmEnv_ = 0.0f;                           // mic envelope for the gate
    // PSOLA state: voices 0/1 = harmony, 2 = autotune; up to 6 grains each.
    static constexpr int kHarmGrains = 6;
    int hgDi_[3][kHarmGrains] = {};                  // grain delay (int + frac parts)
    float hgDf_[3][kHarmGrains] = {};
    float hgPhase_[3][kHarmGrains] = {};
    float hgStep_[3][kHarmGrains] = {};
    bool hgOn_[3][kHarmGrains] = {};
    float hgSpawn_[3] = {};
    float harmMarkPhase_ = 0.0f;
    int harmLastMark_ = 0;
    std::atomic<bool> harmTune_{false};
    float harmTuneRatio_ = 1.0f;
    std::atomic<float> vocalRev_{0.25f};             // Vocals screen reverb amount
    std::atomic<int> loopRecBars_[kNumLoops]{};

    std::atomic<float> inputLevelDb_{-120.0f};
    std::atomic<float> outputLevelDb_{-120.0f};
    std::atomic<float> outLatencyMs_{0.0f};
    int cbCount_ = 0;
    std::atomic<float> pitchHz_{0.0f};
    std::atomic<int> pitchSeq_{0};                    // bumped on each new GK reading
    std::vector<float> pitchScores_;                 // per-lag scores (pitch thread only)
    float pitchHist_[5] = {};                        // recent readings for the median
    int pitchHistIdx_ = 0;
    std::atomic<bool> midiGate_{false};
    std::atomic<float> midiFrequency_{440.0f};
    std::atomic<float> midiVelocity_{0.7f};
    std::atomic<float> control1_{0.62f};
    std::atomic<float> control2_{0.52f};
    std::atomic<float> control3_{0.58f};
    std::atomic<float> control4_{0.56f};
    std::atomic<float> control5_{0.60f};
    std::atomic<float> control6_{0.72f};

    int sampleRate_ = 48000;
    float drumMetalLp_[2] = {0.0f, 0.0f};   // post-saturation low-pass state (metal drums)
    float drumDcX_[2] = {0.0f, 0.0f};       // DC-blocker input history (metal drums)
    float drumDcY_[2] = {0.0f, 0.0f};       // DC-blocker output history (metal drums)
    float drumMetalCoeff_ = 0.5f;           // low-pass coefficient (set from sample rate)
    int32_t burstFrames_ = 0;       // device burst, used for adaptive buffer sizing
    int32_t prevXRuns_ = 0;         // last seen underrun count (glitch tracking)
    int32_t maxBufFrames_ = 0;      // cap for adaptive buffer growth
    int32_t minBufFrames_ = 0;      // stable low-latency floor for this session
    int32_t stableCallbacks_ = 0;   // callbacks since the last underrun
    std::array<float, kInputBufferCapacity> inputBuffer_{};
    float inputTail_ = 0.0f;        // bridges occasional short capture reads
    std::array<float, kInputBufferCapacity> instBuffer_{};   // instrument line-in
    std::array<float, kGkPolyWindow> pitchBuffer_{};
    std::array<float, kGkPolyWindow> analysisBuffer_{};
    std::atomic<int> pitchWriteIndex_{0};
    std::atomic<int> pitchFilled_{0};
    std::thread pitchThread_;
    std::atomic<bool> pitchThreadRunning_{false};

    float c1_ = 0.62f;
    float c2_ = 0.52f;
    float c3_ = 0.58f;
    float c4_ = 0.56f;
    float c5_ = 0.60f;
    float c6_ = 0.72f;

    float meterRms_ = 0.0f;
    float outMeterRms_ = 0.0f;
    float guitarHpAlpha_ = 0.98f;
    float guitarHpX_ = 0.0f;
    float guitarHpY_ = 0.0f;
    float guitarToneCoeff_ = 0.35f;
    float guitarToneState_ = 0.0f;
    // Boss GT-1000 rig state: comp envelope + voicing/DC filters
    float gtEnv_ = 0.0f;
    float gtLp1_ = 0.0f;
    float gtLp2_ = 0.0f;
    float gtDc_ = 0.0f;
    float liveToneState_ = 0.0f;
    float wahEnv_ = 0.0f;
    // Manual wah pedal (separate from the auto-wah tone's envelope/SVF state).
    std::atomic<bool> wahOn_{false};
    std::atomic<float> wahPos_{0.5f};
    float wahLow_ = 0.0f;
    float wahBand_ = 0.0f;
    // Guitar cabinet / IR pedal (on by default: a raw DI sounds too "physical").
    std::atomic<bool> cabOn_{true};
    std::atomic<int> cabType_{0};
    std::atomic<float> cabMix_{1.0f};
    GuitarCab cab_;
    std::atomic<bool> guitarCompOn_{true};
    std::atomic<float> guitarCompAmount_{0.35f};
    float guitarCompEnv_ = 0.0f;
    std::atomic<bool> guitarModOn_{false};
    std::atomic<float> guitarModRate_{0.25f};
    std::atomic<float> guitarModDepth_{0.30f};
    std::array<float, 4096> guitarModDelay_{};
    int guitarModWrite_ = 0;
    float guitarModPhase_ = 0.0f;
    float namToneLow_ = 0.0f;
    float namToneMid_ = 0.0f;
    std::atomic<bool> guitarDelayOn_{false};
    std::atomic<float> guitarDelayTime_{0.32f};
    std::atomic<float> guitarDelayFeedback_{0.28f};
    std::atomic<float> guitarDelayMix_{0.22f};
    std::array<float, 96000> guitarDelay_{};
    int guitarDelayWrite_ = 0;
    std::atomic<bool> guitarRoomOn_{false};
    std::atomic<float> guitarRoomMix_{0.22f};
    std::atomic<float> metalBoostDrive_{0.34f};
    std::atomic<int> metalRigStyle_{0};
    std::atomic<float> metalBoostTone_{0.52f};
    std::atomic<float> metalBoostLevel_{0.72f};
    float metalBoostLow_ = 0.0f;
    float metalBoostToneState_ = 0.0f;
    std::atomic<float> metalDelayTime_{0.28f};
    std::atomic<float> metalDelayFeedback_{0.22f};
    std::atomic<float> metalDelayMix_{0.16f};
    std::array<float, 96000> metalDelay_{};
    int metalDelayWrite_ = 0;
    // MIDI/SF2 guitar pedalboard. It is separate from the microphone guitar
    // chain and can be enabled independently for Keyboard A and Keyboard B.
    std::atomic<bool> pianoGuitarRigA_{false};
    std::atomic<bool> pianoGuitarRigB_{false};
    std::atomic<int> pianoGuitarAmp_{1};
    std::atomic<int> pianoGuitarCab_{0};
    std::atomic<float> pianoGuitarDrive_{0.55f};
    std::atomic<float> pianoGuitarTone_{0.58f};
    std::atomic<float> pianoGuitarHarmonics_{0.35f};
    std::atomic<bool> virtualGuitarPlayer_{false};
    std::atomic<bool> virtualGuitarReset_{false};
    int virtualGuitarStringNote_[8]{};
    int virtualGuitarNoteString_[128]{};
    int virtualGuitarNoteSource_[128]{};
    bool virtualGuitarPickDown_ = false;
    int virtualGuitarPickSeq_ = 0;
    float virtualGuitarStrike_ = 0.7f;
    int virtualGuitarSeenPick_[2][2]{};
    float virtualGuitarPickEnv_[2][2]{};
    float pianoGuitarDcX_[2][2]{};
    float pianoGuitarDcY_[2][2]{};
    float pianoGuitarTight_[2][2]{};
    float pianoGuitarToneState_[2][2]{};
    float pianoGuitarHarmDc_[2][2]{};
    GuitarCab pianoGuitarCabState_[2][2];
    static constexpr int kNamBlockFrames = 1024;
    std::mutex namLoadMutex_;
    std::unique_ptr<nam::DSP> namOwned_;
    std::atomic<nam::DSP *> namModel_{nullptr};
    std::atomic<int> namReaders_{0};
    std::atomic<bool> namEnabled_{false};
    std::atomic<float> namMix_{1.0f};
    std::atomic<float> namInputGain_{1.0f};
    std::atomic<float> namOutputGain_{0.70f};
    std::atomic<float> namExpectedRate_{0.0f};
    std::array<NAM_SAMPLE, kNamBlockFrames> namInput_{};
    std::array<NAM_SAMPLE, kNamBlockFrames> namOutput_{};
    static constexpr int kNamIrTaps = 512;
    std::array<float, kNamIrTaps> namIr_[2]{};
    std::atomic<int> namIrLength_[2]{};
    std::atomic<int> namIrActive_{0};
    std::atomic<bool> namIrEnabled_{false};
    std::atomic<float> namIrLevel_{1.0f};
    std::atomic<bool> virtualGuitarMode_{false};
    std::atomic<float> virtualGuitarOutput_{1.0f};
    float virtualGuitarPolyGain_ = 1.0f;
    std::atomic<bool> namIrReset_{false};
    std::array<float, kNamIrTaps> namIrHistory_{};
    int namIrWrite_ = 0;
    float svfLow_ = 0.0f;
    float svfBand_ = 0.0f;
    float bassLpCoeff_ = 0.25f;
    float bassLpState_ = 0.0f;
    float bassEnv_ = 0.0f;
    float bassHpAlpha_ = 0.99f;   // subsonic high-pass (feedback / DC control)
    float bassHpX_ = 0.0f;
    float bassHpY_ = 0.0f;
    // Noise gate (guitar / bass): mutes idle hum/buzz between notes.
    std::atomic<float> gateThresh_{0.0f};   // 0 = off
    float gateEnv_ = 0.0f;
    float gateGain_ = 0.0f;
    bool gateState_ = false;
    float gateEnvRel_ = 0.999f;   // envelope release (per-sample)
    float gateAtt_ = 0.5f;        // gate open smoothing (fast)
    float gateRel_ = 0.02f;       // gate close smoothing (slower)
    float bassPrev_ = 0.0f;
    float bassSubPolarity_ = 1.0f;
    float pianoEnv_ = 0.0f;
    float midiEnv_ = 0.0f;
    float decayEnv_ = 0.0f;
    float brightEnv_ = 0.0f;
    int lastNoteId_ = 0;
    std::atomic<int> noteOnId_{0};
    std::atomic<float> smoothedPitchHz_{0.0f};
    double pianoPhase_ = 0.0;
    std::atomic<float> cymGainHat_{1.15f};
    std::atomic<float> cymGainRide_{1.40f};
    std::atomic<float> cymGainCrash_{1.30f};
    std::atomic<bool> cymbalChoke_{false};
    double bassSynthPhase_ = 0.0;

    // Sampled-instrument playback (SoundFont via TinySoundFont).
    std::atomic<tsf *> sound_{nullptr};
    std::atomic<tsf *> sound2_{nullptr};   // dedicated GM copy for Sound 2 (Keyboard B)
    std::atomic<bool> manualSplit_{false}; // Full Keys per-board routing (no auto-layer)
    // Dedicated high-quality fonts (grand, Rhodes, Wurli, ...) routed per preset.
    static constexpr int kVirtualGuitarPalmSlot = 9;
    static constexpr int kVirtualGuitarHarmSlot = 10;
    static constexpr int kNumHqFonts = 17;  // + six lazy external layer fonts
    std::atomic<tsf *> hqFonts_[kNumHqFonts]{};
    std::atomic<int> fontSlot_{-1};
    int activeSlot_ = -1;
    std::atomic<int> midiProgram_{0};
    int appliedProgram_ = -1;
    // Layered preset: second GM program on channel 1 (-1 = single sound).
    std::atomic<int> midiLayerProgram_{-1};
    int appliedLayerProgram_ = -1;
    // Extra keyboard layers 3 & 4: GM programs on the GM font channels 2 & 3,
    // each blended by its own volume. -1 = layer off. Layer 2 (ch1) reuses the
    // dual channel above; its blend volume is layer2Vol_.
    // Keyboard A extra layers L2/L3/L4 on GM channels 2/3/4.
    std::atomic<int> layer3Program_{-1};
    std::atomic<int> layer4Program_{-1};
    std::atomic<int> layer5Program_{-1};   // A's L4, GM channel 4
    int appliedLayer3_ = -1;
    int appliedLayer4_ = -1;
    int appliedLayer5_ = -1;
    std::atomic<float> layer5Vol_{0.0f};   // ch4 blend level
    // Keyboard B extra layers L6/L7/L8 on GM channels 5/6/7 (B master = ch1).
    std::atomic<int> layer6Program_{-1};
    std::atomic<int> layer7Program_{-1};
    std::atomic<int> layer8Program_{-1};
    int appliedLayer6_ = -1;
    int appliedLayer7_ = -1;
    int appliedLayer8_ = -1;
    std::atomic<float> layer6Vol_{0.0f};   // ch5
    std::atomic<float> layer7Vol_{0.0f};   // ch6
    std::atomic<float> layer8Vol_{0.0f};   // ch7
    std::atomic<float> layer1Vol_{1.0f};   // ch0 main-sound level (for the layer mix)
    std::atomic<float> layer2Vol_{1.0f};   // ch1 blend level
    std::atomic<float> layer3Vol_{1.0f};   // ch2 blend level
    std::atomic<float> layer4Vol_{1.0f};   // ch3 blend level
    // Layer split: a second 4-layer stack on channels 4-7 (mirrors the same 4
    // sounds) with its own levels, so each half of a split keyboard blends
    // independently. blend: 0 = off, 1 = key-split at layerSplitNote_ (below =
    // side B), 2 = two-manual (noteOn = side A, note2On = side B).
    std::atomic<int> layerBlendMode_{0};
    std::atomic<int> layerSplitNote_{60};
    // Per-channel attack "zap": at note-on the channel is detuned up by this many
    // semitones, then decays to 0 in ~50ms — a laser/zip transient under the tone.
    // Indexed by physical layer channel 0..7 (0=snd, 1=alt, 2..7=gm).
    std::atomic<int> layerZap_[8] = {};   // configured depth in semitones, 0 = off
    float zapOff_[8] = {};                // audio-thread current offset
    bool  zapActive_[8] = {};
    // Per-channel mixer meter: a fast velocity peak on attack PLUS a held level
    // that stays up while any note is sounding on the channel, so the meter
    // reflects sustained sound instead of flashing on each tap.
    std::atomic<float> chanMeter_[8] = {};
    float meterEnv_[8] = {};       // velocity peak (fast decay)
    float meterHold_[8] = {};      // chases "channel has notes held"
    bool  chanNote_[8][128] = {};  // which notes are sounding per channel
    int   chanActive_[8] = {};     // count of notes sounding per channel
    std::atomic<float> layer1VolB_{0.0f};
    std::atomic<float> layer2VolB_{0.0f};
    std::atomic<float> layer3VolB_{0.0f};
    std::atomic<float> layer4VolB_{0.0f};
    int appliedBlend_ = 0;
    std::atomic<int> loopKeysLayer_{-1};
    int appliedLoopKeysLayer_ = -1;
    // Dual sound: notes >= split play ONLY channel 1 (sound 2); -1 = layer mode.
    std::atomic<int> midiKeySplit_{-1};
    std::atomic<int> loopKeySplit_{-1};
    // Sound 2 from an HQ font slot instead of a GM program (-1 = GM channel 1).
    std::atomic<int> dualFontSlot_{-1};
    std::atomic<int> layerFontSlot_[8];
    tsf *appliedAltFont_ = nullptr;
    // Looper keys pitch bender (0..16383, center 8192).
    std::atomic<int> loopKeysBend_{8192};
    int appliedLoopKeysBend_ = -1;
    // Bender range in semitones at full throw (shared by both benders).
    std::atomic<int> bendRange_{2};
    int appliedBendRange_ = -1;       // piano path
    int appliedLoopBendRange_ = -1;   // looper keys path
    int audioActiveNote_ = -1;
    // Guitar→Keys tracker state (audio thread only).
    float gkEnv_ = 0.0f;
    float gkRef_ = 0.0f;      // recent-peak hold for sudden-mute detection
    float gkHpX_ = 0.0f;      // analysis high-pass state (rumble rejection)
    float gkHpY_ = 0.0f;
    float gkFloor_ = 0.0f;    // ambient noise floor (hiss/whine on the raw mic)
    float gkGateOn_ = 0.012f;
    float gkGateOff_ = 0.008f;
    int gkQuietFrames_ = 0;   // consecutive collapsed frames since that peak
    int gkIdleTicks_ = 0;     // callbacks with an idle tracker (voice watchdog)
    std::atomic<bool> gkMuteFlush_{false};   // audio → pitch thread: clear votes
    float gkSlow_ = 0.0f;
    int gkNote_ = -1;
    int gkCand_ = -1;
    int gkOnsetHold_ = 0;
    int gkLastSeq_ = -1;      // last pitch-reading serial the note logic saw
    // Piano slide mode (legato presses bend instead of re-attacking)
    std::atomic<bool> glideOn_{false};
    // Mono slide: a detached press (previous key already lifted) silences the
    // ringing voice instead of stacking tails — one voice per stream, like a
    // stylophone / mono synth. Only overlapped presses slide.
    std::atomic<bool> glideMono_{false};
    std::atomic<float> glideRate_{60.0f};   // semitones per second
    int glideStack_[16] = {};   // physically held keys, newest last (audio thread)
    int glideStackN_ = 0;
    int glideAnchor_ = -1;      // the key whose voice sounds (gets bent around)
    float glideOffCur_ = 0.0f;  // current tuning offset, semitones
    float glideOffTarget_ = 0.0f;
    // Stream 2: dual Sound 2 / the upper split half (alt font, channel 1).
    int glide2Stack_[16] = {};
    int glide2StackN_ = 0;
    int glide2Anchor_ = -1;
    float glide2OffCur_ = 0.0f;
    float glide2OffTarget_ = 0.0f;
    // Looper keys slide mode (same idea, separate state)
    std::atomic<bool> loopGlideOn_{false};
    std::atomic<bool> loopGlideMono_{false};
    int lkStack_[16] = {};
    int lkStackN_ = 0;
    int lkAnchor_ = -1;
    float lkOffCur_ = 0.0f;
    float lkOffTarget_ = 0.0f;
    // Looper keys stream 2: dual Sound 2 / the upper board (channel 1).
    int lk2Stack_[16] = {};
    int lk2StackN_ = 0;
    int lk2Anchor_ = -1;
    float lk2OffCur_ = 0.0f;
    float lk2OffTarget_ = 0.0f;
    // Guitar Keys polyphonic tracker
    std::atomic<bool> gkPoly_{true};
    std::atomic<int> gkTranspose_{0};       // semitones added to played notes (bass = -12)
    std::atomic<bool> gkBendFollow_{true};  // mono: wheel rides the exact pitch
    float gkBendCur_ = 0.0f;                // smoothed wheel offset, semitones (audio thread)
    tsf *gkBendSnd_ = nullptr;              // font the wheel range was applied to
    int gkSoundTrans_ = 0;                  // transpose the sounding poly notes used
    std::atomic<uint64_t> gkPolyMask_{0};   // bit i = MIDI 36+i active (pitch thread → audio)
    float gkPolyVel_[64] = {};              // relative note strengths (benign race)
    int gkSeen_[64] = {};                   // temporal voting state (pitch thread only)
    int gkMiss_[64] = {};
    bool gkActive_[64] = {};
    uint64_t gkSounding_ = 0;               // notes currently on (audio thread only)
    float gkFftRe_[4096] = {};              // FFT work areas (pitch thread only)
    float gkFftIm_[4096] = {};
    float gkMagA_[2049] = {};
    float gkMagB_[2049] = {};
    std::atomic<int> drumKit_{0};
    std::atomic<int> drumRemap_{0};   // 0 none · 1 808 · 2 reggae · 3 mambo · 4 beatbox
    int appliedDrumKit_ = -1;
    // Loop Mix keys: GM program for the on-screen looper keyboard (channel 0),
    // or an HQ piano font slot (loopKeysSlot_ >= 0 overrides the GM program).
    std::atomic<int> loopKeysProg_{0};
    std::atomic<int> loopKeysSlot_{-1};
    int appliedLoopKeysProg_ = -1;
    tsf *activeKeysFont_ = nullptr;   // audio thread: font the keys last played on
    // Dedicated HQ drum fonts (bank-0, GM-mapped). drumKit_ >= kHqDrumBase selects them:
    //   slot = (kit - kHqDrumBase) / 100, preset = (kit - kHqDrumBase) % 100.
    static constexpr int kHqDrumBase = 1000;
    static constexpr int kMetalDriveBase = 10000;  // kit >= this → drive the kit for an aggressive metal tone
    static constexpr int kNumDrumFonts = 46;   // 40 kits + 6 sample-library category fonts (40-45)

    // One-shot "Chimes" (Drums screen): plays the loaded chimes.wav once and
    // can't be retriggered while it is still sounding.
    std::atomic<bool> chimeTrigger_{false};
    std::atomic<bool> chimeActive_{false};
    std::atomic<bool> chimeReady_{false};
    std::atomic<float> chimeGain_{1.0f};
    std::vector<float> chimeSample_;   // interleaved stereo
    int chimeSampleFrames_ = 0;
    int chimeSampleRate_ = 48000;
    double chimePos_ = 0.0;
    static constexpr int kSwellLayers = 5;
    static constexpr int kMaxSwellGroups = 8;
    static constexpr int kMaxSwellVoices = kSwellLayers * kMaxSwellGroups;
    struct SwellVoice {
        int sample = -1, delay = 0, layer = 0;
        double pos = 0.0;
        bool active = false;
    };
    std::atomic<int> swellPending_[6]{};
    std::atomic<bool> swellReady_[6]{};
    std::vector<float> swellSample_[6];
    int swellSampleFrames_[6]{};
    int swellSampleRate_[6]{48000, 48000, 48000, 48000, 48000, 48000};
    SwellVoice swellVoice_[kMaxSwellVoices];
    int swellVoiceCursor_ = 0;
    static constexpr int k808Slot = 1;   // HS TR-808: remap pads with no native sample
    std::atomic<tsf *> drumFonts_[kNumDrumFonts]{};
    // Custom kit: each drum note can sound from a different kit, mixed together.
    // Per-note source code (drumPieceSlot_): 0..kNumDrumFonts-1 = HQ font slot (clean),
    //   kPieceDriveBase+slot = HQ font slot with metal drive, kPieceGmBase+i = GM kit i.
    static constexpr int kPieceDriveBase = 200;
    static constexpr int kPieceGmBase = 100;
    // Full Kit "Default" pieces encode the complete selected drum program here,
    // preserving preset variants and metal drive instead of only a font slot.
    static constexpr int kPieceSelectedBase = 100000;
    static constexpr int kSelectedKitChannel = 15;
    static constexpr int kGmKitCount = 6;
    static constexpr int kGmDrumChannel0 = 9;   // GM kits use channels 9..9+kGmKitCount-1
    static constexpr int kGmPrograms[kGmKitCount] = {0, 8, 16, 24, 32, 40};  // Std/Room/Power/Elec/Jazz/Brush
    std::atomic<bool> customDrum_{false};
    std::atomic<int> drumPieceSlot_[128];   // per-note source code, -1 = unassigned
    std::atomic<int> drumPieceSrcNote_[128];// per-trigger-note: note to actually sound, -1 = same as trigger
    std::atomic<int> previewSlot_{-1};      // Kit Mode sound-picker audition: font slot (-1 if GM)
    std::atomic<int> previewGm_{-1};        // GM kit index for audition, -1 if a drum font
    std::atomic<int> previewNote_{0};
    std::atomic<int> previewFrames_{0};     // remaining render window (frames)
    std::atomic<bool> previewTrig_{false};
    std::atomic<float> drumPieceGain_[128]; // per-note level trim (velocity scale), 1.0 = unity
    std::atomic<float> drumPiecePan_[128];  // per-note pan, 0=L .. 0.5=C .. 1=R
    std::atomic<uint64_t> customSlotMask_{0}; // bitmask of HQ font slots used (64-bit: up to 46 slots)
    std::atomic<uint64_t> customDriveMask_{0};// subset of HQ slots rendered with metal drive
    std::atomic<int> customGmMask_{0};       // bitmask of GM kits used (bit i)
    std::atomic<int> customSelectedKit_{-1}; // complete selected program used by Default pieces
    uint64_t appliedCustomMask_ = ~0ULL;
    int appliedGmMask_ = -1;
    int appliedSelectedKit_ = -1;
    std::array<float, kInputBufferCapacity> mixScratch_{};   // per-slot driven-render scratch
    static constexpr int kEventQueueSize = 256;
    static constexpr int kEvNoteOff = 0;
    static constexpr int kEvNoteOn = 1;
    static constexpr int kEvKeyOff = 2;   // Loop Mix keys: melodic note-off (GM channel 0)
    static constexpr int kEvKeyOn = 3;    // Loop Mix keys: melodic note-on (GM channel 0)
    static constexpr int kEvAllOff = 4;   // panic: release every melodic voice
    static constexpr int kEvKey2Off = 5;  // Loop Mix keys: note straight to sound 2 (ch 1)
    static constexpr int kEvKey2On = 6;
    static constexpr int kEvNote2Off = 7; // piano keys: note straight to sound 2 (ch 1)
    static constexpr int kEvNote2On = 8;
    int eventBuffer_[kEventQueueSize][3]{};
    // Lock-free SPSC-style ring: producers (UI/MIDI) serialize on producerMutex_
    // and publish head; the audio thread consumes without ever blocking, so drum
    // hits are never deferred or dropped by lock contention.
    std::atomic<int> eventHead_{0};
    std::atomic<int> eventTail_{0};
    std::mutex producerMutex_;

    // Per-preset tone-shaping effects applied to the SoundFont output, so
    // presets sharing a sample (e.g. Rhodes variants) can sound distinct.
    std::atomic<float> fxTone_{0.0f};
    std::atomic<float> fxDrive_{0.0f};
    std::atomic<float> fxChorus_{0.0f};
    std::atomic<float> fxTrem_{0.0f};
    std::atomic<float> fxSoft_{0.0f};   // extra high-roll-off "softness" (0..1)
    float ft_ = 0.0f;
    float fd_ = 0.0f;
    float fc_ = 0.0f;
    float ftr_ = 0.0f;
    float fs_ = 0.0f;
    float fxLp_[2] = {0.0f, 0.0f};
    float fxLpCoeff_ = 0.2f;
    double fxTremPhase_ = 0.0;
    double fxChorusPhase_ = 0.0;
    double fxTremInc_ = 0.0;
    double fxChorusInc_ = 0.0;
    float fxChorusBase_ = 480.0f;
    float fxChorusDepth_ = 160.0f;
    std::array<float, 2048> fxDelay_[2]{};
    int fxDelayWrite_[2] = {0, 0};
    // Side B (Sound 2 / Keyboard B) independent FX: own params + filter/chorus
    // state, so Live Controls B shapes only Sound 2 (when it renders separately).
    std::atomic<float> fxToneB_{0.0f};
    std::atomic<float> fxDriveB_{0.0f};
    std::atomic<float> fxChorusB_{0.0f};
    std::atomic<float> fxTremB_{0.0f};
    std::atomic<float> fxSoftB_{0.0f};
    std::atomic<float> levelBCtl_{0.72f};   // side-B output level knob (0..1)
    float ftB_ = 0.0f, fdB_ = 0.0f, fcB_ = 0.0f, ftrB_ = 0.0f, fsB_ = 0.0f;
    float fxLpB_[2] = {0.0f, 0.0f};
    double fxTremPhaseB_ = 0.0;
    double fxChorusPhaseB_ = 0.0;
    std::array<float, 2048> fxDelayB_[2]{};
    int fxDelayWriteB_[2] = {0, 0};
    std::array<float, kInputBufferCapacity * 2> fxBufB_{};   // side-B render scratch (stereo)

    // Sustain pedal: defer note-offs by a small, controllable hold time so
    // released keys ring out and damp, instead of cutting abruptly.
    int64_t sampleClock_ = 0;
    int64_t pendingRelease_[128];
    static constexpr int64_t kSustainHeld = 0x4000000000000000LL;  // held by the pedal, no timeout (64-bit: `long` is 32-bit on some ABIs)
    std::atomic<bool> sustainPedal_{false};   // live MIDI CC64
    std::atomic<int> pitchWheel_{8192};        // live MIDI pitch bend, 0..16383 (8192 = center)
    int appliedPitchWheel_ = 8192;
    std::atomic<int> pitchWheelB_{8192};       // side B (Sound 2) bend
    int appliedPitchWheelB_ = 8192;
    int appliedBendRangeB_ = -1;
    std::atomic<float> vibratoDepthA_{0.0f};   // 0..1 vibrato lever, side A
    std::atomic<float> vibratoDepthB_{0.0f};   // side B
    float vibPhase_ = 0.0f;                     // shared LFO phase (audio thread)
    // MIDI expression: CC7 volume + CC11 expression routed to the active piano channel.
    std::atomic<int> ccVolume_{127};
    std::atomic<int> ccExpression_{127};
    int appliedCcVolume_ = -1;
    int appliedCcExpression_ = -1;

    // Adjustable drum room (replaces the old fixed send).
    std::atomic<float> drumRoom_{0.12f};

    // Metronome: a synthesized click mixed into the output on the beat.
    std::atomic<bool> metronomeOn_{false};
    std::atomic<int> metronomeBpm_{120};
    std::atomic<int> metroBeats_{4};
    std::atomic<bool> metroResetReq_{false};   // realign the click to beat 1 (count-in)
    int metroPhase_ = 0;
    int metroBeat_ = 0;

    // Recording: lock-free push of the final stereo mix to a ring drained by a
    // writer thread into a 16-bit PCM WAV file.
    std::atomic<bool> recording_{false};
    std::vector<float> recordRing_;
    std::atomic<size_t> recordWrite_{0};
    std::atomic<size_t> recordRead_{0};
    std::thread recordThread_;
    std::string recordPath_;

    // MIDI file player: a parsed SMF played through the active piano font.
    std::vector<SeqEvent> seq_;
    size_t seqPos_ = 0;
    double seqMs_ = 0.0;
    double seqTotalMs_ = 0.0;
    bool seqWasPlaying_ = false;
    std::atomic<double> seqPositionMs_{0.0};
    std::atomic<bool> seqPlaying_{false};
    std::atomic<bool> seqLoaded_{false};
    std::atomic<bool> seqLoop_{false};
    std::atomic<bool> seqFlushReq_{false};
    std::atomic<uint64_t> seqActiveLo_{0};   // MIDI-player held notes 0..63
    std::atomic<uint64_t> seqActiveHi_{0};   // MIDI-player held notes 64..127
    std::mutex seqMutex_;

    // Small feedback reverb (4 combs + 2 allpass, Freeverb-style), per channel
    // (L/R) with a stereo-spread on the right for width.
    float combBuf_[2][4][1800]{};
    int combLen_[2][4]{};
    int combIdx_[2][4]{};
    float combStore_[2][4]{};
    float apBuf_[2][2][700]{};
    int apLen_[2][2]{};
    int apIdx_[2][2]{};
    float reverbFeedback_ = 0.8f;
    float reverbDamp_ = 0.35f;
    std::atomic<bool> sustainOn_{false};
    std::atomic<bool> reverbOn_{false};
    std::atomic<float> sustainSeconds_{2.0f};
    std::atomic<float> reverbLevel_{0.30f};
    bool reverbWasOn_ = false;
};

std::mutex gEngineMutex;
std::unique_ptr<InstrumentalEngine> gEngine;

InstrumentalEngine &engine() {
    std::lock_guard<std::mutex> lock(gEngineMutex);
    if (!gEngine) {
        gEngine = std::make_unique<InstrumentalEngine>();
    }
    return *gEngine;
}

EngineConfig makeConfig(
        jint instrument,
        jint tone,
        jint inputRoute,
        jint inputDeviceId,
        jint outputDeviceId
) {
    EngineConfig config;
    config.instrument = static_cast<int>(instrument);
    config.tone = static_cast<int>(tone);
    config.inputRoute = static_cast<int>(inputRoute);
    config.inputDeviceId = static_cast<int>(inputDeviceId);
    config.outputDeviceId = static_cast<int>(outputDeviceId);
    return config;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeStart(
        JNIEnv *,
        jobject,
        jint instrument,
        jint tone,
        jint inputRoute,
        jint inputDeviceId,
        jint outputDeviceId
) {
    return engine().start(makeConfig(instrument, tone, inputRoute, inputDeviceId, outputDeviceId))
           ? JNI_TRUE
           : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeStop(JNIEnv *, jobject) {
    engine().stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetPreset(
        JNIEnv *,
        jobject,
        jint instrument,
        jint tone,
        jint inputRoute,
        jint inputDeviceId,
        jint outputDeviceId
) {
    engine().setPreset(makeConfig(instrument, tone, inputRoute, inputDeviceId, outputDeviceId));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeIsRunning(JNIEnv *, jobject) {
    return engine().isRunning() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeInputLevel(JNIEnv *, jobject) {
    return engine().inputLevelDb();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeOutputLatencyMs(JNIEnv *, jobject) {
    return engine().outputLatencyMs();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeOutputLevel(JNIEnv *, jobject) {
    return engine().outputLevelDb();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativePitchHz(JNIEnv *, jobject) {
    return engine().pitchHz();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeStatus(JNIEnv *env, jobject) {
    return env->NewStringUTF(engine().status().c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetControls(
        JNIEnv *,
        jobject,
        jfloat control1,
        jfloat control2,
        jfloat control3,
        jfloat control4,
        jfloat control5,
        jfloat control6
) {
    engine().setControls(
            static_cast<float>(control1),
            static_cast<float>(control2),
            static_cast<float>(control3),
            static_cast<float>(control4),
            static_cast<float>(control5),
            static_cast<float>(control6)
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeNoteOn(
        JNIEnv *,
        jobject,
        jint note,
        jfloat velocity
) {
    engine().noteOn(static_cast<int>(note), static_cast<float>(velocity));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeNoteOff(
        JNIEnv *,
        jobject,
        jint note
) {
    engine().noteOff(static_cast<int>(note));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeNote2On(
        JNIEnv *,
        jobject,
        jint note,
        jfloat velocity
) {
    engine().note2On(static_cast<int>(note), static_cast<float>(velocity));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeNote2Off(
        JNIEnv *,
        jobject,
        jint note
) {
    engine().note2Off(static_cast<int>(note));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoopKeyOn(
        JNIEnv *,
        jobject,
        jint note,
        jfloat velocity
) {
    engine().loopKeyOn(static_cast<int>(note), static_cast<float>(velocity));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoopKeyOff(
        JNIEnv *,
        jobject,
        jint note
) {
    engine().loopKeyOff(static_cast<int>(note));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLoopKeysProgram(
        JNIEnv *,
        jobject,
        jint program
) {
    engine().setLoopKeysProgram(static_cast<int>(program));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLoopKeysSlot(
        JNIEnv *,
        jobject,
        jint slot
) {
    engine().setLoopKeysSlot(static_cast<int>(slot));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetKeySplit(
        JNIEnv *,
        jobject,
        jint note
) {
    engine().setKeySplit(static_cast<int>(note));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLoopKeySplit(
        JNIEnv *,
        jobject,
        jint note
) {
    engine().setLoopKeySplit(static_cast<int>(note));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLoopKeysBend(
        JNIEnv *,
        jobject,
        jint value
) {
    engine().setLoopKeysBend(static_cast<int>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetDualFontSlot(
        JNIEnv *,
        jobject,
        jint slot
) {
    engine().setDualFontSlot(static_cast<int>(slot));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetBendRange(
        JNIEnv *,
        jobject,
        jint semis
) {
    engine().setBendRange(static_cast<int>(semis));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoopKey2On(
        JNIEnv *,
        jobject,
        jint note,
        jfloat velocity
) {
    engine().loopKey2On(static_cast<int>(note), static_cast<float>(velocity));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoopKey2Off(
        JNIEnv *,
        jobject,
        jint note
) {
    engine().loopKey2Off(static_cast<int>(note));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeAllNotesOff(
        JNIEnv *,
        jobject
) {
    engine().allNotesOff();
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetMidiLayer(
        JNIEnv *,
        jobject,
        jint program
) {
    engine().setMidiLayer(static_cast<int>(program));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLayer3(
        JNIEnv *, jobject, jint program) {
    engine().setLayer3(static_cast<int>(program));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLayer4(
        JNIEnv *, jobject, jint program) {
    engine().setLayer4(static_cast<int>(program));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLayer5(
        JNIEnv *, jobject, jint program) {
    engine().setLayer5(static_cast<int>(program));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLayer6(
        JNIEnv *, jobject, jint program) {
    engine().setLayer6(static_cast<int>(program));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLayer7(
        JNIEnv *, jobject, jint program) {
    engine().setLayer7(static_cast<int>(program));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLayer8(
        JNIEnv *, jobject, jint program) {
    engine().setLayer8(static_cast<int>(program));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLayerFontSlot(
        JNIEnv *, jobject, jint channel, jint slot) {
    engine().setLayerFontSlot(static_cast<int>(channel), static_cast<int>(slot));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLayerVolume(
        JNIEnv *, jobject, jint idx, jfloat vol) {
    engine().setLayerVolume(static_cast<int>(idx), static_cast<float>(vol));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLayerBlend(
        JNIEnv *, jobject, jint mode, jint note) {
    engine().setLayerBlend(static_cast<int>(mode), static_cast<int>(note));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLayerZap(
        JNIEnv *, jobject, jint ch, jint semis) {
    engine().setLayerZap(static_cast<int>(ch), static_cast<int>(semis));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeGetLayerMeters(
        JNIEnv *env, jobject, jfloatArray out) {
    if (out == nullptr || env->GetArrayLength(out) < 8) return;
    float m[8];
    engine().getLayerMeters(m);
    env->SetFloatArrayRegion(out, 0, 8, m);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLoopKeysLayer(
        JNIEnv *,
        jobject,
        jint program
) {
    engine().setLoopKeysLayer(static_cast<int>(program));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetWah(
        JNIEnv *,
        jobject,
        jboolean on
) {
    engine().setWah(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetWahPos(
        JNIEnv *,
        jobject,
        jfloat pos
) {
    engine().setWahPos(static_cast<float>(pos));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetGuitarCab(
        JNIEnv *,
        jobject,
        jboolean on,
        jint type,
        jfloat mix
) {
    engine().setGuitarCab(on == JNI_TRUE, static_cast<int>(type),
            static_cast<float>(mix));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetGuitarRackFx(
        JNIEnv *, jobject, jboolean compOn, jfloat compAmount,
        jboolean modOn, jfloat modRate, jfloat modDepth,
        jboolean delayOn, jfloat delayTime, jfloat delayFeedback, jfloat delayMix,
        jboolean roomOn, jfloat roomMix) {
    engine().setGuitarRackFx(compOn == JNI_TRUE, static_cast<float>(compAmount),
            modOn == JNI_TRUE, static_cast<float>(modRate), static_cast<float>(modDepth),
            delayOn == JNI_TRUE, static_cast<float>(delayTime),
            static_cast<float>(delayFeedback), static_cast<float>(delayMix),
            roomOn == JNI_TRUE, static_cast<float>(roomMix));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetBuiltInMetalRigFx(
        JNIEnv *, jobject, jint style, jfloat drive, jfloat tone, jfloat level,
        jfloat delayTime, jfloat delayFeedback, jfloat delayMix) {
    engine().setBuiltInMetalRigFx(static_cast<int>(style),
            static_cast<float>(drive), static_cast<float>(tone),
            static_cast<float>(level), static_cast<float>(delayTime),
            static_cast<float>(delayFeedback), static_cast<float>(delayMix));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetMonoOutput(
        JNIEnv *,
        jobject,
        jboolean on
) {
    engine().setMonoOutput(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeTriggerChimes(
        JNIEnv *,
        jobject
) {
    engine().triggerChimes();
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeTriggerSwell(
        JNIEnv *, jobject, jint index) {
    engine().triggerSwell(static_cast<int>(index));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeDrumKitReady(
        JNIEnv *,
        jobject
) {
    return engine().drumKitReady() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeDrumNoteHasSound(
        JNIEnv *,
        jobject,
        jint note
) {
    return engine().drumNoteHasSound(static_cast<int>(note)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoadChimeSample(
        JNIEnv *env,
        jobject,
        jfloatArray data,
        jint frames,
        jint channels,
        jint rate
) {
    if (data == nullptr) return;
    jfloat *buf = env->GetFloatArrayElements(data, nullptr);
    if (buf == nullptr) return;
    engine().loadChimeSample(buf, static_cast<int>(frames),
            static_cast<int>(channels), static_cast<int>(rate));
    env->ReleaseFloatArrayElements(data, buf, JNI_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoadSwellSample(
        JNIEnv *env, jobject, jint index, jfloatArray data, jint frames, jint channels, jint rate) {
    if (data == nullptr) return;
    jfloat *buf = env->GetFloatArrayElements(data, nullptr);
    if (buf == nullptr) return;
    engine().loadSwellSample(static_cast<int>(index), buf, static_cast<int>(frames),
            static_cast<int>(channels), static_cast<int>(rate));
    env->ReleaseFloatArrayElements(data, buf, JNI_ABORT);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoadSoundFont(
        JNIEnv *env,
        jobject,
        jbyteArray data
) {
    if (data == nullptr) {
        return JNI_FALSE;
    }
    jsize length = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) {
        return JNI_FALSE;
    }
    engine().loadSoundFont(bytes, static_cast<int>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return engine().hasSoundFont() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoadMidi(
        JNIEnv *env, jobject, jbyteArray data
) {
    if (data == nullptr) return JNI_FALSE;
    jsize length = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return JNI_FALSE;
    bool ok = engine().loadMidi(bytes, static_cast<int>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeMidiPlay(JNIEnv *, jobject) {
    engine().midiPlay();
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeMidiPause(JNIEnv *, jobject) {
    engine().midiPause();
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeMidiStop(JNIEnv *, jobject) {
    engine().midiStop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeMidiSetLoop(JNIEnv *, jobject, jboolean on) {
    engine().midiSetLoop(on == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeMidiIsPlaying(JNIEnv *, jobject) {
    return engine().midiIsPlaying() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeMidiPositionMs(JNIEnv *, jobject) {
    return engine().midiPositionMs();
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeMidiDurationMs(JNIEnv *, jobject) {
    return engine().midiDurationMs();
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeMidiActiveLow(JNIEnv *, jobject) {
    return static_cast<jlong>(engine().midiActiveLow());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeMidiActiveHigh(JNIEnv *, jobject) {
    return static_cast<jlong>(engine().midiActiveHigh());
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoopCommand(
        JNIEnv *, jobject, jint track, jint cmd
) {
    engine().loopCommand(static_cast<int>(track), static_cast<int>(cmd));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoopState(JNIEnv *, jobject, jint track) {
    return static_cast<jint>(engine().loopState(static_cast<int>(track)));
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoopPos(JNIEnv *, jobject, jint track) {
    return engine().loopPosNorm(static_cast<int>(track));
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoopLenMs(JNIEnv *, jobject, jint track) {
    return engine().loopLenMs(static_cast<int>(track));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoopLastOverdub(JNIEnv *, jobject) {
    return static_cast<jint>(engine().loopLastOverdub());
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoopGlobal(JNIEnv *, jobject, jint cmd) {
    engine().loopGlobal(static_cast<int>(cmd));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLoopMonitor(JNIEnv *, jobject, jboolean on) {
    engine().setLoopMonitor(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoopWave(
        JNIEnv *env, jobject, jint track, jfloatArray out
) {
    jsize n = env->GetArrayLength(out);
    if (n <= 0 || n > 256) return;
    float tmp[256];
    engine().loopWave(static_cast<int>(track), tmp, static_cast<int>(n));
    env->SetFloatArrayRegion(out, 0, n, tmp);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetHarmonizer(JNIEnv *, jobject, jboolean on) {
    engine().setHarmonizer(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetInputMute(JNIEnv *, jobject, jboolean mute) {
    engine().setInputMute(mute == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetAutotune(JNIEnv *, jobject, jboolean on) {
    engine().setAutotune(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetVocalReverb(JNIEnv *, jobject, jfloat level) {
    engine().setVocalReverb(static_cast<float>(level));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetPianoGlide(JNIEnv *, jobject, jboolean on) {
    engine().setPianoGlide(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetPianoGlideRate(JNIEnv *, jobject, jfloat rate) {
    engine().setPianoGlideRate(static_cast<float>(rate));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLoopKeysGlide(JNIEnv *, jobject, jboolean on) {
    engine().setLoopKeysGlide(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetPianoGlideMono(JNIEnv *, jobject, jboolean on) {
    engine().setPianoGlideMono(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLoopKeysGlideMono(JNIEnv *, jobject, jboolean on) {
    engine().setLoopKeysGlideMono(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetGkPoly(JNIEnv *, jobject, jboolean on) {
    engine().setGkPoly(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetGkTranspose(JNIEnv *, jobject, jint semis) {
    engine().setGkTranspose(static_cast<int>(semis));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetGkBendFollow(JNIEnv *, jobject, jboolean on) {
    engine().setGkBendFollow(on == JNI_TRUE);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeGkNotes(JNIEnv *, jobject) {
    return static_cast<jlong>(engine().gkNotesMask());
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLoopInstDevice(JNIEnv *, jobject, jint deviceId) {
    engine().setLoopInstDevice(static_cast<int>(deviceId));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLoopRecBars(JNIEnv *, jobject, jint track, jint bars) {
    engine().setLoopRecBars(static_cast<int>(track), static_cast<int>(bars));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetHarmonizerParams(
        JNIEnv *, jobject, jfloat semi1, jfloat semi2, jboolean choir,
        jfloat level, jint tone, jboolean reverb
) {
    engine().setHarmonizerParams(
            static_cast<float>(semi1), static_cast<float>(semi2), choir == JNI_TRUE,
            static_cast<float>(level), static_cast<int>(tone), reverb == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoopSave(
        JNIEnv *env, jobject, jint track, jstring path
) {
    const char *chars = env->GetStringUTFChars(path, nullptr);
    std::string p(chars == nullptr ? "" : chars);
    if (chars != nullptr) env->ReleaseStringUTFChars(path, chars);
    return engine().loopSaveWav(static_cast<int>(track), p) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetMidiProgram(
        JNIEnv *,
        jobject,
        jint program
) {
    engine().setMidiProgram(static_cast<int>(program));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetDrumKit(
        JNIEnv *,
        jobject,
        jint kit
) {
    engine().setDrumKit(static_cast<int>(kit));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetDrumRemap(
        JNIEnv *,
        jobject,
        jint id
) {
    engine().setDrumRemap(static_cast<int>(id));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetCustomDrum(
        JNIEnv *,
        jobject,
        jboolean on
) {
    engine().setCustomDrum(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetDrumPieceSlot(
        JNIEnv *,
        jobject,
        jint note,
        jint slot
) {
    engine().setDrumPieceSlot(static_cast<int>(note), static_cast<int>(slot));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetDrumPieceSrcNote(
        JNIEnv *,
        jobject,
        jint note,
        jint srcNote
) {
    engine().setDrumPieceSrcNote(static_cast<int>(note), static_cast<int>(srcNote));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativePreviewDrum(
        JNIEnv *,
        jobject,
        jint slot,
        jint note
) {
    engine().previewDrum(static_cast<int>(slot), static_cast<int>(note));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetDrumPieceGain(
        JNIEnv *,
        jobject,
        jint note,
        jfloat gain
) {
    engine().setDrumPieceGain(static_cast<int>(note), static_cast<float>(gain));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetDrumPiecePan(
        JNIEnv *,
        jobject,
        jint note,
        jfloat pan
) {
    engine().setDrumPiecePan(static_cast<int>(note), static_cast<float>(pan));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetSustain(
        JNIEnv *,
        jobject,
        jboolean on
) {
    engine().setSustain(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetSustainPedal(
        JNIEnv *,
        jobject,
        jboolean down
) {
    engine().setSustainPedal(down == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetPitchWheel(
        JNIEnv *,
        jobject,
        jint value
) {
    engine().setPitchWheel(static_cast<int>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetPitchWheelA(
        JNIEnv *, jobject, jint value) {
    engine().setPitchWheelA(static_cast<int>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetPitchWheelB(
        JNIEnv *, jobject, jint value) {
    engine().setPitchWheelB(static_cast<int>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetVibratoA(
        JNIEnv *, jobject, jfloat depth) {
    engine().setVibratoA(static_cast<float>(depth));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetVibratoB(
        JNIEnv *, jobject, jfloat depth) {
    engine().setVibratoB(static_cast<float>(depth));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetVibrato(
        JNIEnv *, jobject, jfloat depth) {
    engine().setVibrato(static_cast<float>(depth));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetReverb(
        JNIEnv *,
        jobject,
        jboolean on
) {
    engine().setReverb(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetSustainTime(
        JNIEnv *,
        jobject,
        jfloat seconds
) {
    engine().setSustainTime(static_cast<float>(seconds));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetReverbLevel(
        JNIEnv *,
        jobject,
        jfloat level
) {
    engine().setReverbLevel(static_cast<float>(level));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetDrumRoom(
        JNIEnv *, jobject, jfloat level
) {
    engine().setDrumRoom(static_cast<float>(level));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetCymbalGain(
        JNIEnv *, jobject, jint group, jfloat gain
) {
    engine().setCymbalGain(static_cast<int>(group), static_cast<float>(gain));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetNoiseGate(
        JNIEnv *, jobject, jfloat threshold
) {
    engine().setNoiseGate(static_cast<float>(threshold));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeChokeCymbals(JNIEnv *, jobject) {
    engine().chokeCymbals();
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetMidiVolume(
        JNIEnv *, jobject, jint value
) {
    engine().setMidiVolume(static_cast<int>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetMidiExpression(
        JNIEnv *, jobject, jint value
) {
    engine().setMidiExpression(static_cast<int>(value));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetMetronome(
        JNIEnv *, jobject, jboolean on, jint bpm, jint beatsPerBar
) {
    engine().setMetronome(on == JNI_TRUE, static_cast<int>(bpm), static_cast<int>(beatsPerBar));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeResetMetronome(JNIEnv *, jobject) {
    engine().resetMetronome();
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeStartRecording(
        JNIEnv *env, jobject, jstring path
) {
    const char *chars = env->GetStringUTFChars(path, nullptr);
    if (chars != nullptr) {
        engine().recordStart(std::string(chars));
        env->ReleaseStringUTFChars(path, chars);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeStopRecording(
        JNIEnv *, jobject
) {
    engine().recordStop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeIsRecording(
        JNIEnv *, jobject
) {
    return engine().isRecordingActive() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoadHqFont(
        JNIEnv *env,
        jobject,
        jint slot,
        jint presetNumber,
        jfloat gainDb,
        jbyteArray data
) {
    if (data == nullptr) {
        return JNI_FALSE;
    }
    jsize length = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) {
        return JNI_FALSE;
    }
    bool loaded = engine().loadHqFont(
            static_cast<int>(slot), static_cast<int>(presetNumber),
            static_cast<float>(gainDb), bytes, static_cast<int>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeHqFontPresetNames(
        JNIEnv *env, jobject, jint slot
) {
    int count = engine().hqFontPresetCount(static_cast<int>(slot));
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(count, stringClass, nullptr);
    if (result == nullptr) return nullptr;
    for (int i = 0; i < count; ++i) {
        std::string name = engine().hqFontPresetName(static_cast<int>(slot), i);
        jstring value = env->NewStringUTF(name.c_str());
        if (value != nullptr) {
            env->SetObjectArrayElement(result, i, value);
            env->DeleteLocalRef(value);
        }
    }
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetHqFontPreset(
        JNIEnv *, jobject, jint slot, jint presetIndex
) {
    return engine().setHqFontPreset(
            static_cast<int>(slot), static_cast<int>(presetIndex))
            ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetFontSlot(
        JNIEnv *,
        jobject,
        jint slot
) {
    engine().setFontSlot(static_cast<int>(slot));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoadDrumFont(
        JNIEnv *env,
        jobject,
        jint slot,
        jfloat gainDb,
        jbyteArray data
) {
    if (data == nullptr) {
        return JNI_FALSE;
    }
    jsize length = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) {
        return JNI_FALSE;
    }
    bool loaded = engine().loadDrumFont(static_cast<int>(slot), static_cast<float>(gainDb),
            bytes, static_cast<int>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetPianoFx(
        JNIEnv *,
        jobject,
        jfloat tone,
        jfloat drive,
        jfloat chorus,
        jfloat trem
) {
    engine().setPianoFx(
            static_cast<float>(tone),
            static_cast<float>(drive),
            static_cast<float>(chorus),
            static_cast<float>(trem)
    );
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetPianoSoft(
        JNIEnv *, jobject, jfloat soft) {
    engine().setPianoSoft(static_cast<float>(soft));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetPianoFxB(
        JNIEnv *, jobject, jfloat tone, jfloat drive, jfloat chorus, jfloat trem) {
    engine().setPianoFxB(static_cast<float>(tone), static_cast<float>(drive),
                         static_cast<float>(chorus), static_cast<float>(trem));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetPianoGuitarRig(
        JNIEnv *, jobject, jboolean onA, jboolean onB, jint amp, jint cab,
        jfloat drive, jfloat tone, jfloat harmonics) {
    engine().setPianoGuitarRig(onA == JNI_TRUE, onB == JNI_TRUE,
            static_cast<int>(amp), static_cast<int>(cab),
            static_cast<float>(drive), static_cast<float>(tone),
            static_cast<float>(harmonics));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetVirtualGuitarPlayer(
        JNIEnv *, jobject, jboolean on) {
    engine().setVirtualGuitarPlayer(on == JNI_TRUE);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoadNamModel(
        JNIEnv *env, jobject, jbyteArray data) {
    if (data == nullptr) return JNI_FALSE;
    jsize length = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);
    if (bytes == nullptr) return JNI_FALSE;
    bool loaded = engine().loadNamModel(bytes, static_cast<int>(length));
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetNam(
        JNIEnv *, jobject, jboolean on, jfloat mix, jfloat inputGain,
        jfloat outputGain) {
    engine().setNam(on == JNI_TRUE, static_cast<float>(mix),
            static_cast<float>(inputGain), static_cast<float>(outputGain));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeNamReady(
        JNIEnv *, jobject) {
    return engine().namReady() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeNamExpectedRate(
        JNIEnv *, jobject) {
    return engine().namExpectedRate();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeLoadNamIr(
        JNIEnv *env, jobject, jfloatArray data, jint frames, jint channels, jint rate) {
    if (data == nullptr) return JNI_FALSE;
    jfloat *samples = env->GetFloatArrayElements(data, nullptr);
    if (samples == nullptr) return JNI_FALSE;
    bool loaded = engine().loadNamIr(samples, static_cast<int>(frames),
            static_cast<int>(channels), static_cast<int>(rate));
    env->ReleaseFloatArrayElements(data, samples, JNI_ABORT);
    return loaded ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetNamIr(
        JNIEnv *, jobject, jboolean on) {
    engine().setNamIr(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetNamIrLevel(
        JNIEnv *, jobject, jfloat level) {
    engine().setNamIrLevel(level);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetVirtualGuitarMode(
        JNIEnv *, jobject, jboolean on) {
    engine().setVirtualGuitarMode(on == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetVirtualGuitarOutput(
        JNIEnv *, jobject, jfloat level) {
    engine().setVirtualGuitarOutput(level);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeNamIrReady(
        JNIEnv *, jobject) {
    return engine().namIrReady() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetPianoSoftB(
        JNIEnv *, jobject, jfloat soft) {
    engine().setPianoSoftB(static_cast<float>(soft));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetLevelB(
        JNIEnv *, jobject, jfloat lvl) {
    engine().setLevelB(static_cast<float>(lvl));
}

extern "C" JNIEXPORT void JNICALL
Java_com_instrumental_attachment_NativeAudioEngine_nativeSetManualSplit(
        JNIEnv *, jobject, jboolean on) {
    engine().setManualSplit(on == JNI_TRUE);
}
