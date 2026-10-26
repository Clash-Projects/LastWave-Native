#define _USE_MATH_DEFINES
#include <cmath>
#include <iostream>
#include <vector>
#include <fstream>
#include <iomanip>
#include <algorithm>

constexpr double SR = 48000.0;

struct Bandpass {
    double b0{0}, b1{0}, b2{0}, a1{0}, a2{0};
    double z1{0}, z2{0};

    void init(double f0, double Q, double sr) {
        double w0 = 2.0 * M_PI * f0 / sr;
        double alpha = std::sin(w0) / (2.0 * Q);
        double a0 = 1.0 + alpha;
        b0 = alpha / a0;
        b1 = 0.0;
        b2 = -alpha / a0;
        a1 = (-2.0 * std::cos(w0)) / a0;
        a2 = (1.0 - alpha) / a0;
        z1 = z2 = 0.0;
    }

    double tick(double in) {
        double out = b0 * in + z1;
        z1 = b1 * in - a1 * out + z2;
        z2 = b2 * in - a2 * out;
        return out;
    }
};

struct BeatDropResult {
    bool locked{false};
    double dropBeatTimeSec{0.0};
    double entryCueTimeSec{0.0};
    double tempoBpm{120.0};
    double beatIntervalSec{0.5};
};

BeatDropResult detectBeatDropAndCue(const float* pcm, std::size_t totalFrames) {
    BeatDropResult res;
    const std::size_t scanFrames = std::min(totalFrames, static_cast<std::size_t>(35.0 * SR));

    Bandpass kickBp, snareBp, vocalBp;
    kickBp.init(95.0, 1.2, SR);     // 60-130 Hz kick
    snareBp.init(350.0, 1.0, SR);   // 250-500 Hz snare/body
    vocalBp.init(1200.0, 0.7, SR);  // 300-3000 Hz vocal/speech

    constexpr std::size_t B = 960; // 20ms
    const std::size_t totalBlocks = scanFrames / B;

    std::vector<float> kickRms(totalBlocks);
    std::vector<float> drumRms(totalBlocks);
    std::vector<float> crest(totalBlocks);

    for (std::size_t b = 0; b < totalBlocks; ++b) {
        double kSum = 0, sSum = 0, peak = 0;
        for (std::size_t j = 0; j < B; ++j) {
            double mono = (pcm[(b * B + j) * 2] + pcm[(b * B + j) * 2 + 1]) * 0.5;
            double k = kickBp.tick(mono);
            double s = snareBp.tick(mono);
            kSum += k * k;
            sSum += s * s;
            peak = std::max(peak, std::abs(k));
        }
        float kR = std::sqrt(kSum / B);
        float sR = std::sqrt(sSum / B);
        kickRms[b] = kR;
        drumRms[b] = kR + sR * 0.6f;
        crest[b] = (kR > 1e-5f) ? (peak / kR) : 0.0f;
    }

    struct Onset {
        double timeSec;
        float rmsDb;
        bool isKick;
    };
    std::vector<Onset> onsets;

    for (int b = 2; b < (int)totalBlocks - 2; ++b) {
        float kDb = 20.0f * std::log10(std::max(1e-5f, kickRms[b]));
        float dDb = 20.0f * std::log10(std::max(1e-5f, drumRms[b]));

        // Local peak
        if (drumRms[b] >= drumRms[b-1] && drumRms[b] >= drumRms[b-2] &&
            drumRms[b] >= drumRms[b+1] && drumRms[b] >= drumRms[b+2]) {
            if (dDb > -18.0f) {
                double t = (double)(b * B) / SR;
                if (onsets.empty() || (t - onsets.back().timeSec) >= 0.22) {
                    onsets.push_back({t, dDb, (kDb > -16.0f && crest[b] >= 1.6f)});
                } else if (dDb > onsets.back().rmsDb) {
                    onsets.back() = {t, dDb, (kDb > -16.0f && crest[b] >= 1.6f)};
                }
            }
        }
    }

    // Find first 4 consecutive beats that form a grid
    // Check candidate tempos from 70 to 180 BPM (beatInterval 0.33 to 0.86s)
    for (size_t i = 0; i < onsets.size(); ++i) {
        if (!onsets[i].isKick) continue; // must start on a real kick transient
        double t0 = onsets[i].timeSec;

        // Try to find matching beats at interval T
        for (double testBpm = 75.0; testBpm <= 175.0; testBpm += 1.0) {
            double T = 60.0 / testBpm;
            int matchedBeats = 1;

            for (int step = 1; step <= 3; ++step) {
                double expectedT = t0 + step * T;
                bool foundMatch = false;
                for (size_t k = i + 1; k < onsets.size(); ++k) {
                    if (std::abs(onsets[k].timeSec - expectedT) <= 0.08) { // within 80ms
                        foundMatch = true;
                        break;
                    }
                }
                if (foundMatch) matchedBeats++;
            }

            if (matchedBeats >= 4) {
                res.locked = true;
                res.dropBeatTimeSec = t0;
                res.tempoBpm = testBpm;
                res.beatIntervalSec = T;
                // Cue 1 bar (4 beats) before the drop, clamped to 0
                res.entryCueTimeSec = std::max(0.0, t0 - 4.0 * T);
                return res;
            }
        }
    }

    // Fallback: If half-time kicks (e.g. 1 and 3)
    for (size_t i = 0; i < onsets.size(); ++i) {
        if (!onsets[i].isKick) continue;
        double t0 = onsets[i].timeSec;

        for (double testBpm = 75.0; testBpm <= 175.0; testBpm += 1.0) {
            double T = 60.0 / testBpm;
            double bar = 4.0 * T;
            int matchedBars = 1;

            for (int step = 1; step <= 2; ++step) {
                double expectedBar = t0 + step * bar;
                bool found = false;
                for (size_t k = i + 1; k < onsets.size(); ++k) {
                    if (std::abs(onsets[k].timeSec - expectedBar) <= 0.12) {
                        found = true;
                        break;
                    }
                }
                if (found) matchedBars++;
            }

            if (matchedBars >= 3) {
                res.locked = true;
                res.dropBeatTimeSec = t0;
                res.tempoBpm = testBpm;
                res.beatIntervalSec = T;
                res.entryCueTimeSec = std::max(0.0, t0 - bar);
                return res;
            }
        }
    }

    // Fallback: first kick
    for (auto& o : onsets) {
        if (o.isKick) {
            res.locked = true;
            res.dropBeatTimeSec = o.timeSec;
            res.entryCueTimeSec = std::max(0.0, o.timeSec - 2.0);
            return res;
        }
    }

    return res;
}

