import Foundation

/// What is known about one downloaded list.
public struct ListStatus: Codable, Equatable {
    public var count = 0
    /// When the copy on this Mac was made, and when it was last checked against the web (ms).
    public var updated = 0
    public var checked = 0
    /// The last try, good or bad.
    public var tried = 0
    public var etag: String?
    public var lastModified: String?
    /// Why the last try failed, in a few words; nil when it worked.
    public var error: String?

    public init() {}
    public init(from decoder: Decoder) throws {
        let c = try decoder.keyed()
        count = c.v("count", 0); updated = c.v("updated", 0); checked = c.v("checked", 0); tried = c.v("tried", 0)
        etag = c.o("etag"); lastModified = c.o("lastModified"); error = c.o("error")
    }
}

/// What a download came back with.
public enum Fetched {
    /// A file on this Mac that is the caller's to delete.
    case file(URL, etag: String?, lastModified: String?)
    case notModified
    case failed(String)
}

/// Gets a list from the web. Calls `done` on the main queue.
public protocol ListFetcher {
    func fetch(_ url: URL, etag: String?, lastModified: String?, maxBytes: Int, done: @escaping (Fetched) -> Void)
}

/// Something that can say which of the chosen categories a site belongs to.
public protocol DomainMatcher {
    /// The listed domain this host falls under in one of these categories, or nil.
    func match(host: String, categories: [String]) -> String?
}

/// How a category stands, for its row.
public enum CategoryState: Equatable {
    /// Not on this Mac yet; about this many KB to download (0: not known).
    case none(sizeKB: Int)
    case working
    case ready(sites: Int, updated: Int)
    /// The last try failed; `sites` are already here from before.
    case failed(String, sites: Int)
}

/// The ready-made lists on this Mac: downloaded when a category is switched on, kept as hashes in `lists/`,
/// looked at again about once a week. A bad answer (an error, a web page, a list that is nearly empty) never
/// replaces the copy already here.
public final class CategoryStore: DomainMatcher {
    public static let refreshAfterMs = 7 * 86_400_000
    public static let retryAfterMs = 3_600_000
    /// The most that is downloaded for one list.
    public static let maxBytes = 64 * 1024 * 1024

    public let folder: URL
    /// The lists the person added by link (the app keeps them in its settings).
    public var custom: [ListCategory] = []
    public var onChange: () -> Void = {}
    public private(set) var sets: [String: DomainSet] = [:]
    public private(set) var status: [String: ListStatus] = [:]
    public private(set) var working: Set<String> = []

    private let fetcher: ListFetcher
    private let network: Bool
    private let clock: () -> Int
    private let queue = DispatchQueue(label: "xyz.tiresia.chataltul.lists", qos: .utility)
    private var statusFile: URL { folder.appendingPathComponent("status.json") }

    /// `network: false` never goes to the web (tests that fake the lists).
    public init(folder: URL, fetcher: ListFetcher = URLSessionFetcher(), network: Bool = true, clock: @escaping () -> Int = nowMs) {
        self.folder = folder; self.fetcher = fetcher; self.network = network; self.clock = clock
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        load()
    }

    private func file(for sourceID: String) -> URL { folder.appendingPathComponent(sourceID + ".chd") }

    /// What is on disk from before.
    private func load() {
        guard let d = try? Data(contentsOf: statusFile), let s = try? JSONDecoder().decode([String: ListStatus].self, from: d) else { return }
        for (id, st) in s {
            guard !id.contains("/"), let data = try? Data(contentsOf: file(for: id), options: .mappedIfSafe), let set = DomainSet(serialized: data) else { continue }
            sets[id] = set
            var st = st; st.count = set.count
            status[id] = st
        }
    }

    private func saveStatus() {
        let e = JSONEncoder(); e.outputFormatting = [.sortedKeys]
        if let d = try? e.encode(status) { try? d.write(to: statusFile, options: .atomic) }
    }

    // ---------------------------------------------------------------- what is where

    public func category(_ id: String) -> ListCategory? { Catalog.category(id, custom: custom) }
    public func sources(of ids: [String]) -> [ListSource] {
        var seen = Set<String>(), out = [ListSource]()
        for id in ids { for s in category(id)?.sources ?? [] where seen.insert(s.id).inserted { out.append(s) } }
        return out
    }

    public func state(of id: String) -> CategoryState {
        guard let cat = category(id) else { return .none(sizeKB: 0) }
        if cat.sources.contains(where: { working.contains($0.id) }) { return .working }
        let have = cat.sources.filter { sets[$0.id] != nil }
        let sites = have.reduce(0) { $0 + (sets[$1.id]?.count ?? 0) }
        if let why = cat.sources.compactMap({ status[$0.id]?.error }).first { return .failed(why, sites: sites) }
        if have.count == cat.sources.count {
            return .ready(sites: sites, updated: have.map { status[$0.id]?.updated ?? 0 }.min() ?? 0)
        }
        return .none(sizeKB: cat.sources.filter { sets[$0.id] == nil }.reduce(0) { $0 + $1.sizeKB })
    }

