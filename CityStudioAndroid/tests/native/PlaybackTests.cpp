#include "../../jni/arrange/Playback.hpp"
#include <chrono>
#include <cstdlib>
#include <ctime>
#include <iostream>
#include <new>
#include <random>
#include <thread>

using namespace audiocity::arrange;
static thread_local bool inCallback = false;
static std::atomic<uint64_t> callbackAllocations{0}, callbackFrees{0};
void* operator new(std::size_t bytes) {
    if (inCallback) callbackAllocations.fetch_add(1);
    if (void* p = std::malloc(bytes ? bytes : 1)) return p;
    throw std::bad_alloc();
}
void* operator new[](std::size_t bytes) { return ::operator new(bytes); }
void operator delete(void* p) noexcept { if (p && inCallback) callbackFrees.fetch_add(1); std::free(p); }
void operator delete[](void* p) noexcept { ::operator delete(p); }
void operator delete(void* p, std::size_t) noexcept { ::operator delete(p); }
void operator delete[](void* p, std::size_t) noexcept { ::operator delete(p); }
static uint64_t passed = 0;
static void check(bool value, const char* label) { if (!value) throw std::runtime_error(label); ++passed; }
template<class F> static void rejects(F f, const char* label) {
    try { f(); } catch (const std::invalid_argument&) { ++passed; return; } throw std::runtime_error(label);
}
static bool near(float a, float b) { return std::abs(a - b) < 1e-6f; }
struct CallbackScope { CallbackScope() { inCallback = true; } ~CallbackScope() { inCallback = false; } };

