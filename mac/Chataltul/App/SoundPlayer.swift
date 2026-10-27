import AppKit
import AVFoundation

/// Background sounds, up to two at once. The recordings are the web's own (web/audio/<file>.mp3): each is
/// downloaded the first time it is picked and kept in the app's folder, then decoded once and looped with no
/// seam. Brown and white noise are made on the Mac. A meow when a kitten comes home.
final class SoundPlayer {
    let store: Store
    private let engine = AVAudioEngine()
    private final class Voice {
        let id: String
        let node: AVAudioNode
        let mixer = AVAudioMixerNode()
        var vol: Float
        init(id: String, node: AVAudioNode, vol: Float) { self.id = id; self.node = node; self.vol = vol }
    }
    private var voices: [String: Voice] = [:]
    private var buffers: [String: AVAudioPCMBuffer] = [:]
    private var downloading = Set<String>()
    private var failedAt: [String: Date] = [:]
    private var decoding = Set<String>()
    private var fades: [String: Timer] = [:]
    private var meowPlayer: AVAudioPlayer?
    private var lastState: UiState

    /// Where the recordings are kept.
    var cache: URL { store.folder.appendingPathComponent("audio", isDirectory: true) }

    init(store: Store) {
        self.store = store
        lastState = store.state
        try? FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
    }

    /// Sounds are for a running block: they play while it runs and stop on a pause, a stop or the end.
    func update(_ st: UiState) {
        lastState = st
        let playing = st.view.active != nil && st.view.active?.paused == nil
        let want = playing ? store.catalog.playable(store.settings.mix, shop: st.owned) : []
        for (id, _) in voices where !want.contains(where: { $0.id == id }) { stop(id) }
        for m in want {
            if let v = voices[m.id] { setVolume(v, Float(m.vol) * 0.8, over: 0.2); continue }
            start(m)
        }
    }

    func refresh() { update(lastState) }

    func stopAll() { for id in Array(voices.keys) { stop(id, over: 0.05) } }

    /// The sounds that are playing now (for the self-test).
    var playing: [String] { Array(voices.keys).sorted() }

    // ---------------------------------------------------------------- one voice

    private func start(_ m: MixEntry) {
        guard let kind = store.catalog.kind(m.id) else { return }
        guard let file = kind.file else { begin(m, node: noise(kind.id)); return }
        if let buf = buffers[file] { begin(m, buffer: buf); return }
        let url = cache.appendingPathComponent("\(file).mp3")
        if FileManager.default.fileExists(atPath: url.path) { decode(url, file: file); return }
        download(file: file, to: url)
    }

    private func begin(_ m: MixEntry, node: AVAudioNode) {
        let v = Voice(id: m.id, node: node, vol: Float(m.vol) * 0.8)
        attach(v, format: nil)
    }

    private func begin(_ m: MixEntry, buffer: AVAudioPCMBuffer) {
        let player = AVAudioPlayerNode()
        let v = Voice(id: m.id, node: player, vol: Float(m.vol) * 0.8)
        attach(v, format: buffer.format)
        player.scheduleBuffer(buffer, at: nil, options: .loops, completionHandler: nil)
        player.play()
    }

    private func attach(_ v: Voice, format: AVAudioFormat?) {
        guard voices[v.id] == nil else { return }
        engine.attach(v.node); engine.attach(v.mixer)
        engine.connect(v.node, to: v.mixer, format: format)
        engine.connect(v.mixer, to: engine.mainMixerNode, format: nil)
        v.mixer.outputVolume = 0
        voices[v.id] = v
        if !engine.isRunning { do { try engine.start() } catch { NSLog("sound: \(error)") } }
        setVolume(v, v.vol, over: 2)
    }

