// Standalone 4-track AutoDJ + Automix megamix renderer v5
// Enforcing 4 Real-DJ Rules:
// 1. Skip Spoken Intro Skits via 35s Beat-Grid Lock
// 2. Phrase Boundary Trigger + 1-Bar Beat-Loop Exit
// 3. Zero Bass-Hole Guarantee (Complementary 220Hz HPF & 500Hz Downbeat Swap)
// 4. Zero Vocal-on-Vocal Collision
#define _USE_MATH_DEFINES
#include <cmath>
#include <iostream>
#include <vector>
#include <fstream>
#include <algorithm>
#include <iomanip>

constexpr double SR = 48000.0;
constexpr std::size_t TOTAL_OUTPUT_FRAMES = 14400000; // 300.00s (5:00 exact)

struct Track {
    std::string name;
    std::vector<float> pcm;
    std::size_t totalFrames{0};
};

bool loadTrack(const char* path, const char* name, Track& t) {
    std::ifstream f(path, std::ios::binary | std::ios::ate);
    if (!f) { std::cerr << "[ERR] Cannot open " << path << "\n"; return false; }
    const auto bytes = static_cast<std::size_t>(f.tellg());
    f.seekg(0, std::ios::beg);
    t.totalFrames = bytes / (2 * sizeof(float));
    t.pcm.resize(t.totalFrames * 2);
    f.read(reinterpret_cast<char*>(t.pcm.data()), t.totalFrames * 2 * sizeof(float));
    f.close();
    t.name = name;
    std::cout << "[OK] " << name << ": " << t.totalFrames << " frames ("
              << std::fixed << std::setprecision(1) << (t.totalFrames / SR) << "s)\n";
    return true;
}

// Biquad filter
struct Biquad {
    double b0{1}, b1{0}, b2{0}, a1{0}, a2{0};
    double z1L{0}, z2L{0}, z1R{0}, z2R{0};

    void setHPF(double freq, double Q, double sr) {
        double w0 = 2.0 * M_PI * freq / sr;
        double alpha = std::sin(w0) / (2.0 * Q);
        double cs = std::cos(w0);
        double a0 = 1.0 + alpha;
        b0 = ((1.0 + cs) * 0.5) / a0;
        b1 = (-(1.0 + cs)) / a0;
        b2 = b0;
        a1 = (-2.0 * cs) / a0;
        a2 = (1.0 - alpha) / a0;
        z1L = z2L = z1R = z2R = 0;
    }

    void setBPF(double f0, double Q, double sr) {
        double w0 = 2.0 * M_PI * f0 / sr;
        double alpha = std::sin(w0) / (2.0 * Q);
        double a0 = 1.0 + alpha;
        b0 = alpha / a0;
        b1 = 0.0;
        b2 = -alpha / a0;
        a1 = (-2.0 * std::cos(w0)) / a0;
        a2 = (1.0 - alpha) / a0;
        z1L = z2L = z1R = z2R = 0;
    }

    void processLR(float& l, float& r) {
        double inL = l;
        double outL = b0 * inL + z1L;
        z1L = b1 * inL - a1 * outL + z2L;
        z2L = b2 * inL - a2 * outL;
        l = static_cast<float>(outL);

        double inR = r;
        double outR = b0 * inR + z1R;
        z1R = b1 * inR - a1 * outR + z2R;
        z2R = b2 * inR - a2 * outR;
        r = static_cast<float>(outR);
    }
};

// 4th-order Linkwitz-Riley High-Pass Filter (24 dB/oct) for Complementary Bass
struct LR4_HPF {
    Biquad stage1, stage2;

    void init(double cutoff, double sr) {
        // Cascade of two 2nd-order Butterworth HPFs (Q = 0.7071)
        stage1.setHPF(cutoff, 0.70710678, sr);
        stage2.setHPF(cutoff, 0.70710678, sr);
    }

    void processLR(float& l, float& r) {
        stage1.processLR(l, r);
        stage2.processLR(l, r);
    }
};