void testTrack(const char* name, const char* path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return;
    const std::size_t frames = static_cast<std::size_t>(35.0 * SR);
    std::vector<float> pcm(frames * 2);
    f.read(reinterpret_cast<char*>(pcm.data()), frames * 2 * sizeof(float));
    std::size_t r = f.gcount() / (2 * sizeof(float));

    auto res = detectBeatDropAndCue(pcm.data(), r);
    std::cout << "\n============================================\n";
    std::cout << "Track: " << name << "\n";
    std::cout << "  Locked: " << (res.locked ? "YES" : "NO") << "\n";
    std::cout << "  Drop Beat Time: " << std::fixed << std::setprecision(3) << res.dropBeatTimeSec << "s (" << (res.dropBeatTimeSec * 1000.0) << "ms)\n";
    std::cout << "  Tempo: " << std::setprecision(1) << res.tempoBpm << " BPM (Beat: " << (res.beatIntervalSec * 1000.0) << "ms)\n";
    std::cout << "  Deck B Cue (1-Bar Before Drop): " << std::setprecision(3) << res.entryCueTimeSec << "s (" << (res.entryCueTimeSec * 1000.0) << "ms)\n";
}

int main() {
    testTrack("Tandav", "c:/Users/Ajisth/.gemini/antigravity-ide/brain/ae9d614d-7e25-4bd4-975f-33e17060736a/scratch/tandav_48k.raw");
    testTrack("Unstoppable", "c:/Users/Ajisth/.gemini/antigravity-ide/brain/ae9d614d-7e25-4bd4-975f-33e17060736a/scratch/unstoppable_48k.raw");
    testTrack("Girlfriend", "c:/Users/Ajisth/.gemini/antigravity-ide/brain/ae9d614d-7e25-4bd4-975f-33e17060736a/scratch/girlfriend_48k.raw");
    testTrack("Loser", "c:/Users/Ajisth/.gemini/antigravity-ide/brain/ae9d614d-7e25-4bd4-975f-33e17060736a/scratch/loser_48k.raw");
    return 0;
}
