#pragma once
#include <algorithm>
#include <atomic>
#include <cstdint>
#include <limits>
#include <memory>
#include <stdexcept>
#include <type_traits>
#include <vector>

namespace audiocity { namespace arrange {
constexpr int64_t ppqn = 960;
constexpr int64_t maxTick = int64_t{1} << 40;

// Exactly one producer and one consumer. Construction/destruction/reset require quiescence.
// Monotonic unsigned counters keep wrap-around defined; capacity stays below half counter range.
template<class T> class SpscRing {
    static_assert(std::is_trivially_copyable<T>::value, "Ring payload must not own memory");
    static_assert(std::atomic<uint32_t>::is_always_lock_free, "32-bit lock-free atomics required");
    const uint32_t capacity_, mask_;
    std::unique_ptr<T[]> data_;
    alignas(64) std::atomic<uint32_t> write_{0};
    alignas(64) std::atomic<uint32_t> read_{0};
public:
    explicit SpscRing(uint32_t capacity) : capacity_(capacity), mask_(capacity - 1) {
        if (capacity < 2 || capacity > (1u << 24) || (capacity & (capacity - 1)))
            throw std::invalid_argument("Ring capacity must be a bounded power of two");
        data_.reset(new T[capacity]{});
    }
    uint32_t writable() const noexcept {
        return capacity_ - (write_.load(std::memory_order_relaxed) - read_.load(std::memory_order_acquire));
    }
    uint32_t readable() const noexcept {
        return write_.load(std::memory_order_acquire) - read_.load(std::memory_order_relaxed);
    }
    uint32_t push(const T* source, uint32_t count) noexcept {
        const uint32_t head = write_.load(std::memory_order_relaxed);
        count = std::min(count, capacity_ - (head - read_.load(std::memory_order_acquire)));
        for (uint32_t i = 0; i < count; ++i) data_[(head + i) & mask_] = source[i];
        write_.store(head + count, std::memory_order_release); return count;
    }
    uint32_t pop(T* target, uint32_t count) noexcept {
        const uint32_t tail = read_.load(std::memory_order_relaxed);
        count = std::min(count, write_.load(std::memory_order_acquire) - tail);
        for (uint32_t i = 0; i < count; ++i) target[i] = data_[(tail + i) & mask_];
        read_.store(tail + count, std::memory_order_release); return count;
    }
    bool push(const T& value) noexcept { return push(&value, 1) == 1; }
    bool pop(T& value) noexcept { return pop(&value, 1) == 1; }
};

// Control owns allocation/reclamation. Audio owns active_ and never destroys a snapshot.
// publish/collect: one control thread. acquireAtBoundary: one callback thread.
// Destructor: only after callback has stopped. A full retirement queue defers adoption.
template<class T> class SnapshotExchange {
    static_assert(std::atomic<T*>::is_always_lock_free, "Lock-free pointers required");
    std::atomic<T*> pending_{nullptr};
    T* active_ = nullptr;
    SpscRing<T*> retired_{64};
public:
    ~SnapshotExchange() {
        delete pending_.exchange(nullptr); delete active_; collect();
    }
    void publish(std::unique_ptr<T> snapshot) noexcept {
        delete pending_.exchange(snapshot.release(), std::memory_order_acq_rel);
        collect();
    }
    void collect() noexcept { T* old; while (retired_.pop(old)) delete old; }
    T* acquireAtBoundary() noexcept {
        if (retired_.writable() == 0) return active_;
        T* next = pending_.exchange(nullptr, std::memory_order_acq_rel);
        if (next) {
            if (active_) retired_.push(active_); // Capacity reserved above, single audio producer.
            active_ = next;
        }
        return active_;
    }
};

struct TempoEvent { int64_t tick, microsPerQuarter; };
// Exact fixed rational time. No BigInteger, floating point, allocation or lock in conversion.
// Splitting into whole microseconds + 1/960 microsecond avoids nonportable 128-bit integers.
class TempoMap {
    struct Stamp { int64_t micros = 0, remainder = 0; };
    std::vector<TempoEvent> events_;
    std::vector<Stamp> prefix_;
    static Stamp add(Stamp a, int64_t ticks, int64_t microsPerQuarter) noexcept {
        a.micros += (ticks / ppqn) * microsPerQuarter;
        const int64_t fractional = (ticks % ppqn) * microsPerQuarter + a.remainder;
        a.micros += fractional / ppqn; a.remainder = fractional % ppqn; return a;
    }
public:
    explicit TempoMap(std::vector<TempoEvent> events) : events_(std::move(events)) {
        if (events_.empty() || events_.size() > 100000) throw std::invalid_argument("Tempo event count");
        std::sort(events_.begin(), events_.end(), [](auto a, auto b) { return a.tick < b.tick; });
        if (events_[0].tick != 0) throw std::invalid_argument("Tempo origin");
        prefix_.resize(events_.size());
        for (size_t i = 0; i < events_.size(); ++i) {
            const auto event = events_[i];
            if (event.tick < 0 || event.tick > maxTick || event.microsPerQuarter < 60000 || event.microsPerQuarter > 60000000)
                throw std::invalid_argument("Tempo bounds");
            if (i) {
                if (event.tick == events_[i - 1].tick) throw std::invalid_argument("Duplicate tempo tick");
                prefix_[i] = add(prefix_[i - 1], event.tick - events_[i - 1].tick, events_[i - 1].microsPerQuarter);
            }
        }
    }
    // Callers validate 0<=tick<=maxTick and 8000<=rate<=384000 when preparing commands.
    int64_t sampleAtTick(int64_t tick, int rate) const noexcept {
        size_t lo = 0, hi = events_.size();
        while (lo + 1 < hi) { size_t mid = (lo + hi) / 2; if (events_[mid].tick <= tick) lo = mid; else hi = mid; }
        const Stamp stamp = add(prefix_[lo], tick - events_[lo].tick, events_[lo].microsPerQuarter);
        constexpr int64_t denominator = ppqn * 1000000;
        return (stamp.micros / 1000000) * rate +
            (((stamp.micros % 1000000) * ppqn + stamp.remainder) * rate + denominator / 2) / denominator;
    }
    int64_t tickBeforeSample(int64_t sample, int rate) const noexcept {
        int64_t lo = 0, hi = maxTick;
        while (lo < hi) { int64_t mid = lo + (hi - lo + 1) / 2;
            if (sampleAtTick(mid, rate) <= sample) lo = mid; else hi = mid - 1;
        }
        return lo;
    }
    // The returned tick minimizes sample distance, with exact ties resolved later.
    int64_t tickAtSample(int64_t sample, int rate) const noexcept {
        const int64_t lo = tickBeforeSample(sample, rate);
        if (lo == maxTick) return lo;
        return sample - sampleAtTick(lo, rate) < sampleAtTick(lo + 1, rate) - sample ? lo : lo + 1;
    }
};
} }