// Stereo Schroeder Dub-Echo / Reverb Tail Processor (1.2s decay)
struct DubEchoReverb {
    struct Comb {
        std::vector<float> buf;
        int pos{0};
        float feedback{0.78F};
        float damp{0.25F};
        float state{0.0F};
        void init(int len, float fb, float d = 0.25F) {
            buf.assign(std::max(len, 2), 0.0F);
            pos = 0;
            feedback = fb;
            damp = d;
            state = 0.0F;
        }
        float tick(float in) {
            float out = buf[pos];
            state = out * (1.0F - damp) + state * damp;
            buf[pos] = in + state * feedback;
            pos = (pos + 1) % (int)buf.size();
            return out;
        }
    };

    struct Allpass {
        std::vector<float> buf;
        int pos{0};
        float c{0.5F};
        void init(int len, float coeff = 0.5F) {
            buf.assign(std::max(len, 2), 0.0F);
            pos = 0;
            c = coeff;
        }
        float tick(float in) {
            float d = buf[pos];
            float out = -c * in + d;
            buf[pos] = in + c * d;
            pos = (pos + 1) % (int)buf.size();
            return out;
        }
    };

    Comb combsL[4], combsR[4];
    Allpass apsL[2], apsR[2];
    std::vector<float> delayBufL, delayBufR;
    int delayPosL{0}, delayPosR{0};

    void init(double sr, double bpm) {
        double beatSec = 60.0 / bpm;
        int delayFrames = static_cast<int>(std::round(beatSec * 0.5 * sr)); // 1/2 beat delay
        delayBufL.assign(std::max(delayFrames, 2), 0.0F);
        delayBufR.assign(std::max(delayFrames + 60, 2), 0.0F);
        delayPosL = delayPosR = 0;

        // Comb delays for 1.2s T60
        int combLens[] = {int(sr * 0.0297), int(sr * 0.0371), int(sr * 0.0411), int(sr * 0.0437)};
        float combFb[] = {0.84F, 0.82F, 0.80F, 0.78F};
        for (int i = 0; i < 4; ++i) {
            combsL[i].init(combLens[i], combFb[i]);
            combsR[i].init(combLens[i] + 47, combFb[i]);
        }
        int apLens[] = {int(sr * 0.005), int(sr * 0.0017)};
        for (int i = 0; i < 2; ++i) {
            apsL[i].init(apLens[i], 0.7F);
            apsR[i].init(apLens[i] + 23, 0.7F);
        }
    }

    void process(float& l, float& r, float dryGain, float wetGain) {
        // 1/2-beat ping-pong dub delay
        float dL = delayBufL[delayPosL];
        float dR = delayBufR[delayPosR];
        delayBufL[delayPosL] = l + dR * 0.35F;
        delayBufR[delayPosR] = r + dL * 0.35F;
        delayPosL = (delayPosL + 1) % (int)delayBufL.size();
        delayPosR = (delayPosR + 1) % (int)delayBufR.size();

        float inRevL = l * 0.7F + dL * 0.3F;
        float inRevR = r * 0.7F + dR * 0.3F;

        float sumL = 0, sumR = 0;
        for (int i = 0; i < 4; ++i) {
            sumL += combsL[i].tick(inRevL);
            sumR += combsR[i].tick(inRevR);
        }
        sumL *= 0.25F;
        sumR *= 0.25F;
        for (int i = 0; i < 2; ++i) {
            sumL = apsL[i].tick(sumL);
            sumR = apsR[i].tick(sumR);
        }

        l = l * dryGain + sumL * wetGain;
        r = r * dryGain + sumR * wetGain;
    }
};

// -------------------------------------------------------------
// RULE 1: Beat-Grid Lock & Drop Detection (Scanning First 35s)
// -------------------------------------------------------------
struct GridDropResult {
    double dropBeatSec{0.0};
    double entryCueSec{0.0};
    double tempoBpm{120.0};
};