    private func stop(_ id: String, over: TimeInterval = 1.2) {
        guard let v = voices.removeValue(forKey: id) else { return }
        fade(v, to: 0, over: over) { [weak self] in
            guard let self else { return }
            (v.node as? AVAudioPlayerNode)?.stop()
            self.engine.detach(v.node); self.engine.detach(v.mixer)
            if self.voices.isEmpty { self.engine.stop() }
        }
    }

    private func setVolume(_ v: Voice, _ vol: Float, over: TimeInterval) {
        v.vol = vol
        fade(v, to: vol, over: over)
    }

    private func fade(_ v: Voice, to target: Float, over: TimeInterval, done: (() -> Void)? = nil) {
        fades[v.id]?.invalidate()
        let from = v.mixer.outputVolume
        let steps = max(1, Int(over * 30))
        var i = 0
        let t = Timer(timeInterval: over / Double(steps), repeats: true) { [weak self] timer in
            i += 1
            v.mixer.outputVolume = from + (target - from) * Float(i) / Float(steps)
            if i >= steps { timer.invalidate(); self?.fades[v.id] = nil; done?() }
        }
        RunLoop.main.add(t, forMode: .common)
        fades[v.id] = t
    }

    // ---------------------------------------------------------------- recordings

    private func download(file: String, to url: URL) {
        if downloading.contains(file) { return }
        if let f = failedAt[file], Date().timeIntervalSince(f) < 60 { return }
        guard let remote = URL(string: store.settings.serverURL + "audio/\(file).mp3") else { return }
        downloading.insert(file)
        URLSession.shared.downloadTask(with: remote) { [weak self] tmp, response, error in
            DispatchQueue.main.async {
                guard let self else { return }
                self.downloading.remove(file)
                guard let tmp, (response as? HTTPURLResponse)?.statusCode == 200,
                      ((try? FileManager.default.attributesOfItem(atPath: tmp.path)[.size] as? Int) ?? 0) > 10_000 else {
                    self.failedAt[file] = Date()
                    return
                }
                try? FileManager.default.removeItem(at: url)
                do { try FileManager.default.moveItem(at: tmp, to: url) } catch { self.failedAt[file] = Date(); return }
                self.refresh()
            }
        }.resume()
    }

    private func decode(_ url: URL, file: String) {
        if decoding.contains(file) { return }
        decoding.insert(file)
        DispatchQueue.global(qos: .utility).async { [weak self] in
            var buffer: AVAudioPCMBuffer?
            if let f = try? AVAudioFile(forReading: url),
               let b = AVAudioPCMBuffer(pcmFormat: f.processingFormat, frameCapacity: AVAudioFrameCount(f.length)),
               (try? f.read(into: b)) != nil, b.frameLength > 0 {
                buffer = b
            }
            DispatchQueue.main.async {
                guard let self else { return }
                self.decoding.remove(file)
                if let buffer { self.buffers[file] = buffer }
                else { try? FileManager.default.removeItem(at: url); self.failedAt[file] = Date() }
                self.refresh()
            }
        }
    }

    // ---------------------------------------------------------------- noise and the meow

    private func noise(_ id: String) -> AVAudioNode {
        var last: Float = 0
        return AVAudioSourceNode { _, _, frames, list -> OSStatus in
            let abl = UnsafeMutableAudioBufferListPointer(list)
            for f in 0..<Int(frames) {
                let w = Float.random(in: -1...1)
                let s: Float
                if id == "brown" { last = (last + 0.02 * w) / 1.02; s = last * 3.2 } else { s = w * 0.5 }
                for b in abl { b.mData?.assumingMemoryBound(to: Float.self)[f] = s }
            }
            return noErr
        }
    }

    /// A kitten that comes home says hello.
    func meow(if cond: Bool) {
        guard cond, let name = store.catalog.meows.randomElement(),
              let url = Bundle.main.url(forResource: name, withExtension: "mp3", subdirectory: "audio") else { return }
        meowPlayer = try? AVAudioPlayer(contentsOf: url)
        meowPlayer?.volume = 0.7
        meowPlayer?.play()
    }
}
