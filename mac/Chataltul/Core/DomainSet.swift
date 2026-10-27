import Foundation

/// A set of domains kept as 64-bit hashes of their names: small, quick to search, and the names
/// themselves are not kept anywhere on this Mac.
public struct DomainSet: Equatable {
    /// Sorted, no repeats.
    public private(set) var hashes: [UInt64]

    public init(hashes: [UInt64] = []) {
        var a = hashes
        a.sort()
        var out = [UInt64](); out.reserveCapacity(a.count)
        for h in a where out.last != h { out.append(h) }
        self.hashes = out
    }
    public init(domains: [String]) { self.init(hashes: domains.map { DomainSet.hash($0) }) }

    public var count: Int { hashes.count }

    /// FNV-1a over the lowercase bytes, then mixed so near names land far apart. This must never change: the
    /// copies on disk are hashed with it.
    public static func hash(_ b: UnsafeBufferPointer<UInt8>) -> UInt64 {
        var h: UInt64 = 0xcbf29ce484222325
        for c in b {
            h ^= UInt64((c >= 0x41 && c <= 0x5A) ? c + 32 : c)
            h = h &* 0x100000001b3
        }
        h ^= h >> 33; h = h &* 0xff51afd7ed558ccd
        h ^= h >> 33; h = h &* 0xc4ceb9fe1a85ec53
        h ^= h >> 33
        return h
    }
    public static func hash(_ s: String) -> UInt64 {
        var s = s
        return s.withUTF8 { hash($0) }
    }

    public func contains(hash h: UInt64) -> Bool {
        hashes.withUnsafeBufferPointer { a in
            var lo = 0, hi = a.count
            while lo < hi {
                let mid = (lo + hi) / 2
                if a[mid] < h { lo = mid + 1 } else { hi = mid }
            }
            return lo < a.count && a[lo] == h
        }
    }
    public func contains(_ domain: String) -> Bool { contains(hash: DomainSet.hash(domain)) }

    /// The shortest part of a host, from its last two names up to the whole host, that is in the set:
    /// "m.tiktok.com" gives "tiktok.com" when that is listed. A single name ("com") never counts.
    public func firstSuffix(of host: String) -> String? {
        let b = Array(host.utf8)
        var starts = [0]
        for (i, c) in b.enumerated() where c == 0x2E { starts.append(i + 1) }
        if starts.count < 2 { return nil }
        var i = starts.count - 2
        while i >= 0 {
            let s = starts[i]
            if b.count > s {
                let found = b.withUnsafeBufferPointer { p in contains(hash: DomainSet.hash(UnsafeBufferPointer(rebasing: p[s...]))) }
                if found { return String(decoding: b[s...], as: UTF8.self) }
            }
            i -= 1
        }
        return nil
    }

    // ---------------------------------------------------------------- on disk

    /// "CHD1", the count (4 bytes), then the hashes, all little-endian (every Mac is).
    public func serialized() -> Data {
        var d = Data(capacity: 8 + hashes.count * 8)
        d.append(contentsOf: Array("CHD1".utf8))
        var n = UInt32(hashes.count).littleEndian
        withUnsafeBytes(of: &n) { d.append(contentsOf: $0) }
        hashes.withUnsafeBytes { d.append(contentsOf: $0) }
        return d
    }

    /// nil if the bytes aren't a copy written by `serialized` (cut short, or not sorted).
    public init?(serialized d: Data) {
        guard d.count >= 8, d.prefix(4) == Data("CHD1".utf8) else { return nil }
        let n = d.withUnsafeBytes { Int(UInt32(littleEndian: $0.loadUnaligned(fromByteOffset: 4, as: UInt32.self))) }
        guard d.count == 8 + n * 8 else { return nil }
        var a = [UInt64](repeating: 0, count: n)
        a.withUnsafeMutableBytes { dst in
            d.withUnsafeBytes { src in dst.copyMemory(from: UnsafeRawBufferPointer(rebasing: src[8...])) }
        }
        for i in 1..<max(1, n) where a[i - 1] >= a[i] { return nil }
        hashes = a
    }
}

/// Reads the lists the web publishes for blocking: hosts files (`0.0.0.0 example.com`), plain lists of
/// domains, and AdGuard / Adblock rules (`||example.com^`). Only whole domains count. Comments, exceptions
/// (`@@`), paths, wildcards, page rules, IP addresses and local names are skipped.
public enum ListParser {
    public struct Result {
        public var hashes: [UInt64]
        /// It starts like a web page, so it isn't a list.
        public var webPage: Bool
    }

    public static func parse(_ data: Data) -> Result {
        data.withUnsafeBytes { raw -> Result in
            let b = raw.bindMemory(to: UInt8.self)
            let n = b.count
            let bom = n >= 3 && b[0] == 0xEF && b[1] == 0xBB && b[2] == 0xBF ? 3 : 0
            var i = bom
            while i < n, isSpace(b[i]) { i += 1 }
            let page = i < n && b[i] == 0x3C   // "<"
            var out = [UInt64](); out.reserveCapacity(page ? 0 : n / 22)
            if !page {
                var start = bom
                while start <= n {
                    var end = start
                    while end < n, b[end] != 0x0A { end += 1 }
                    if let r = domain(in: b, from: start, to: end) { out.append(DomainSet.hash(UnsafeBufferPointer(rebasing: b[r]))) }
                    start = end + 1
                }
            }
            return Result(hashes: out, webPage: page)
        }
    }