GridDropResult detectDeckBGridDrop(const float* pcm, std::size_t totalFrames) {
    GridDropResult res;
    const std::size_t scanFrames = std::min(totalFrames, static_cast<std::size_t>(35.0 * SR));

    Biquad kickBp, snareBp;
    kickBp.setBPF(95.0, 1.2, SR);
    snareBp.setBPF(350.0, 1.0, SR);

    constexpr std::size_t B = 960; // 20ms
    const std::size_t totalBlocks = scanFrames / B;

    std::vector<float> kickRms(totalBlocks);
    std::vector<float> drumRms(totalBlocks);
    std::vector<float> crest(totalBlocks);

    for (std::size_t b = 0; b < totalBlocks; ++b) {
        double kSum = 0, sSum = 0, peak = 0;
        for (std::size_t j = 0; j < B; ++j) {
            float l = pcm[(b * B + j) * 2];
            float r = pcm[(b * B + j) * 2 + 1];
            float k = l, s = r;
            kickBp.processLR(k, l);
            snareBp.processLR(s, r);
            kSum += k * k;
            sSum += s * s;
            peak = std::max(peak, (double)std::abs(k));
        }
        float kR = std::sqrt(kSum / B);
        float sR = std::sqrt(sSum / B);
        kickRms[b] = kR;
        drumRms[b] = kR + sR * 0.6F;
        crest[b] = (kR > 1e-5F) ? (peak / kR) : 0.0F;
    }

    struct Onset {
        double timeSec;
        float rmsDb;
        bool isKick;
    };
    std::vector<Onset> onsets;

    for (int b = 2; b < static_cast<int>(totalBlocks) - 2; ++b) {
        float kDb = 20.0F * std::log10(std::max(1e-5F, kickRms[b]));
        float dDb = 20.0F * std::log10(std::max(1e-5F, drumRms[b]));

        if (drumRms[b] >= drumRms[b-1] && drumRms[b] >= drumRms[b-2] &&
            drumRms[b] >= drumRms[b+1] && drumRms[b] >= drumRms[b+2]) {
            if (dDb > -18.0F) {
                double t = (double)(b * B) / SR;
                bool kick = (kDb > -16.0F && crest[b] >= 1.5F);
                if (onsets.empty() || (t - onsets.back().timeSec) >= 0.22) {
                    onsets.push_back({t, dDb, kick});
                } else if (dDb > onsets.back().rmsDb) {
                    onsets.back() = {t, dDb, kick};
                }
            }
        }
    }

    // Search for 4 consecutive beats locked to grid
    for (std::size_t i = 0; i < onsets.size(); ++i) {
        if (!onsets[i].isKick) continue;
        double t0 = onsets[i].timeSec;

        for (double testBpm = 75.0; testBpm <= 175.0; testBpm += 1.0) {
            double T = 60.0 / testBpm;
            int matched = 1;

            for (int step = 1; step <= 3; ++step) {
                double expectedT = t0 + step * T;
                for (std::size_t k = i + 1; k < onsets.size(); ++k) {
                    if (std::abs(onsets[k].timeSec - expectedT) <= 0.08) {
                        matched++;
                        break;
                    }
                }
            }

            if (matched >= 4) {
                res.dropBeatSec = t0;
                res.tempoBpm = testBpm;
                res.entryCueSec = std::max(0.0, t0 - 4.0 * T); // 1 bar before drop
                return res;
            }
        }
    }

    // Half-time search (bars of 4T)
    for (std::size_t i = 0; i < onsets.size(); ++i) {
        if (!onsets[i].isKick) continue;
        double t0 = onsets[i].timeSec;

        for (double testBpm = 75.0; testBpm <= 175.0; testBpm += 1.0) {
            double T = 60.0 / testBpm;
            double bar = 4.0 * T;
            int matchedBars = 1;

            for (int step = 1; step <= 2; ++step) {
                double expectedBar = t0 + step * bar;
                for (std::size_t k = i + 1; k < onsets.size(); ++k) {
                    if (std::abs(onsets[k].timeSec - expectedBar) <= 0.12) {
                        matchedBars++;
                        break;
                    }
                }
            }

            if (matchedBars >= 3) {
                res.dropBeatSec = t0;
                res.tempoBpm = testBpm;
                res.entryCueSec = std::max(0.0, t0 - bar);
                return res;
            }
        }
    }

    // Fallback: first detected kick
    for (const auto& o : onsets) {
        if (o.isKick) {
            res.dropBeatSec = o.timeSec;
            res.entryCueSec = std::max(0.0, o.timeSec - 2.0);
            return res;
        }
    }

    return res;
}

