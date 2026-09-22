#ifndef AE_CORE_RING_BUFFER_H
#define AE_CORE_RING_BUFFER_H

// Lock-free single-producer / single-consumer byte ring. Safe to touch from a
// real-time callback (no locks, no allocation, no syscalls) — which is why the
// USB isochronous transfer callbacks and the JACK process callback both use it
// instead of the blocking mutex+CV NativePcmBuffer.
//
// Extracted from the USB driver so every backend that needs an RT-safe ring
// (USB, JACK, ...) shares one implementation.

#include <cstdint>
#include <cstring>
#include <atomic>
#include <algorithm>

namespace ae {

class RingBuffer {
public:
    explicit RingBuffer(size_t capacity)
        : capacity(capacity), buffer(new uint8_t[capacity]) {
        readPos.store(0);
        writePos.store(0);
    }
    ~RingBuffer() { delete[] buffer; }

    RingBuffer(const RingBuffer&) = delete;
    RingBuffer& operator=(const RingBuffer&) = delete;

    size_t write(const uint8_t* data, size_t len) {
        size_t r = readPos.load(std::memory_order_acquire);
        size_t w = writePos.load(std::memory_order_relaxed);
        size_t available = freeSpace(r, w);
        size_t toWrite = std::min(len, available);

        size_t firstPart = std::min(toWrite, capacity - w);
        memcpy(buffer + w, data, firstPart);
        if (toWrite > firstPart) {
            memcpy(buffer, data + firstPart, toWrite - firstPart);
        }

        writePos.store(advance(w, toWrite), std::memory_order_release);
        return toWrite;
    }

    size_t read(uint8_t* data, size_t len) {
        size_t w = writePos.load(std::memory_order_acquire);
        size_t r = readPos.load(std::memory_order_relaxed);
        size_t available = distance(w, r);
        size_t toRead = std::min(len, available);

        size_t firstPart = std::min(toRead, capacity - r);
        memcpy(data, buffer + r, firstPart);
        if (toRead > firstPart) {
            memcpy(data + firstPart, buffer, toRead - firstPart);
        }

        readPos.store(advance(r, toRead), std::memory_order_release);
        return toRead;
    }

    size_t getAvailable() const {
        size_t w = writePos.load(std::memory_order_acquire);
        size_t r = readPos.load(std::memory_order_acquire);
        return distance(w, r);
    }

    // Returns a conservative lower bound on free space (reader may free more at any time).
    size_t getFreeSpace() const {
        size_t r = readPos.load(std::memory_order_acquire);
        size_t w = writePos.load(std::memory_order_relaxed);
        return freeSpace(r, w);
    }

    void clear() {
        readPos.store(0);
        writePos.store(0);
    }

    uint8_t* getBuffer() const { return buffer; }
    size_t getCapacity() const { return capacity; }

private:
    // Wrap by conditional subtraction rather than `%`. The modulo form needed a
    // hardware integer divide on every call — capacity is a runtime value, so
    // the compiler cannot strength-reduce it — and submitTransfer() reads this
    // ring once per isochronous packet (32 per transfer) on the thread whose
    // timing decides whether audio crackles.
    //
    // All three helpers are exact, not approximations, because readPos and
    // writePos are always < capacity and no caller ever moves a position by
    // more than capacity - 1:
    //   advance()  — pos + delta < 2 * capacity, so one subtraction suffices.
    //   distance() — the unwrapped difference needs at most one carry.
    //   freeSpace()— reserves one byte to tell full from empty, which is why
    //                it cannot be expressed as distance(readPos, writePos + 1):
    //                writePos + 1 can equal capacity, and the comparison then
    //                takes the wrong branch.
    //
    // A power-of-two capacity + mask was considered and rejected: it would
    // round the mlock'd ring up by as much as 2x (18 MB -> 33 MB for a 3 s
    // buffer at 768 kHz), and dsp_bench shows the wrap arithmetic is not where
    // the time goes.
    inline size_t advance(size_t pos, size_t delta) const {
        const size_t next = pos + delta;
        return next >= capacity ? next - capacity : next;
    }
    // Occupancy: bytes from readPos forward to writePos.
    inline size_t distance(size_t ahead, size_t behind) const {
        return ahead >= behind ? ahead - behind : ahead + capacity - behind;
    }
    // (r + capacity - w - 1) mod capacity, branch form. r == w (empty) must
    // yield capacity - 1, which is why the `r > w` test is strict.
    inline size_t freeSpace(size_t r, size_t w) const {
        return r > w ? r - w - 1 : r + capacity - w - 1;
    }

    const size_t capacity;
    uint8_t* const buffer;
    std::atomic<size_t> readPos;
    std::atomic<size_t> writePos;
};

} // namespace ae

#endif // AE_CORE_RING_BUFFER_H
