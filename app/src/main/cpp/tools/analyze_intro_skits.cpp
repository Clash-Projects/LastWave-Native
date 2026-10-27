#define _USE_MATH_DEFINES
#include <cmath>
#include <iostream>
#include <vector>
#include <fstream>
#include <iomanip>

constexpr double SR = 48000.0;

// Biquad Bandpass filter
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

void analyzeTrack(const char* name, const char* path) {
    std::ifstream f(path, std::ios::binary);
    if (!f) { std::cerr << "Cannot open " << path << "\n"; return; }

    std::cout << "\n============================================\n";
    std::cout << "Analyzing first 35s of: " << name << "\n";
    std::cout << "============================================\n";

    // Read first 35 seconds
    const std::size_t totalFrames = static_cast<std::size_t>(35.0 * SR);
    std::vector<float> pcm(totalFrames * 2);
    f.read(reinterpret_cast<char*>(pcm.data()), totalFrames * 2 * sizeof(float));
    std::size_t readBytes = f.gcount();
    std::size_t readFrames = readBytes / (2 * sizeof(float));

    Bandpass kickBp, vocalBp;
    kickBp.init(95.0, 1.2, SR); // 60-130 Hz sub-bass
    vocalBp.init(1200.0, 0.7, SR); // 300-3000 Hz vocal/speech

    // Analyze in 20ms blocks (960 frames)
    constexpr std::size_t B = 960;
    struct BlockStat {
        double timeSec;
        float kickRms;
        float vocalRms;
        float kickPeak;
        float crestFactor;
    };
    std::vector<BlockStat> stats;

    for (std::size_t i = 0; i + B <= readFrames; i += B) {
        double kickSum = 0, vocalSum = 0;
        double kickPeak = 0;
        for (std::size_t j = 0; j < B; ++j) {
            double mono = (pcm[(i + j) * 2] + pcm[(i + j) * 2 + 1]) * 0.5;
            double k = kickBp.tick(mono);
            double v = vocalBp.tick(mono);
            kickSum += k * k;
            vocalSum += v * v;
            kickPeak = std::max(kickPeak, std::abs(k));
        }
        float kRms = std::sqrt(kickSum / B);
        float vRms = std::sqrt(vocalSum / B);
        float crest = (kRms > 1e-5f) ? (kickPeak / kRms) : 0.0f;
        stats.push_back({(double)i / SR, kRms, vRms, (float)kickPeak, crest});
    }

    // Print blocks with significant energy
    for (size_t i = 0; i < stats.size(); i += 5) { // every 100ms
        float kDb = 20.0f * std::log10(std::max(1e-5f, stats[i].kickRms));
        float vDb = 20.0f * std::log10(std::max(1e-5f, stats[i].vocalRms));
        if (kDb > -35.0f || vDb > -35.0f) {
            std::cout << std::fixed << std::setprecision(2)
                      << "t=" << stats[i].timeSec << "s | Kick RMS: " << std::setw(6) << kDb << " dBFS, Peak: " << stats[i].kickPeak
                      << ", Crest: " << stats[i].crestFactor << " | Vocal RMS: " << std::setw(6) << vDb << " dBFS\n";
        }
    }
}

int main() {
    analyzeTrack("Tandav", "c:/Users/Ajisth/.gemini/antigravity-ide/brain/ae9d614d-7e25-4bd4-975f-33e17060736a/scratch/tandav_48k.raw");
    analyzeTrack("Unstoppable", "c:/Users/Ajisth/.gemini/antigravity-ide/brain/ae9d614d-7e25-4bd4-975f-33e17060736a/scratch/unstoppable_48k.raw");
    analyzeTrack("Girlfriend", "c:/Users/Ajisth/.gemini/antigravity-ide/brain/ae9d614d-7e25-4bd4-975f-33e17060736a/scratch/girlfriend_48k.raw");
    analyzeTrack("Loser", "c:/Users/Ajisth/.gemini/antigravity-ide/brain/ae9d614d-7e25-4bd4-975f-33e17060736a/scratch/loser_48k.raw");
    return 0;
}
