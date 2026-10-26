#define _USE_MATH_DEFINES
#include <cmath>
#include <iostream>
#include <vector>
#include <fstream>
#include <iomanip>

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

void scanPhraseBoundaries(const char* name, const char* path, double startSec, double endSec) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return;

    std::size_t startFrame = static_cast<std::size_t>(startSec * SR);
    std::size_t totalFrames = static_cast<std::size_t>((endSec - startSec) * SR);

    f.seekg(startFrame * 2 * sizeof(float), std::ios::beg);
    std::vector<float> pcm(totalFrames * 2);
    f.read(reinterpret_cast<char*>(pcm.data()), totalFrames * 2 * sizeof(float));
    std::size_t readFrames = f.gcount() / (2 * sizeof(float));

    Bandpass vocalBp, kickBp;
    vocalBp.init(1200.0, 0.7, SR); // 300-3500 Hz vocal
    kickBp.init(95.0, 1.2, SR);

    constexpr std::size_t B = 960; // 20ms
    std::cout << "\nScanning phrase boundaries for " << name << " (" << startSec << "s to " << endSec << "s):\n";

    for (std::size_t i = 0; i + B <= readFrames; i += B) {
        double vSum = 0, kSum = 0;
        for (std::size_t j = 0; j < B; ++j) {
            double mono = (pcm[(i + j) * 2] + pcm[(i + j) * 2 + 1]) * 0.5;
            double v = vocalBp.tick(mono);
            double k = kickBp.tick(mono);
            vSum += v * v;
            kSum += k * k;
        }
        float vRms = std::sqrt(vSum / B);
        float kRms = std::sqrt(kSum / B);
        float vDb = 20.0f * std::log10(std::max(1e-5f, vRms));
        float kDb = 20.0f * std::log10(std::max(1e-5f, kRms));

        double t = startSec + (double)i / SR;
        // Vocal pause detected when vocal energy drops below -28 dBFS while drum/beat is active
        if (vDb < -26.0f) {
            std::cout << "  Phrase Pause at t=" << std::fixed << std::setprecision(2) << t
                      << "s | Vocal: " << vDb << " dBFS, Beat: " << kDb << " dBFS\n";
            i += static_cast<std::size_t>(0.25 * SR); // skip ahead 250ms
        }
    }
}

int main() {
    scanPhraseBoundaries("Tandav", "c:/Users/Ajisth/.gemini/antigravity-ide/brain/ae9d614d-7e25-4bd4-975f-33e17060736a/scratch/tandav_48k.raw", 50.0, 80.0);
    scanPhraseBoundaries("Unstoppable", "c:/Users/Ajisth/.gemini/antigravity-ide/brain/ae9d614d-7e25-4bd4-975f-33e17060736a/scratch/unstoppable_48k.raw", 125.0, 155.0);
    scanPhraseBoundaries("Girlfriend", "c:/Users/Ajisth/.gemini/antigravity-ide/brain/ae9d614d-7e25-4bd4-975f-33e17060736a/scratch/girlfriend_48k.raw", 195.0, 225.0);
    return 0;
}