    /// The domains in a text, lowercase, for tests and small lists.
    public static func domains(in text: String) -> [String] {
        var text = text
        return text.withUTF8 { b -> [String] in
            var out = [String]()
            var start = 0
            let n = b.count
            while start <= n {
                var end = start
                while end < n, b[end] != 0x0A { end += 1 }
                if let r = domain(in: b, from: start, to: end) { out.append(String(decoding: UnsafeBufferPointer(rebasing: b[r]), as: UTF8.self).lowercased()) }
                start = end + 1
            }
            return out
        }
    }

    // ---------------------------------------------------------------- one line

    @inline(__always) private static func isSpace(_ c: UInt8) -> Bool { c == 0x20 || c == 0x09 || c == 0x0D || c == 0x0A }
    @inline(__always) private static func isDigit(_ c: UInt8) -> Bool { c >= 0x30 && c <= 0x39 }
    @inline(__always) private static func isDomainByte(_ c: UInt8) -> Bool {
        (c >= 0x61 && c <= 0x7A) || (c >= 0x41 && c <= 0x5A) || isDigit(c) || c == 0x2D || c == 0x2E || c == 0x5F
    }

    /// Where the domain is in this line, or nil if the line names none.
    static func domain(in b: UnsafeBufferPointer<UInt8>, from: Int, to: Int) -> Range<Int>? {
        var lo = from, hi = to
        while lo < hi, isSpace(b[lo]) { lo += 1 }
        while hi > lo, isSpace(b[hi - 1]) { hi -= 1 }
        if lo >= hi { return nil }
        let c = b[lo]
        if c == 0x23 || c == 0x21 || c == 0x5B || c == 0x40 { return nil }   // # ! [ @
        if c == 0x7C {   // |
            guard hi - lo > 3, b[lo + 1] == 0x7C else { return nil }
            var e = lo + 2
            while e < hi, isDomainByte(b[e]) { e += 1 }
            guard e < hi, b[e] == 0x5E else { return nil }   // ^
            if e + 1 < hi {
                // only the options that still mean the whole domain
                let tail = String(decoding: UnsafeBufferPointer(rebasing: b[(e + 1)..<hi]), as: UTF8.self)
                guard tail == "$important" || tail == "$all" else { return nil }
            }
            return named(b, lo + 2, e)
        }
        if let h = (lo..<hi).first(where: { b[$0] == 0x23 }) {   // a comment after the entry
            // `example.org##.banner` and its kin are page rules, not a domain
            if h + 1 < hi, b[h + 1] == 0x23 || b[h + 1] == 0x40 || b[h + 1] == 0x3F || b[h + 1] == 0x24 || b[h + 1] == 0x25 { return nil }
            hi = h
            while hi > lo, isSpace(b[hi - 1]) { hi -= 1 }
            if lo >= hi { return nil }
        }
        var t1 = lo
        while t1 < hi, !isSpace(b[t1]) { t1 += 1 }
        if t1 == hi { return named(b, lo, hi) }   // one word: a plain list
        // a hosts line: an address, then the name
        guard isAddress(b, lo, t1) else { return nil }
        var s2 = t1
        while s2 < hi, isSpace(b[s2]) { s2 += 1 }
        var t2 = s2
        while t2 < hi, !isSpace(b[t2]) { t2 += 1 }
        return named(b, s2, t2)
    }

    private static func isAddress(_ b: UnsafeBufferPointer<UInt8>, _ lo: Int, _ hi: Int) -> Bool {
        var colon = false, digitsDots = true
        for i in lo..<hi {
            let c = b[i]
            if c == 0x3A { colon = true; digitsDots = false }
            else if !(isDigit(c) || c == 0x2E) { digitsDots = false; if !((c >= 0x61 && c <= 0x66) || (c >= 0x41 && c <= 0x46)) { return false } }
        }
        return colon || digitsDots
    }

    /// The domain at b[lo..<hi] if it is one: letters, digits, dashes and underscores in names with dots between,
    /// at least two names, not an address, not a local name. A leading "www." is dropped, since a page's "www."
    /// is dropped too when it is looked up.
    private static func named(_ b: UnsafeBufferPointer<UInt8>, _ lo: Int, _ hi: Int) -> Range<Int>? {
        let len = hi - lo
        guard len >= 3, len <= 253 else { return nil }
        var dots = 0, numeric = true
        for i in lo..<hi {
            let c = b[i]
            guard isDomainByte(c) else { return nil }
            if c == 0x2E {
                dots += 1
                if i == lo || i == hi - 1 || b[i - 1] == 0x2E { return nil }
            } else if !isDigit(c) { numeric = false }
        }
        guard dots >= 1, !numeric else { return nil }
        var l = lo
        if len > 4, (b[lo] | 0x20) == 0x77, (b[lo + 1] | 0x20) == 0x77, (b[lo + 2] | 0x20) == 0x77, b[lo + 3] == 0x2E,
           (lo + 4..<hi).contains(where: { b[$0] == 0x2E }) { l = lo + 4 }
        if hi - l == 21 || hi - l == 10 {   // only a name that long can be one of the local ones
            let name = String(decoding: UnsafeBufferPointer(rebasing: b[l..<hi]), as: UTF8.self).lowercased()
            if name == "localhost.localdomain" || name == "local.host" { return nil }
        }
        return l..<hi
    }
}