    public func match(host: String, categories: [String]) -> String? {
        for s in sources(of: categories) {
            if let set = sets[s.id], let d = set.firstSuffix(of: host) { return d }
        }
        return nil
    }

    /// Puts a list in place without going to the web (tests, and the screenshots).
    public func install(_ domains: [String], source id: String) {
        let set = DomainSet(domains: domains)
        sets[id] = set
        var st = ListStatus()
        st.count = set.count; st.updated = clock(); st.checked = st.updated; st.tried = st.updated
        status[id] = st
        onChange()
    }

    /// Shows a list as downloading, or as failed with this reason (the screenshots).
    public func simulate(source id: String, working isWorking: Bool = false, error: String? = nil) {
        if isWorking { working.insert(id) } else { working.remove(id) }
        if let error { var st = status[id] ?? ListStatus(); st.error = error; status[id] = st }
        onChange()
    }

    /// Forgets the copy of these lists (a list the person took away).
    public func remove(sources ids: [String]) {
        for id in ids where !id.contains("/") {
            sets[id] = nil; status[id] = nil
            try? FileManager.default.removeItem(at: file(for: id))
        }
        saveStatus()
        onChange()
    }

    // ---------------------------------------------------------------- downloading

    /// Brings the lists of these categories up to date: the ones not here yet, the ones last checked over a week
    /// ago, and (after an hour) the ones that failed. `force` fetches them all now. `done` is called on the main
    /// queue when every one has an answer.
    public func refresh(_ ids: [String], force: Bool = false, done: (() -> Void)? = nil) {
        guard network else { done?(); return }
        let due = sources(of: ids).filter { shouldFetch($0, force: force) }
        var remaining = due.count
        if remaining == 0 { done?(); return }
        for s in due {
            fetch(s) {
                remaining -= 1
                if remaining == 0 { done?() }
            }
        }
    }

    private func shouldFetch(_ s: ListSource, force: Bool) -> Bool {
        if working.contains(s.id) { return false }
        if force { return true }
        guard let st = status[s.id] else { return true }
        let now = clock()
        if st.error != nil { return now - st.tried >= Self.retryAfterMs }
        return sets[s.id] == nil || now - st.checked >= Self.refreshAfterMs
    }

    private func fetch(_ s: ListSource, then finished: @escaping () -> Void) {
        guard let url = URL(string: s.url) else { fail(s, "Not an address"); finished(); return }
        working.insert(s.id)
        onChange()
        // a copy is only reused if it is really here
        let have = sets[s.id] != nil ? status[s.id] : nil
        fetcher.fetch(url, etag: have?.etag, lastModified: have?.lastModified, maxBytes: Self.maxBytes) { [weak self] result in
            guard let self else { return }
            switch result {
            case .notModified:
                var st = self.status[s.id] ?? ListStatus()
                let now = self.clock()
                st.checked = now; st.tried = now; st.error = nil
                self.status[s.id] = st
                self.working.remove(s.id)
                self.saveStatus(); self.onChange(); finished()
            case .failed(let why):
                self.fail(s, why); finished()
            case .file(let tmp, let etag, let modified):
                let dest = self.file(for: s.id)
                self.queue.async {
                    let built = Self.build(s, from: tmp)
                    try? FileManager.default.removeItem(at: tmp)
                    if case .success(let set) = built { try? set.serialized().write(to: dest, options: .atomic) }
                    DispatchQueue.main.async {
                        switch built {
                        case .failure(let why): self.fail(s, why.description)
                        case .success(let set):
                            let now = self.clock()
                            var st = ListStatus()
                            st.count = set.count; st.updated = now; st.checked = now; st.tried = now; st.etag = etag; st.lastModified = modified
                            self.sets[s.id] = set
                            self.status[s.id] = st
                            self.working.remove(s.id)
                            self.saveStatus(); self.onChange()
                        }
                        finished()
                    }
                }
            }
        }
    }

    /// A try that failed: the copy already here (if any) stays.
    private func fail(_ s: ListSource, _ why: String) {
        var st = status[s.id] ?? ListStatus()
        st.error = why; st.tried = clock()
        status[s.id] = st
        working.remove(s.id)
        saveStatus(); onChange()
    }