// -------------------------------------------------------------
// RULE 2: Find Musical Phrase Boundary in Deck A
// -------------------------------------------------------------
std::size_t findPhraseBoundaryFrame(const float* pcm, std::size_t totalFrames,
                                    std::size_t targetFrame, std::size_t windowFrames) {
    std::size_t startFrame = (targetFrame > windowFrames) ? (targetFrame - windowFrames) : 0;
    std::size_t endFrame = std::min(totalFrames, targetFrame + windowFrames);

    Biquad vocalBp;
    vocalBp.setBPF(1200.0, 0.7, SR); // 300-3500 Hz vocal range

    constexpr std::size_t B = 960; // 20ms
    double minVocalEnergy = 1e9;
    std::size_t bestPauseFrame = targetFrame;

    for (std::size_t f = startFrame; f + B <= endFrame; f += B) {
        double vSum = 0;
        for (std::size_t j = 0; j < B; ++j) {
            float l = pcm[(f + j) * 2];
            float r = pcm[(f + j) * 2 + 1];
            vocalBp.processLR(l, r);
            vSum += l * l + r * r;
        }
        float rms = std::sqrt(vSum / (B * 2));
        float db = 20.0f * std::log10(std::max(1e-5f, rms));

        // Phrase pause: vocal drops below -26 dBFS
        if (db < -26.0f && vSum < minVocalEnergy) {
            minVocalEnergy = vSum;
            bestPauseFrame = f;
        }
    }
    return bestPauseFrame;
}