static PlaybackProject basic(unsigned tracks = 1) {
    PlaybackProject p({{0, 500000}}); p.sources.resize(1);
    p.sources[0].frames.resize(250000, Stereo{.25f, .5f});
    for (unsigned i = 0; i < tracks; ++i) { PcmTrack t; t.regions.push_back(PcmRegion{0, 9600}); p.tracks.push_back(std::move(t)); }
    return p;
}
static void ringTests() {
    rejects([] { SpscRing<int> invalid(7); }, "non power of two ring");
    SpscRing<int> q(8); int x = -1;
    check(!q.pop(x) && x == -1 && q.writable() == 8, "empty ring");
    for (int i = 0; i < 8; ++i) check(q.push(i), "ring accepts capacity");
    check(!q.push(9), "ring rejects overflow");
    for (int i = 0; i < 8; ++i) check(q.pop(x) && x == i, "ring ordering");
    for (int i = 0; i < 100000; ++i) { check(q.push(i), "wrap write"); check(q.pop(x) && x == i, "wrap read"); }
    SpscRing<uint32_t> threaded(256); constexpr uint32_t count = 1000000;
    std::atomic<bool> failure{false};
    std::thread writer([&] { for (uint32_t i = 0; i < count;) if (threaded.push(i)) ++i; });
    std::thread reader([&] { for (uint32_t i = 0; i < count;) { uint32_t got; if (threaded.pop(got)) { if (got != i) failure = true; ++i; } } });
    writer.join(); reader.join(); check(!failure && threaded.readable() == 0, "million-item concurrent FIFO");
    OutputFifo audio(8); Stereo input[] = {{1, 2}, {3, 4}, {5, 6}}; float output[16];
    check(audio.enqueue(input, 3) == 3, "audio queue enqueue");
    { CallbackScope guard; audio.callback(output, 8); }
    check(output[0] == 1 && output[5] == 6 && output[6] == 0 && output[15] == 0, "initialized silence on starvation");
    check(audio.consumed() == 3 && audio.missing() == 5, "starvation accounting");
}
struct Lifetime {
    static std::atomic<unsigned> deleted;
    uint64_t value;
    explicit Lifetime(uint64_t n) : value(n) {}
    ~Lifetime() { if (inCallback) std::abort(); ++deleted; }
};
std::atomic<unsigned> Lifetime::deleted{0};
static void exchangeTests() {
    {
        SnapshotExchange<Lifetime> exchange;
        exchange.publish(std::make_unique<Lifetime>(1)); exchange.publish(std::make_unique<Lifetime>(2));
        check(Lifetime::deleted == 1, "superseded pending reclaimed by control");
        Lifetime* first; { CallbackScope guard; first = exchange.acquireAtBoundary(); }
        check(first->value == 2, "latest snapshot adopted");
        // Fill retirement queue without collecting. publish() collects, so concurrent producer/consumer
        // stress below covers reclamation while the callback reads the current generation.
        std::atomic<bool> done{false}, failed{false};
        std::thread control([&] { for (uint64_t i = 3; i < 20003; ++i) exchange.publish(std::make_unique<Lifetime>(i)); done = true; });
        std::thread audio([&] { uint64_t last = 2; do {
            CallbackScope guard; Lifetime* current = exchange.acquireAtBoundary();
            if (!current || current->value < last) failed = true; else last = current->value;
        } while (!done.load()); });
        control.join(); audio.join(); exchange.collect();
        check(!failed, "concurrent snapshots never go backwards or free on callback");
    }
    check(Lifetime::deleted == 20002, "every published snapshot reclaimed exactly once");
}
static void tempoTests() {
    TempoMap map({{7680, 250000}, {0, 500000}, {3840, 1000000}});
    check(map.sampleAtTick(3840, 48000) == 96000 && map.sampleAtTick(8640, 48000) == 300000, "tempo changes");
    check(map.tickAtSample(96000, 48000) == 3840, "inverse tempo boundary");
    std::mt19937_64 random(91417);
    std::vector<TempoEvent> events; std::vector<__int128> prefix;
    __int128 total = 0;
    for (int64_t i = 0; i < 10000; ++i) { events.push_back({i * 7, 600001 + i % 3}); prefix.push_back(total); total += 7 * events.back().microsPerQuarter; }
    TempoMap dense(events);
    for (int rate : {8000, 44100, 48000, 96000, 192000, 384000}) {
        for (unsigned i = 0; i < 10000; ++i) {
            int64_t tick = static_cast<int64_t>(random() % static_cast<uint64_t>(maxTick));
            size_t segment = std::min(events.size() - 1, static_cast<size_t>(tick / 7));
            __int128 expected = (prefix[segment] + static_cast<__int128>(tick - events[segment].tick) * events[segment].microsPerQuarter) * rate;
            expected = (expected + 480000000) / 960000000;
            check(dense.sampleAtTick(tick, rate) == expected, "exact native vs independent 128-bit oracle");
            check(dense.tickAtSample(static_cast<int64_t>(expected), rate) == tick, "large tick inverse");
        }
    }
    rejects([] { TempoMap invalid({{0, 0}}); }, "zero tempo");
    rejects([] { TempoMap invalid({{0, 500000}, {0, 600000}}); }, "duplicate tempo");
    check(TempoMap({{0, 60000000}}).sampleAtTick(maxTick, 384000) == 26388279066624000LL, "maximum supported integer conversion");
}
static void pcmTests() {
    auto p = basic(); p.tracks[0].regions[0].startTick = 960; p.tracks[0].regions[0].lengthTick = 960;
    PreparedPlayback state(std::move(p), 64); PcmRenderer renderer(state); std::vector<float> data(96000);
    { CallbackScope guard; renderer.render(data.data(), 48000); }
    check(data[47999] == 0 && near(data[48000], .25f * std::sqrt(.5f)), "sample-accurate attack");
    check(near(data[95998], .25f * std::sqrt(.5f)), "last active frame");
    { CallbackScope guard; renderer.render(data.data(), 64); }
    check(data[0] == 0 && data[127] == 0, "half-open sample stop");
    check(renderer.seekTick(1440), "midregion seek accepted");
    { CallbackScope guard; renderer.render(data.data(), 1); }
    check(near(data[0], .25f * std::sqrt(.5f)), "seek into source");
    check(!renderer.seekTick(-1), "invalid seek rejected");
    auto fade = basic(); fade.tracks[0].regions[0].fadeInTick = 960; fade.tracks[0].regions[0].fadeOutTick = 960;
    PreparedPlayback fs(std::move(fade), 512); PcmRenderer fr(fs);
    { CallbackScope guard; fr.render(data.data(), 24000); }
    check(data[0] == 0 && data[47998] > .1767f && data[47998] < .1768f, "fade in duration");
    auto clipped = basic(8); PreparedPlayback cs(std::move(clipped), 64); PcmRenderer cr(cs); Meter meter;
    { CallbackScope guard; meter = cr.render(data.data(), 64); }
    check(meter.clipped && meter.peakLeft <= 1 && meter.peakRight <= 1 && meter.rms <= 1, "safe master and meters");
    auto excessive = basic(); excessive.sources[0].frames[0] = Stereo{std::numeric_limits<float>::max(), std::numeric_limits<float>::max()};
    excessive.tracks[0].regions[0].gain = 64; excessive.tracks[0].volume = 64; excessive.tracks[0].pan = -1;
    PreparedPlayback excessiveState(std::move(excessive), 64); PcmRenderer excessiveRenderer(excessiveState);
    { CallbackScope guard; meter = excessiveRenderer.render(data.data(), 1); }
    check(std::isfinite(data[0]) && std::isfinite(data[1]) && meter.clipped && std::isfinite(meter.rms), "overflowing finite PCM cannot poison master output or meters");
    auto crowded = basic();
    for (int64_t i = 1; i <= 5000; ++i) crowded.tracks[0].regions.push_back(PcmRegion{i, 1});
    PreparedPlayback crowdedState(std::move(crowded), 64); PcmRenderer crowdedRenderer(crowdedState); crowdedRenderer.seekTick(6000);
    { CallbackScope guard; crowdedRenderer.render(data.data(), 64); }
    check(near(data[0], .25f * std::sqrt(.5f)), "interval scheduling retains long clip while pruning expired overlapping clips");
    auto invalid = basic(); invalid.tracks[0].regions[0].source = 9;
    rejects([&] { PreparedPlayback bad(std::move(invalid), 64); }, "bad source reference");
    auto unordered = basic(); unordered.tracks[0].regions = {PcmRegion{960, 960}, PcmRegion{0, 960}};
    rejects([&] { PreparedPlayback bad(std::move(unordered), 64); }, "unordered region schedule");
    auto cyclic = basic(2); cyclic.tracks[0].output = 1; cyclic.tracks[1].output = 0;
    rejects([&] { PreparedPlayback bad(std::move(cyclic), 64); }, "routing feedback");
    auto shortCycle = basic(); shortCycle.tempo = TempoMap({{0, 60000}}); shortCycle.sampleRate = 8000; shortCycle.cycle = true; shortCycle.cycleEndTick = 1;
    // Ticks 1 and 2 both round to sample 1: a nonempty tick range can be empty in samples.
    shortCycle.cycleStartTick = 1; shortCycle.cycleEndTick = 2;
    rejects([&] { PreparedPlayback bad(std::move(shortCycle), 64); }, "sub-sample cycle");
}
static void routingTests() {
    auto p = basic(2); p.tracks[0].volume = .5f; p.tracks[0].pan = -1; p.tracks[0].output = 1;
    p.tracks[1].regions.clear(); p.tracks[1].pan = 1;
    // pre-fader send carries right-channel input even when the main path is hard left.
    p.tracks[0].sends.push_back(SendRoute{1, .5f, true});
    PreparedPlayback state(std::move(p), 64); PcmRenderer renderer(state); float data[128];
    { CallbackScope guard; renderer.render(data, 64); }
    check(near(data[0], 0) && near(data[1], .25f), "routing topology/pre-fader send/pan");
    auto solo = basic(3); solo.tracks[0].solo = true; solo.tracks[0].output = 2; solo.tracks[2].regions.clear();
    PreparedPlayback ss(std::move(solo), 64); PcmRenderer sr(ss);
    { CallbackScope guard; sr.render(data, 64); }
    check(near(data[0], .125f) && near(data[1], .25f), "solo keeps downstream buses and removes unrelated track");
}
static void parityTests() {
    auto a = basic(3); a.cycle = true; a.cycleStartTick = 960; a.cycleEndTick = 1920;
    a.tracks[0].regions[0].startTick = 480; // crossing the cycle start
    for (size_t i = 0; i < a.sources[0].frames.size(); ++i) a.sources[0].frames[i] = Stereo{static_cast<float>(std::sin(i * .013) * .1), static_cast<float>(std::cos(i * .021) * .1)};
    a.tracks[1].regions[0].repeatSource = true; a.tracks[1].regions[0].sourceOffsetFrame = 249000;
    auto b = a;
    PreparedPlayback as(std::move(a), 64), bs(std::move(b), 512); PcmRenderer realtime(as), offline(bs);
    constexpr size_t frames = 130007; std::vector<float> rt(frames * 2), bounce(frames * 2);
    realtime.seekTick(960); offline.seekTick(960);
    { CallbackScope guard;
        for (size_t i = 0; i < frames;) { uint32_t count = static_cast<uint32_t>(std::min<size_t>(64, frames - i)); realtime.render(rt.data() + i * 2, count); i += count; }
        offline.render(bounce.data(), frames);
    }
    check(std::memcmp(rt.data(), bounce.data(), rt.size() * sizeof(float)) == 0, "bit-identical PCM output across realtime/offline chunk sizes and cycle wraps");
    check(realtime.positionSample() == offline.positionSample(), "cycle transport parity");
    // Boundary starts and overlap across a tempo change.
    PlaybackProject tempo({{0, 500000}, {960, 1000000}}); tempo.sources.resize(1); tempo.sources[0].frames.resize(100000, Stereo{.1f, .1f});
    PcmTrack track; track.regions = {PcmRegion{480, 1440}, PcmRegion{960, 480}}; tempo.tracks.push_back(track);
    PreparedPlayback ts(std::move(tempo), 64); PcmRenderer tr(ts); std::vector<float> samples(144002);
    { CallbackScope guard; tr.render(samples.data(), 72001); }
    check(samples[23999] == 0 && samples[24000] > 0, "tempo-map region start");
    check(samples[48000] > samples[47998] && samples[144000] == 0, "overlap and stop through tempo change");
}
static void engineTests() {
    PlaybackEngine engine; float output[128]; PlaybackStatus status{};
    engine.command({TransportAction::Seek, 960});
    { CallbackScope guard; engine.callback(output, 64); }
    engine.publish(std::make_unique<PreparedPlayback>(basic(), 64));
    engine.command({TransportAction::Play});
    { CallbackScope guard; engine.callback(output, 64); }
    check(output[0] != 0 && engine.poll(status) && status.sample == 24064, "seek before publication, then play");
    engine.publish(std::make_unique<PreparedPlayback>(basic(), 64));
    { CallbackScope guard; engine.callback(output, 64); }
    check(engine.poll(status) && status.sample == 24128, "snapshot adoption preserves fractional tick phase exactly");
    engine.command({TransportAction::Pause});
    { CallbackScope guard; engine.callback(output, 64); }
    check(output[0] == 0 && engine.poll(status) && !status.playing && status.sample == 24128, "pause retains cursor");
    engine.publish(std::make_unique<PreparedPlayback>(basic(2), 64));
    engine.command({TransportAction::Stop});
    { CallbackScope guard; engine.callback(output, 64); }
    check(engine.poll(status) && status.sample == 0 && !status.playing, "stop resets on snapshot adoption");
    check(!engine.command({TransportAction::Seek, -1}), "invalid transport command");
    for (unsigned i = 0; i < 128; ++i) check(engine.command({TransportAction::Play}), "bounded command queue accepts capacity");
    check(!engine.command({TransportAction::Stop}), "full command queue gives explicit failure");
    engine.collect();
}
static void benchmark() {
    auto p = basic(32); for (auto& track : p.tracks) track.volume = .025f;
    PreparedPlayback state(std::move(p), 64); PcmRenderer renderer(state); float output[128];
    constexpr unsigned blocks = 20000;
    auto start = std::chrono::steady_clock::now(); const auto cpuStart = std::clock();
    { CallbackScope guard; for (unsigned i = 0; i < blocks; ++i) { if (i % 3000 == 0) renderer.seekTick(0); renderer.render(output, 64); } }
    const auto nanos = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).count();
    std::cout << "HOST_ONLY 32 resident-PCM tracks / 64 frames: " << nanos / blocks << " ns/block mean, "
        << 100.0 * static_cast<double>(nanos) / (blocks * 64.0 / 48000.0 * 1e9) << "% of 48k callback wall-time budget, "
        << 100.0 * (std::clock() - cpuStart) / CLOCKS_PER_SEC / (blocks * 64.0 / 48000.0)
        << "% process CPU per audio duration; not an Android xrun result\n";
}
int main() {
    try {
        ringTests(); exchangeTests(); tempoTests(); pcmTests(); routingTests(); parityTests(); engineTests(); benchmark();
        check(callbackAllocations == 0 && callbackFrees == 0, "no allocations or frees in marked callback execution");
        std::cout << passed << " native playback checks passed; callback allocations/frees: " << callbackAllocations << "/" << callbackFrees << "\n";
    } catch (const std::exception& e) { std::cerr << "FAIL: " << e.what() << "\n"; return 1; }
}
