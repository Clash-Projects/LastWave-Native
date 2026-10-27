#include "../AutoMixEngine.h"
#include <iostream>
#include <vector>
#include <fstream>
#include <cmath>
#include <iomanip>
#include <algorithm>

using namespace lastwave::audio;

int main(int argc, char* argv[]) {
    if (argc < 4) {
        std::cerr << "Usage: " << argv[0] << " <deckA_raw_f32le> <deckB_raw_f32le> <output_raw_f32le>\n";
        return 1;
    }

    const std::string pathA = argv[1];
    const std::string pathB = argv[2];
    const std::string pathOut = argv[3];

    std::cout << "========================================================\n";
    std::cout << "LastWave Native C++ AutoDJ & Automix Offline Renderer\n";
    std::cout << "========================================================\n";

    // 1. Read Deck A (Tandav)
    std::ifstream fileA(pathA, std::ios::binary | std::ios::ate);
    if (!fileA) {
        std::cerr << "Error: Failed to open Deck A file: " << pathA << "\n";
        return 1;
    }
    const std::size_t bytesA = fileA.tellg();
    fileA.seekg(0, std::ios::beg);
    const std::size_t totalFramesA = bytesA / (2 * sizeof(float));
    std::vector<float> pcmA(totalFramesA * 2);
    fileA.read(reinterpret_cast<char*>(pcmA.data()), bytesA);
    fileA.close();
    std::cout << "[Deck A] Loaded: " << totalFramesA << " frames ("
              << std::fixed << std::setprecision(2) << (static_cast<double>(totalFramesA) / 48000.0)
              << "s)\n";

    // 2. Read Deck B (Loser)
    std::ifstream fileB(pathB, std::ios::binary | std::ios::ate);
    if (!fileB) {
        std::cerr << "Error: Failed to open Deck B file: " << pathB << "\n";
        return 1;
    }
    const std::size_t bytesB = fileB.tellg();
    fileB.seekg(0, std::ios::beg);
    const std::size_t totalFramesB = bytesB / (2 * sizeof(float));
    std::vector<float> pcmB(totalFramesB * 2);
    fileB.read(reinterpret_cast<char*>(pcmB.data()), bytesB);
    fileB.close();
    std::cout << "[Deck B] Loaded: " << totalFramesB << " frames ("
              << std::fixed << std::setprecision(2) << (static_cast<double>(totalFramesB) / 48000.0)
              << "s)\n";

    // 3. Configure AutoMixEngine
    AutoMixEngine engine;
    engine.configure(48000.0);
    engine.setEnabled(true);
    engine.setBarMode(8);

    // 4. Prebuffer Deck B into engine
    // Buffer first 25 seconds of Deck B
    const std::size_t prebufferFrames = std::min(totalFramesB, static_cast<std::size_t>(48000 * 25));
    std::size_t written = engine.writeDeckBPcm(pcmB.data(), prebufferFrames);
    std::cout << "[AutoDJ] Prebuffered Deck B: " << written << " frames ("
              << (static_cast<double>(written) / 48000.0) << "s)\n";

    const std::size_t cueTrimFrames = engine.getDeckBLeadingTrimFrames();
    const std::int64_t firstBeatMs = engine.getDeckBFirstBeatMs();
    std::cout << "[AutoDJ] Smart Intro Cue Detected: " << cueTrimFrames
              << " frames (" << firstBeatMs << " ms)\n";

    // 5. Output timeline setup
    constexpr double kSampleRate = 48000.0;
    constexpr double kTotalDurationSec = 300.0; // Exact 5 minutes
    constexpr std::size_t kTotalOutputFrames = static_cast<std::size_t>(kTotalDurationSec * kSampleRate); // 14,400,000

    constexpr double kTransitionDurationSec = 15.0; // 15-second club blend
    const std::size_t transitionDurationFrames = static_cast<std::size_t>(kTransitionDurationSec * kSampleRate); // 720,000

    // Deck A runs until its outro: 197.5s.
    // Transition begins 15.0s before end of Deck A:
    const std::size_t transitionStartFrame = (totalFramesA > transitionDurationFrames)
        ? (totalFramesA - transitionDurationFrames)
        : 0;
    const std::size_t transitionEndFrame = totalFramesA;

    std::cout << "[AutoDJ] Transition Start: Frame " << transitionStartFrame << " ("
              << (transitionStartFrame / kSampleRate) << "s / 3:02.50)\n";
    std::cout << "[AutoDJ] Transition End:   Frame " << transitionEndFrame << " ("
              << (transitionEndFrame / kSampleRate) << "s / 3:17.50)\n";
    std::cout << "[AutoDJ] Target Mix Length: " << kTotalOutputFrames << " frames (300.00s / 5:00.00)\n";

    std::vector<float> outputMix;
    outputMix.reserve(kTotalOutputFrames * 2);

    constexpr std::size_t kChunkSize = 1024;

    // --- Phase 1: Deck A Solo (0:00 to 3:02.50) ---
    std::size_t currentFrameA = 0;
    while (currentFrameA < transitionStartFrame) {
        const std::size_t chunk = std::min(kChunkSize, transitionStartFrame - currentFrameA);
        std::vector<float> buf(pcmA.begin() + currentFrameA * 2, pcmA.begin() + (currentFrameA + chunk) * 2);

        engine.process(buf.data(), chunk);

        outputMix.insert(outputMix.end(), buf.begin(), buf.end());
        currentFrameA += chunk;
    }
    std::cout << "[Render] Phase 1 (Deck A Solo) completed: " << (outputMix.size() / 2) << " frames\n";

    // --- Phase 2: 15-Second Auto DJ & Automix Transition (3:02.50 to 3:17.50) ---
    std::cout << "[AutoDJ] >>> TRIGGERING TRANSITION (15s Window) <<<\n";
    engine.startTransition(static_cast<int>(kTransitionDurationSec * 1000.0));

    std::size_t transitionFramesProcessed = 0;
    while (currentFrameA < transitionEndFrame) {
        const std::size_t chunk = std::min(kChunkSize, transitionEndFrame - currentFrameA);
        std::vector<float> buf(pcmA.begin() + currentFrameA * 2, pcmA.begin() + (currentFrameA + chunk) * 2);

        engine.process(buf.data(), chunk);

        const float progress = engine.getTransitionProgress();
        if (transitionFramesProcessed % (48000 * 3) == 0) {
            std::cout << "  - Transition Progress: " << std::fixed << std::setprecision(1)
                      << (progress * 100.0F) << "% | State: "
                      << static_cast<int>(engine.getState())
                      << " | Deck A HPF & Echo Active\n";
        }

        outputMix.insert(outputMix.end(), buf.begin(), buf.end());
        currentFrameA += chunk;
        transitionFramesProcessed += chunk;
    }
    std::cout << "[Render] Phase 2 (Transition) completed: " << (outputMix.size() / 2) << " frames\n";

    // --- Phase 3: Deck B Solo (3:17.50 to 5:00.00) ---
    // Transition consumed transitionDurationFrames from Deck B after cueTrimFrames
    const std::size_t deckBStartFrame = cueTrimFrames + transitionDurationFrames;
    std::cout << "[AutoDJ] Switching to Deck B Solo from frame " << deckBStartFrame
              << " (" << (deckBStartFrame / kSampleRate) << "s into Loser)\n";

    const std::size_t remainingMixFrames = (kTotalOutputFrames > (outputMix.size() / 2))
        ? (kTotalOutputFrames - (outputMix.size() / 2))
        : 0;

    std::cout << "[Render] Phase 3 Deck B Frames Needed: " << remainingMixFrames
              << " (" << (remainingMixFrames / kSampleRate) << "s)\n";

    std::size_t currentFrameB = deckBStartFrame;
    std::size_t bFramesWritten = 0;

    while (bFramesWritten < remainingMixFrames && currentFrameB < totalFramesB) {
        const std::size_t chunk = std::min(kChunkSize, remainingMixFrames - bFramesWritten);
        const std::size_t availableChunk = std::min(chunk, totalFramesB - currentFrameB);

        std::vector<float> buf(pcmB.begin() + currentFrameB * 2, pcmB.begin() + (currentFrameB + availableChunk) * 2);

        // If near the end of 5 minutes (last 2.5 seconds = 120,000 frames), apply smooth master fade out
        for (std::size_t f = 0; f < availableChunk; ++f) {
            const std::size_t globalFrame = (outputMix.size() / 2) + f;
            if (globalFrame >= kTotalOutputFrames - 120000) {
                const float fade = static_cast<float>(kTotalOutputFrames - globalFrame) / 120000.0F;
                buf[f * 2] *= fade;
                buf[f * 2 + 1] *= fade;
            }
        }

        outputMix.insert(outputMix.end(), buf.begin(), buf.end());
        currentFrameB += availableChunk;
        bFramesWritten += availableChunk;
    }

    // Zero-pad if needed to make exactly 5:00.00
    while (outputMix.size() < kTotalOutputFrames * 2) {
        outputMix.push_back(0.0F);
    }

    std::cout << "[Render] Total Mix Frames: " << (outputMix.size() / 2)
              << " (" << ((outputMix.size() / 2) / kSampleRate) << "s / exactly 5:00.00)\n";

    // Peak scan and soft safety limiter
    float maxPeak = 0.0F;
    for (float s : outputMix) {
        maxPeak = std::max(maxPeak, std::abs(s));
    }
    std::cout << "[Audio Quality] Maximum Peak: " << maxPeak << " ("
              << (20.0 * std::log10(std::max(1e-6f, maxPeak))) << " dBFS)\n";

    if (maxPeak > 0.99F) {
        const float gain = 0.98F / maxPeak;
        std::cout << "[Audio Quality] Normalizing master mix to -0.2 dBFS (gain: " << gain << ")\n";
        for (float& s : outputMix) {
            s *= gain;
        }
    }

    // Write output raw PCM
    std::ofstream fileOut(pathOut, std::ios::binary);
    if (!fileOut) {
        std::cerr << "Error: Failed to open output file: " << pathOut << "\n";
        return 1;
    }
    fileOut.write(reinterpret_cast<const char*>(outputMix.data()), outputMix.size() * sizeof(float));
    fileOut.close();

    std::cout << "[Render] Successfully written output raw PCM to " << pathOut << "\n";
    std::cout << "========================================================\n";
    return 0;
}