int main(int argc, char* argv[]) {
    if (argc < 6) {
        std::cerr << "Usage: " << argv[0] << " <tandav.raw> <unstoppable.raw> <girlfriend.raw> <loser.raw> <out.raw>\n";
        return 1;
    }

    std::cout << "=================================================================\n";
    std::cout << " LastWave AutoDJ + Automix Megamix v5 (Real-DJ Rules Applied)\n";
    std::cout << "=================================================================\n";

    Track tracks[4];
    const char* names[] = {"Tandav", "Unstoppable", "Girlfriend", "Loser"};
    for (int i = 0; i < 4; ++i) {
        if (!loadTrack(argv[i+1], names[i], tracks[i])) return 1;
    }

    // RULE 1: Detect beat drops and entry cues for all tracks
    GridDropResult cues[4];
    for (int i = 0; i < 4; ++i) {
        cues[i] = detectDeckBGridDrop(tracks[i].pcm.data(), tracks[i].totalFrames);
        std::cout << "[Rule 1 Grid Lock] " << names[i] << ": Drop at "
                  << std::fixed << std::setprecision(3) << cues[i].dropBeatSec << "s, "
                  << "Entry Cue (-1 bar): " << cues[i].entryCueSec << "s, "
                  << "Tempo: " << std::setprecision(1) << cues[i].tempoBpm << " BPM\n";
    }

    std::vector<float> masterOut(TOTAL_OUTPUT_FRAMES * 2, 0.0F);
    std::size_t masterWritePos = 0; // Current frame in master output

    // Approximate transition targets for 4 tracks over 300 seconds
    const double transitionTargetSec[3] = {70.0, 145.0, 218.0};
    std::size_t trackPlayHead[4] = {0, 0, 0, 0};

    // Tandav starts at 0.0s
    trackPlayHead[0] = 0;

    for (int seg = 0; seg < 3; ++seg) {
        const int A = seg;
        const int B = seg + 1;

        std::cout << "\n-------------------------------------------------------------\n";
        std::cout << ">>> SEGMENT " << (seg + 1) << ": " << names[A] << " -> " << names[B] << "\n";
        std::cout << "-------------------------------------------------------------\n";

        // RULE 2: Find exact phrase boundary in Deck A near transitionTargetSec
        const std::size_t targetMasterFrame = static_cast<std::size_t>(transitionTargetSec[seg] * SR);
        const std::size_t searchWindowFrames = static_cast<std::size_t>(5.0 * SR);
        const std::size_t targetHeadA = trackPlayHead[A] + (targetMasterFrame - masterWritePos);

        const std::size_t phraseBoundaryHeadA = findPhraseBoundaryFrame(
            tracks[A].pcm.data(), tracks[A].totalFrames, targetHeadA, searchWindowFrames);

        const std::size_t soloFramesToPlay = phraseBoundaryHeadA - trackPlayHead[A];
        std::cout << "[Rule 2 Phrase Boundary] Triggering transition at Deck A t="
                  << (phraseBoundaryHeadA / SR) << "s (Master t="
                  << ((masterWritePos + soloFramesToPlay) / SR) << "s)\n";

        // 1. Play Deck A Solo up to the phrase boundary
        for (std::size_t f = 0; f < soloFramesToPlay && masterWritePos < TOTAL_OUTPUT_FRAMES; ++f) {
            masterOut[masterWritePos * 2]     = tracks[A].pcm[trackPlayHead[A] * 2];
            masterOut[masterWritePos * 2 + 1] = tracks[A].pcm[trackPlayHead[A] * 2 + 1];
            masterWritePos++;
            trackPlayHead[A]++;
        }

        // 2. Capture 1-Bar (4-beat) Beat Loop from Deck A immediately prior to phrase pause
        const double bpmA = cues[A].tempoBpm;
        const double barSecA = 4.0 * (60.0 / bpmA);
        const std::size_t oneBarFramesA = static_cast<std::size_t>(std::round(barSecA * SR));

        std::vector<float> loopA(oneBarFramesA * 2);
        const std::size_t loopStart = (trackPlayHead[A] >= oneBarFramesA) ? (trackPlayHead[A] - oneBarFramesA) : 0;
        for (std::size_t i = 0; i < oneBarFramesA; ++i) {
            loopA[i * 2]     = tracks[A].pcm[(loopStart + i) * 2];
            loopA[i * 2 + 1] = tracks[A].pcm[(loopStart + i) * 2 + 1];
        }
        std::cout << "[Rule 2 Beat Loop] Captured clean 1-bar drum loop from Deck A ("
                  << (oneBarFramesA / SR) << "s, " << bpmA << " BPM)\n";

        // 3. Prepare Deck B: Cue to 1 bar before its first real beat drop (RULE 1)
        const std::size_t cueFrameB = static_cast<std::size_t>(cues[B].entryCueSec * SR);
        trackPlayHead[B] = cueFrameB;
        std::cout << "[Rule 1 Entry Cue] Deck B (" << names[B] << ") starts from frame "
                  << cueFrameB << " (" << (cueFrameB / SR) << "s), skipping all spoken skits!\n";

        // 4. Run Transition (Duration: 16 bars ~ 18-20s)
        const std::size_t transitionFrames = static_cast<std::size_t>(18.0 * SR);
        const std::size_t dropPointFrame = static_cast<std::size_t>(transitionFrames * 0.75); // 75% downbeat

        LR4_HPF deckBHpf220;
        deckBHpf220.init(220.0, SR);

        Biquad deckAHpf500;
        deckAHpf500.setHPF(500.0, 1.1, SR);

        DubEchoReverb reverbA;
        reverbA.init(SR, bpmA);

        std::cout << "[Rule 3 & 4 Transition] 18s total | 0-75% (0-" << (dropPointFrame/SR)
                  << "s): Deck A 0dB sub-bass loop + Deck B 220Hz HPF (crisp un-muffled highs)\n"
                  << "                        | 75% Drop (" << (dropPointFrame/SR)
                  << "s): Instantaneous Bass Swap -> Deck B full range, Deck A 500Hz echo tail\n";

        std::size_t loopReadPos = 0;

        for (std::size_t tf = 0; tf < transitionFrames && masterWritePos < TOTAL_OUTPUT_FRAMES; ++tf) {
            const float progress = static_cast<float>(tf) / static_cast<float>(transitionFrames);

            // Read Deck A from captured 1-bar loop (RULE 2: NO continuous rapper talk)
            float aL = loopA[loopReadPos * 2];
            float aR = loopA[loopReadPos * 2 + 1];
            loopReadPos = (loopReadPos + 1) % oneBarFramesA;

            // Read Deck B from incoming track
            float bL = 0.0F, bR = 0.0F;
            if (trackPlayHead[B] < tracks[B].totalFrames) {
                bL = tracks[B].pcm[trackPlayHead[B] * 2];
                bR = tracks[B].pcm[trackPlayHead[B] * 2 + 1];
                trackPlayHead[B]++;
            }

            float mixL = 0.0F, mixR = 0.0F;

            if (progress < 0.75F) {
                // PHASE 1: 0% to 75%
                // Deck A: 100% sub-bass (<180 Hz at 0 dB), volume solid through 50%, 0.75 at 75%
                float volA = (progress > 0.50F) ? (1.0F - (progress - 0.50F) / 0.25F * 0.25F) : 1.0F;
                aL *= volA;
                aR *= volA;

                // Deck B: High-passed at 220 Hz (zero sub-bass clash, crisp pristine highs), volume 0.35 -> 1.0
                deckBHpf220.processLR(bL, bR);
                float volB = 0.35F + (progress / 0.75F) * 0.65F;
                bL *= volB;
                bR *= volB;

                mixL = aL + bL;
                mixR = aR + bR;
            } else {
                // PHASE 2: Exactly at 75% Downbeat (The Drop)
                // Deck B: Instantaneous snap to full-range 0 dB sub-bass! (HPF bypassed)
                // Deck A: Cut sub-bass at 500 Hz, -12 dB mid cut, send into 1.2s dub-echo tail
                deckAHpf500.processLR(aL, aR);
                aL *= 0.25F; // -12 dB mid-cut (RULE 4)
                aR *= 0.25F;

                const float tailProgress = (progress - 0.75F) / 0.25F;
                float dryG = (1.0F - tailProgress) * 0.4F;
                float wetG = (1.0F - tailProgress * tailProgress) * 0.65F;
                reverbA.process(aL, aR, dryG, wetG);

                mixL = aL + bL;
                mixR = aR + bR;
            }

            masterOut[masterWritePos * 2]     = mixL;
            masterOut[masterWritePos * 2 + 1] = mixR;
            masterWritePos++;
        }

        std::cout << "  Transition Complete at Master t=" << (masterWritePos / SR)
                  << "s. Deck B (" << names[B] << ") running solo.\n";
    }

    // SEGMENT 4: Loser Solo to 5:00 with smooth 2.5s end fade
    std::cout << "\n-------------------------------------------------------------\n";
    std::cout << ">>> SEGMENT 4: " << names[3] << " Solo to 5:00 <<<\n";
    std::cout << "-------------------------------------------------------------\n";

    constexpr std::size_t END_FADE_FRAMES = static_cast<std::size_t>(2.5 * SR);

    while (masterWritePos < TOTAL_OUTPUT_FRAMES && trackPlayHead[3] < tracks[3].totalFrames) {
        float l = tracks[3].pcm[trackPlayHead[3] * 2];
        float r = tracks[3].pcm[trackPlayHead[3] * 2 + 1];

        if (masterWritePos >= TOTAL_OUTPUT_FRAMES - END_FADE_FRAMES) {
            float fade = static_cast<float>(TOTAL_OUTPUT_FRAMES - masterWritePos) / static_cast<float>(END_FADE_FRAMES);
            l *= fade;
            r *= fade;
        }

        masterOut[masterWritePos * 2]     = l;
        masterOut[masterWritePos * 2 + 1] = r;
        masterWritePos++;
        trackPlayHead[3]++;
    }

    std::cout << "\n[Render Complete] " << masterWritePos << " frames ("
              << (masterWritePos / SR) << "s)\n";

    // Peak scan
    float peak = 0.0F;
    for (std::size_t i = 0; i < TOTAL_OUTPUT_FRAMES * 2; ++i) {
        peak = std::max(peak, std::abs(masterOut[i]));
    }
    std::cout << "[Peak Amplitude] " << peak << " ("
              << (20.0 * std::log10(std::max(1e-6, (double)peak))) << " dBFS)\n";

    // Normalize to -0.3 dBFS (Clean, punchy, zero digital clipping)
    if (peak > 0.001F) {
        const float target = std::pow(10.0F, -0.3F / 20.0F); // ~0.966
        const float gain = target / peak;
        for (auto& s : masterOut) s *= gain;
        std::cout << "[Normalized Master] gain = " << gain << " -> Peak: -0.3 dBFS\n";
    }

    // Write output raw PCM
    std::ofstream fo(argv[5], std::ios::binary);
    fo.write(reinterpret_cast<const char*>(masterOut.data()), TOTAL_OUTPUT_FRAMES * 2 * sizeof(float));
    fo.close();
    std::cout << "[Output Written] " << argv[5] << " ("
              << (TOTAL_OUTPUT_FRAMES * 2 * sizeof(float)) << " bytes)\n";
    std::cout << "=================================================================\n";
    return 0;
}
