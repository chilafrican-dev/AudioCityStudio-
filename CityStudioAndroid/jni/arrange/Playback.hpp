#pragma once
#include "Realtime.hpp"
#include <cmath>
#include <cstring>
#include <string>
#include <optional>

namespace audiocity { namespace arrange {
struct Stereo { float left = 0, right = 0; };

// This is the device boundary, not the project renderer. Exactly one non-RT producer.
// Callback starvation yields initialized silence and a counter, never a wait.
class OutputFifo {
    SpscRing<Stereo> queue_;
    std::atomic<uint32_t> consumed_{0}, missing_{0};
public:
    explicit OutputFifo(uint32_t capacity) : queue_(capacity) {}
    uint32_t enqueue(const Stereo* frames, uint32_t count) noexcept { return queue_.push(frames, count); }
    void callback(float* output, uint32_t frames) noexcept {
        uint32_t copied = 0;
        Stereo sample;
        while (copied < frames && queue_.pop(sample)) {
            output[copied * 2] = sample.left; output[copied * 2 + 1] = sample.right; ++copied;
        }
        std::fill(output + copied * 2, output + frames * 2, 0.0f);
        consumed_.fetch_add(copied, std::memory_order_relaxed);
        missing_.fetch_add(frames - copied, std::memory_order_relaxed);
    }
    uint32_t consumed() const noexcept { return consumed_.load(std::memory_order_relaxed); }
    uint32_t missing() const noexcept { return missing_.load(std::memory_order_relaxed); }
};
struct PcmSource { std::vector<Stereo> frames; };
struct PcmRegion {
    int64_t startTick = 0, lengthTick = 0;
    uint32_t source = 0;
    // Audio source coordinates use integer source frames; timeline coordinates remain ticks.
    uint64_t sourceOffsetFrame = 0;
    float gain = 1;
    int64_t fadeInTick = 0, fadeOutTick = 0;
    bool mute = false, repeatSource = false;
};
struct SendRoute { uint32_t destination; float gain; bool preFader; };
struct PcmTrack {
    std::vector<PcmRegion> regions;
    std::vector<SendRoute> sends;
    int32_t output = -1; // -1 = master, otherwise an acyclic track/bus index.
    float volume = 1, pan = 0;
    bool mute = false, solo = false;
};
struct PlaybackProject {
    TempoMap tempo;
    int sampleRate = 48000;
    std::vector<PcmSource> sources;
    std::vector<PcmTrack> tracks;
    float masterGain = 1;
    bool cycle = false;
    int64_t cycleStartTick = 0, cycleEndTick = 0;
    explicit PlaybackProject(std::vector<TempoEvent> events) : tempo(std::move(events)) {}
};

// Prepared off audio thread. Its document is immutable after construction; scratch/runtime caches
// are exclusively audio-owned. publish it through SnapshotExchange<PreparedPlayback>.
class PreparedPlayback {
    friend class PcmRenderer;
    const PlaybackProject project_;
    const uint32_t blockCapacity_;
    std::vector<uint32_t> order_;
    std::vector<float> pre_, post_;
    std::vector<std::vector<int64_t>> intervalEnd_;
    std::vector<size_t> intervalBase_;
    std::vector<float> leftGain_, rightGain_;
    std::vector<uint8_t> audible_;
    static bool finiteGain(float gain) { return std::isfinite(gain) && gain >= 0 && gain <= 64; }
    void validateAndPrepare() {
        const auto& p = project_;
        if (p.sampleRate < 8000 || p.sampleRate > 384000 || p.tracks.size() > 1024 || p.sources.size() > 100000 || !finiteGain(p.masterGain))
            throw std::invalid_argument("Playback project limits");
        if (p.cycleStartTick < 0 || p.cycleEndTick < p.cycleStartTick || p.cycleEndTick > maxTick || (p.cycle && p.cycleStartTick == p.cycleEndTick))
            throw std::invalid_argument("Invalid cycle");
        if (p.cycle && p.tempo.sampleAtTick(p.cycleStartTick, p.sampleRate) == p.tempo.sampleAtTick(p.cycleEndTick, p.sampleRate))
            throw std::invalid_argument("Sub-sample cycle");
        for (const auto& source : p.sources) for (auto frame : source.frames)
            if (!std::isfinite(frame.left) || !std::isfinite(frame.right)) throw std::invalid_argument("Nonfinite PCM");
        std::vector<uint32_t> incoming(p.tracks.size(), 0);
        auto edge = [&](uint32_t from, int32_t to) {
            if (to < 0 || static_cast<size_t>(to) >= p.tracks.size() || from == static_cast<uint32_t>(to)) throw std::invalid_argument("Invalid route");
            ++incoming[static_cast<size_t>(to)];
        };
        for (size_t i = 0; i < p.tracks.size(); ++i) {
            const auto& track = p.tracks[i];
            if (!finiteGain(track.volume) || !std::isfinite(track.pan) || track.pan < -1 || track.pan > 1 || track.output < -1 || track.sends.size() > 1024 || track.regions.size() > 1000000)
                throw std::invalid_argument("Track parameters");
            if (track.output >= 0) edge(static_cast<uint32_t>(i), track.output);
            for (auto send : track.sends) { if (!finiteGain(send.gain)) throw std::invalid_argument("Send gain"); edge(static_cast<uint32_t>(i), static_cast<int32_t>(send.destination)); }
            int64_t previous = -1;
            for (const auto& region : track.regions) {
                if (region.startTick < 0 || region.startTick < previous || region.lengthTick < 0 || region.lengthTick > maxTick - region.startTick ||
                    region.startTick > maxTick || region.source >= p.sources.size() || !finiteGain(region.gain) || region.fadeInTick < 0 ||
                    region.fadeOutTick < 0 || region.fadeInTick > region.lengthTick || region.fadeOutTick > region.lengthTick)
                    throw std::invalid_argument("Region parameters/order");
                const auto& source = p.sources[region.source];
                if (region.sourceOffsetFrame > source.frames.size() || (region.repeatSource && region.sourceOffsetFrame == source.frames.size()))
                    throw std::invalid_argument("Source offset/repeat");
                previous = region.startTick;
            }
        }
        std::vector<uint32_t> ready;
        for (uint32_t i = 0; i < incoming.size(); ++i) if (!incoming[i]) ready.push_back(i);
        for (size_t i = 0; i < ready.size(); ++i) {
            uint32_t index = ready[i]; order_.push_back(index); const auto& track = p.tracks[index];
            auto release = [&](uint32_t target) { if (--incoming[target] == 0) ready.push_back(target); };
            if (track.output >= 0) release(static_cast<uint32_t>(track.output));
            for (auto send : track.sends) release(send.destination);
        }
        if (order_.size() != p.tracks.size()) throw std::invalid_argument("Routing cycle");
        pre_.resize(p.tracks.size() * blockCapacity_ * 2); post_.resize(pre_.size());
        intervalEnd_.resize(p.tracks.size()); intervalBase_.resize(p.tracks.size());
        leftGain_.resize(p.tracks.size()); rightGain_.resize(p.tracks.size()); audible_.resize(p.tracks.size());
        // Solo includes all downstream return paths, while unrelated sources feeding a bus stay silent.
        bool solo = false; for (const auto& track : p.tracks) solo |= track.solo;
        for (size_t i = 0; i < p.tracks.size(); ++i) audible_[i] = !solo || p.tracks[i].solo;
        for (uint32_t index : order_) if (audible_[index]) {
            const auto& track = p.tracks[index]; if (track.output >= 0) audible_[static_cast<size_t>(track.output)] = true;
            for (auto send : track.sends) audible_[send.destination] = true;
        }
        for (size_t i = 0; i < p.tracks.size(); ++i) {
            size_t base = 1; while (base < p.tracks[i].regions.size()) base *= 2;
            intervalBase_[i] = base; auto& tree = intervalEnd_[i]; tree.resize(base * 2, -1);
            for (size_t ri = 0; ri < p.tracks[i].regions.size(); ++ri) {
                const auto& r = p.tracks[i].regions[ri]; tree[base + ri] = r.startTick + r.lengthTick;
            }
            for (size_t node = base - 1; node > 0; --node) tree[node] = std::max(tree[node * 2], tree[node * 2 + 1]);
            const float angle = (p.tracks[i].pan + 1) * static_cast<float>(3.14159265358979323846 / 4);
            leftGain_[i] = p.tracks[i].volume * std::cos(angle); rightGain_[i] = p.tracks[i].volume * std::sin(angle);
        }
    }
public:
    PreparedPlayback(PlaybackProject project, uint32_t blockCapacity) : project_(std::move(project)), blockCapacity_(blockCapacity) {
        if (!blockCapacity || blockCapacity > 8192) throw std::invalid_argument("Callback block capacity");
        validateAndPrepare();
    }
};
struct MusicalCursor { int64_t tick, phaseSamples, samplesPerTick; };
struct Meter { float peakLeft = 0, peakRight = 0, rms = 0; uint8_t clipped = 0; };

// Resident-PCM playback kernel. Uses the same function for device blocks and offline blocks.
// Control commands/edits must reach this object at callback boundaries, never by concurrent setters.
class PcmRenderer {
    PreparedPlayback& state_;
    int64_t position_ = 0;
    uint32_t cycleBlend_ = 0;
    float previousLeft_ = 0, previousRight_ = 0, blendLeft_ = 0, blendRight_ = 0;
    bool clipHold_ = false;
    static float limit(float x) noexcept {
        // Unity below -0.92 dBFS, smooth bounded knee above it. No state or chunk dependence.
        if (std::isnan(x)) return 0.f;
        const float magnitude = std::abs(x);
        return magnitude <= .9f ? x : std::copysign(.9f + .1f * std::tanh((magnitude - .9f) * 10), x);
    }
    void chunk(float* output, uint32_t frames) noexcept {
        const auto& project = state_.project_;
        std::fill(state_.pre_.begin(), state_.pre_.end(), 0.f); std::fill(state_.post_.begin(), state_.post_.end(), 0.f);
        std::fill(output, output + frames * 2, 0.f);
        for (uint32_t ti : state_.order_) {
            const auto& track = project.tracks[ti];
            if (track.mute || !state_.audible_[ti]) continue;
            float* pre = state_.pre_.data() + ti * state_.blockCapacity_ * 2;
            float* post = state_.post_.data() + ti * state_.blockCapacity_ * 2;
            // A balanced max-end interval tree skips expired subtrees even when a long
            // region overlaps thousands of shorter clips. DFS is ordered by region index.
            struct Node { size_t index, begin, size; };
            Node stack[64]; unsigned pending = 1;
            stack[0] = Node{1, 0, state_.intervalBase_[ti]};
            while (pending) {
                const Node node = stack[--pending];
                const int64_t lastTick = state_.intervalEnd_[ti][node.index];
                if (lastTick < 0 || node.begin >= track.regions.size() ||
                    project.tempo.sampleAtTick(lastTick, project.sampleRate) <= position_ ||
                    project.tempo.sampleAtTick(track.regions[node.begin].startTick, project.sampleRate) >= position_ + frames) continue;
                if (node.size > 1) {
                    const size_t half = node.size / 2;
                    stack[pending++] = Node{node.index * 2 + 1, node.begin + half, half};
                    stack[pending++] = Node{node.index * 2, node.begin, half};
                    continue;
                }
                const size_t ri = node.begin;
                const auto& r = track.regions[ri];
                const int64_t start = project.tempo.sampleAtTick(r.startTick, project.sampleRate);
                if (start >= position_ + frames) continue;
                const int64_t finish = project.tempo.sampleAtTick(r.startTick + r.lengthTick, project.sampleRate);
                if (r.mute || finish <= position_ || finish <= start) continue;
                const auto& source = project.sources[r.source].frames;
                const int64_t begin = std::max(position_, start), end = std::min(position_ + frames, finish);
                const int64_t fadeIn = project.tempo.sampleAtTick(r.startTick + r.fadeInTick, project.sampleRate) - start;
                const int64_t fadeOut = finish - project.tempo.sampleAtTick(r.startTick + r.lengthTick - r.fadeOutTick, project.sampleRate);
                for (int64_t at = begin; at < end; ++at) {
                    uint64_t sourceFrame = r.sourceOffsetFrame + static_cast<uint64_t>(at - start);
                    if (r.repeatSource) sourceFrame = r.sourceOffsetFrame + (sourceFrame - r.sourceOffsetFrame) % (source.size() - r.sourceOffsetFrame);
                    if (sourceFrame >= source.size()) continue;
                    float gain = r.gain;
                    if (fadeIn) gain *= std::min(1.f, static_cast<float>(at - start) / static_cast<float>(fadeIn));
                    if (fadeOut) gain *= std::min(1.f, static_cast<float>(finish - 1 - at) / static_cast<float>(fadeOut));
                    const size_t out = static_cast<size_t>(at - position_) * 2;
                    pre[out] += source[sourceFrame].left * gain; pre[out + 1] += source[sourceFrame].right * gain;
                }
            }
            for (uint32_t i = 0; i < frames; ++i) { post[i * 2] = pre[i * 2] * state_.leftGain_[ti]; post[i * 2 + 1] = pre[i * 2 + 1] * state_.rightGain_[ti]; }
            for (auto send : track.sends) {
                float* destination = state_.pre_.data() + send.destination * state_.blockCapacity_ * 2;
                const float* from = send.preFader ? pre : post;
                for (uint32_t i = 0; i < frames * 2; ++i) destination[i] += from[i] * send.gain;
            }
            float* destination = track.output < 0 ? output : state_.pre_.data() + static_cast<size_t>(track.output) * state_.blockCapacity_ * 2;
            for (uint32_t i = 0; i < frames * 2; ++i) destination[i] += post[i];
        }
        for (uint32_t i = 0; i < frames; ++i) {
            float left = output[i * 2] * project.masterGain, right = output[i * 2 + 1] * project.masterGain;
            clipHold_ |= !std::isfinite(left) || !std::isfinite(right) || std::abs(left) > 1 || std::abs(right) > 1;
            left = limit(left); right = limit(right);
            if (cycleBlend_) {
                const float mix = (33.f - static_cast<float>(cycleBlend_)) / 32.f;
                left = blendLeft_ + (left - blendLeft_) * mix; right = blendRight_ + (right - blendRight_) * mix; --cycleBlend_;
            }
            // Flush subnormal output, including platforms without a global FTZ mode.
            output[i * 2] = std::abs(left) < 1e-30f ? 0.f : left;
            output[i * 2 + 1] = std::abs(right) < 1e-30f ? 0.f : right;
            previousLeft_ = output[i * 2]; previousRight_ = output[i * 2 + 1];
        }
    }
public:
    explicit PcmRenderer(PreparedPlayback& state) noexcept : state_(state) {}
    bool seekTick(int64_t tick) noexcept {
        if (tick < 0 || tick > maxTick) return false;
        position_ = state_.project_.tempo.sampleAtTick(tick, state_.project_.sampleRate);
        cycleBlend_ = 0; previousLeft_ = previousRight_ = 0; return true;
    }
    MusicalCursor musicalCursor() const noexcept {
        const auto& p = state_.project_;
        const int64_t tick = p.tempo.tickBeforeSample(position_, p.sampleRate);
        const int64_t start = p.tempo.sampleAtTick(tick, p.sampleRate);
        const int64_t span = tick == maxTick ? 1 : p.tempo.sampleAtTick(tick + 1, p.sampleRate) - start;
        return {tick, position_ - start, std::max<int64_t>(1, span)};
    }
    // Runtime phase is retained across snapshots; it is not a persisted timeline position.
    bool resume(MusicalCursor cursor) noexcept {
        if (cursor.tick < 0 || cursor.tick > maxTick || cursor.phaseSamples < 0 || cursor.samplesPerTick <= 0 || cursor.phaseSamples >= cursor.samplesPerTick)
            return false;
        const auto& p = state_.project_;
        const int64_t start = p.tempo.sampleAtTick(cursor.tick, p.sampleRate);
        const int64_t span = cursor.tick == maxTick ? 1 : p.tempo.sampleAtTick(cursor.tick + 1, p.sampleRate) - start;
        // Both old/new tick spans are <=24000 samples under validated rates/tempos.
        if (cursor.samplesPerTick > 24000) return false;
        position_ = start + (cursor.phaseSamples * span + cursor.samplesPerTick / 2) / cursor.samplesPerTick;
        cycleBlend_ = 0; return true;
    }
    int64_t positionSample() const noexcept { return position_; }
    int64_t positionTick() const noexcept { return state_.project_.tempo.tickAtSample(position_, state_.project_.sampleRate); }
    void clearClipHold() noexcept { clipHold_ = false; }
    Meter render(float* output, uint32_t frames) noexcept {
        const auto& p = state_.project_;
        uint32_t done = 0;
        const int64_t loopStart = p.tempo.sampleAtTick(p.cycleStartTick, p.sampleRate), loopEnd = p.tempo.sampleAtTick(p.cycleEndTick, p.sampleRate);
        while (done < frames) {
            if (p.cycle && position_ >= loopEnd) {
                position_ = loopStart + (position_ - loopStart) % (loopEnd - loopStart);
                blendLeft_ = previousLeft_; blendRight_ = previousRight_; cycleBlend_ = 32;
            }
            uint32_t count = std::min(frames - done, state_.blockCapacity_);
            if (p.cycle) count = static_cast<uint32_t>(std::min<int64_t>(count, loopEnd - position_));
            chunk(output + done * 2, count); position_ += count; done += count;
        }
        Meter meter; double square = 0;
        for (uint32_t i = 0; i < frames; ++i) {
            meter.peakLeft = std::max(meter.peakLeft, std::abs(output[i * 2])); meter.peakRight = std::max(meter.peakRight, std::abs(output[i * 2 + 1]));
            square += static_cast<double>(output[i * 2]) * output[i * 2] + static_cast<double>(output[i * 2 + 1]) * output[i * 2 + 1];
        }
        meter.rms = frames ? static_cast<float>(std::sqrt(square / (frames * 2.0))) : 0; meter.clipped = clipHold_; return meter;
    }
};
enum class TransportAction : uint8_t { Play, Pause, Stop, Seek, ClearClip };
struct TransportCommand { TransportAction action; int64_t tick = 0; };
struct PlaybackStatus { int64_t sample, tick; Meter meter; bool playing; };

// UI/control -> commands/snapshots -> callback -> status -> UI.
// All members are bounded or preallocated. Render owns transport and mutable DSP state.
class PlaybackEngine {
    SnapshotExchange<PreparedPlayback> snapshots_;
    SpscRing<TransportCommand> commands_{128};
    SpscRing<PlaybackStatus> status_{64};
    PreparedPlayback* active_ = nullptr;
    std::optional<PcmRenderer> renderer_;
    bool playing_ = false;
    int64_t requestedTick_ = 0;
public:
    // Control thread, never callback. Destruction also requires a stopped callback.
    void publish(std::unique_ptr<PreparedPlayback> project) noexcept { snapshots_.publish(std::move(project)); }
    void collect() noexcept { snapshots_.collect(); }
    bool command(TransportCommand command) noexcept {
        if (command.tick < 0 || command.tick > maxTick) return false;
        switch (command.action) {
            case TransportAction::Play: case TransportAction::Pause: case TransportAction::Stop:
            case TransportAction::Seek: case TransportAction::ClearClip: return commands_.push(command);
        }
        return false;
    }
    bool poll(PlaybackStatus& latest) noexcept {
        bool any = false; PlaybackStatus status;
        for (unsigned i = 0; i < 64 && status_.pop(status); ++i) { latest = status; any = true; } return any;
    }
    void callback(float* output, uint32_t frames) noexcept {
        const MusicalCursor previousCursor = renderer_ ? renderer_->musicalCursor() : MusicalCursor{requestedTick_, 0, 1};
        PreparedPlayback* next = snapshots_.acquireAtBoundary();
        if (next != active_) { active_ = next; renderer_.emplace(*active_); renderer_->resume(previousCursor); }
        TransportCommand command;
        for (unsigned i = 0; i < 128 && commands_.pop(command); ++i) {
            switch (command.action) {
                case TransportAction::Play: playing_ = true; break;
                case TransportAction::Pause: playing_ = false; break;
                case TransportAction::Stop: playing_ = false; requestedTick_ = 0; if (renderer_) renderer_->seekTick(0); break;
                case TransportAction::Seek: requestedTick_ = command.tick; if (renderer_) renderer_->seekTick(command.tick); break;
                case TransportAction::ClearClip: if (renderer_) renderer_->clearClipHold(); break;
            }
        }
        Meter meter;
        if (playing_ && renderer_) meter = renderer_->render(output, frames);
        else std::fill(output, output + frames * 2, 0.f);
        status_.push(PlaybackStatus{renderer_ ? renderer_->positionSample() : 0, renderer_ ? renderer_->positionTick() : 0, meter, playing_});
    }
};
} }