    /// The downloaded file as a set, or why it isn't one.
    static func build(_ s: ListSource, from file: URL) -> Swift.Result<DomainSet, ListFailure> {
        let data: Data
        if let member = s.member {
            guard let d = Tar.read(member, from: file, maxBytes: 256 * 1024 * 1024) else { return .failure(ListFailure("Couldn't open it")) }
            data = d
        } else {
            guard let d = try? Data(contentsOf: file, options: .mappedIfSafe) else { return .failure(ListFailure("Couldn't read it")) }
            data = d
        }
        let r = ListParser.parse(data)
        if r.webPage { return .failure(ListFailure("Not a list of sites")) }
        let set = DomainSet(hashes: r.hashes)
        if set.count < max(1, s.floor) { return .failure(ListFailure("Too few sites")) }
        return .success(set)
    }
}

/// Why a list was refused, in a few words.
public struct ListFailure: Error, CustomStringConvertible {
    public let description: String
    public init(_ s: String) { description = s }
}

/// One file out of a .tar.gz, read to memory and nothing written to disk (so nothing in the archive can name
/// a place to be put).
enum Tar {
    static func read(_ member: String, from archive: URL, maxBytes: Int) -> Data? {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/tar")
        p.arguments = ["-xzOf", archive.path, member]
        p.currentDirectoryURL = FileManager.default.temporaryDirectory
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        p.standardInput = FileHandle.nullDevice
        do { try p.run() } catch { return nil }
        var data = Data()
        let h = out.fileHandleForReading
        while true {
            let chunk = h.availableData
            if chunk.isEmpty { break }
            data.append(chunk)
            if data.count > maxBytes { p.terminate(); return nil }
        }
        p.waitUntilExit()
        return p.terminationStatus == 0 && !data.isEmpty ? data : nil
    }
}

/// Downloads with URLSession: https only (http only to this Mac, for tests), no cookies, nothing sent but
/// "have you changed" headers, cut off at `maxBytes`.
public final class URLSessionFetcher: ListFetcher {
    public init() {}

    public func fetch(_ url: URL, etag: String?, lastModified: String?, maxBytes: Int, done: @escaping (Fetched) -> Void) {
        let local = url.host == "127.0.0.1" || url.host == "localhost"
        guard url.scheme == "https" || (url.scheme == "http" && local) else {
            DispatchQueue.main.async { done(.failed("Not a secure address")) }
            return
        }
        var req = URLRequest(url: url)
        req.cachePolicy = .reloadIgnoringLocalCacheData
        req.timeoutInterval = 30
        req.httpShouldHandleCookies = false
        req.setValue("Chataltul", forHTTPHeaderField: "User-Agent")
        if let etag { req.setValue(etag, forHTTPHeaderField: "If-None-Match") }
        if let lastModified { req.setValue(lastModified, forHTTPHeaderField: "If-Modified-Since") }
        let job = Download(maxBytes: maxBytes, done: done)
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForResource = 300
        config.httpCookieStorage = nil
        let session = URLSession(configuration: config, delegate: job, delegateQueue: nil)
        job.session = session
        session.downloadTask(with: req).resume()
    }

    private final class Download: NSObject, URLSessionDownloadDelegate {
        let maxBytes: Int
        let done: (Fetched) -> Void
        var session: URLSession?
        var kept: URL?
        var tooBig = false
        var finished = false

        init(maxBytes: Int, done: @escaping (Fetched) -> Void) { self.maxBytes = maxBytes; self.done = done }

        func urlSession(_ s: URLSession, downloadTask t: URLSessionDownloadTask, didWriteData _: Int64, totalBytesWritten n: Int64, totalBytesExpectedToWrite _: Int64) {
            if Int(n) > maxBytes { tooBig = true; t.cancel() }
        }

        func urlSession(_ s: URLSession, downloadTask t: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
            // the system deletes this file when the method returns
            let mine = FileManager.default.temporaryDirectory.appendingPathComponent("chataltul-list-\(UUID().uuidString)")
            if (try? FileManager.default.moveItem(at: location, to: mine)) != nil { kept = mine }
        }

        func urlSession(_ s: URLSession, task t: URLSessionTask, didCompleteWithError error: Error?) {
            guard !finished else { return }
            finished = true
            let http = t.response as? HTTPURLResponse
            let result: Fetched
            if tooBig { result = .failed("Too big") }
            else if let error {
                let code = (error as? URLError)?.code
                result = .failed(code == .cancelled ? "Stopped" : "Couldn't reach it")
            }
            else if let http, http.statusCode == 304 { result = .notModified }
            else if let http, http.statusCode == 200, let kept { result = .file(kept, etag: http.value(forHTTPHeaderField: "ETag"), lastModified: http.value(forHTTPHeaderField: "Last-Modified")) }
            else if let http, http.statusCode != 200 { result = .failed("Server said \(http.statusCode)") }
            else { result = .failed("Couldn't reach it") }
            if case .file = result {} else if let kept { try? FileManager.default.removeItem(at: kept) }
            s.finishTasksAndInvalidate()
            session = nil
            DispatchQueue.main.async { [done] in done(result) }
        }
    }
}
