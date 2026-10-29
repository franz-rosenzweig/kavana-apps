using System.Buffers.Binary;
using System.Text;

namespace Chataltul.Core;

/// <summary>
/// A set of domains kept as 64-bit hashes of their names: small, quick to search, and the names themselves are not
/// kept anywhere on this PC. The Mac's DomainSet.swift, hash for hash, so the same list gives the same file.
/// </summary>
public sealed class DomainSet : IEquatable<DomainSet>
{
    /// <summary>Sorted, no repeats.</summary>
    public ulong[] Hashes { get; }

    public DomainSet(IEnumerable<ulong>? hashes = null)
    {
        var a = (hashes ?? []).ToArray();
        Array.Sort(a);
        var out_ = new List<ulong>(a.Length);
        foreach (var h in a) if (out_.Count == 0 || out_[^1] != h) out_.Add(h);
        Hashes = [.. out_];
    }

    public static DomainSet Of(IEnumerable<string> domains) => new(domains.Select(Hash));

    public int Count => Hashes.Length;

    /// <summary>FNV-1a over the lowercase bytes, then mixed so near names land far apart. This must never change: the copies on disk are hashed with it.</summary>
    public static ulong Hash(ReadOnlySpan<byte> b)
    {
        var h = 0xcbf29ce484222325UL;
        foreach (var c in b)
        {
            h ^= (ulong)(c is >= 0x41 and <= 0x5A ? c + 32 : c);
            h = unchecked(h * 0x100000001b3UL);
        }
        h ^= h >> 33; h = unchecked(h * 0xff51afd7ed558ccdUL);
        h ^= h >> 33; h = unchecked(h * 0xc4ceb9fe1a85ec53UL);
        h ^= h >> 33;
        return h;
    }

    public static ulong Hash(string s) => Hash(Encoding.UTF8.GetBytes(s));

    public bool Contains(ulong h) => Array.BinarySearch(Hashes, h) >= 0;
    public bool Contains(string domain) => Contains(Hash(domain));

    /// <summary>
    /// The shortest part of a host, from its last two names up to the whole host, that is in the set: "m.tiktok.com"
    /// gives "tiktok.com" when that is listed. A single name ("com") never counts.
    /// </summary>
    public string? FirstSuffix(string host)
    {
        var b = Encoding.UTF8.GetBytes(host);
        var starts = new List<int> { 0 };
        for (var i = 0; i < b.Length; i++) if (b[i] == 0x2E) starts.Add(i + 1);
        if (starts.Count < 2) return null;
        for (var i = starts.Count - 2; i >= 0; i--)
        {
            var s = starts[i];
            if (b.Length > s && Contains(Hash(b.AsSpan(s)))) return Encoding.UTF8.GetString(b, s, b.Length - s);
        }
        return null;
    }

    // ---------------------------------------------------------------- on disk

    /// <summary>"CHD1", the count (4 bytes), then the hashes, all little-endian (as the Mac writes them).</summary>
    public byte[] Serialized()
    {
        var d = new byte[8 + Hashes.Length * 8];
        "CHD1"u8.CopyTo(d);
        BinaryPrimitives.WriteUInt32LittleEndian(d.AsSpan(4), (uint)Hashes.Length);
        for (var i = 0; i < Hashes.Length; i++) BinaryPrimitives.WriteUInt64LittleEndian(d.AsSpan(8 + i * 8), Hashes[i]);
        return d;
    }

    /// <summary>Null if the bytes aren't a copy written by Serialized (cut short, or not sorted).</summary>
    public static DomainSet? FromSerialized(ReadOnlySpan<byte> d)
    {
        if (d.Length < 8 || !d[..4].SequenceEqual("CHD1"u8)) return null;
        var n = (int)BinaryPrimitives.ReadUInt32LittleEndian(d[4..]);
        if (n < 0 || d.Length != 8 + (long)n * 8) return null;
        var a = new ulong[n];
        for (var i = 0; i < n; i++)
        {
            a[i] = BinaryPrimitives.ReadUInt64LittleEndian(d[(8 + i * 8)..]);
            if (i > 0 && a[i - 1] >= a[i]) return null;
        }
        return new DomainSet(a, alreadySorted: true);
    }

    /// <summary>Hashes already sorted with no repeats (read back from disk).</summary>
    DomainSet(ulong[] sorted, bool alreadySorted) { Hashes = sorted; }

    public bool Equals(DomainSet? other) => other != null && Hashes.AsSpan().SequenceEqual(other.Hashes);
    public override bool Equals(object? obj) => Equals(obj as DomainSet);
    public override int GetHashCode() => Hashes.Length == 0 ? 0 : HashCode.Combine(Hashes.Length, Hashes[0], Hashes[^1]);
}

/// <summary>
/// Reads the lists the web publishes for blocking: hosts files (0.0.0.0 example.com), plain lists of domains, and
/// AdGuard / Adblock rules (||example.com^). Only whole domains count. Comments, exceptions (@@), paths, wildcards,
/// page rules, IP addresses and local names are skipped. The Mac's ListParser, line for line.
/// </summary>
public static class ListParser
{
    public sealed record Result(ulong[] Hashes, bool WebPage);

    public static Result Parse(ReadOnlySpan<byte> b)
    {
        var n = b.Length;
        var bom = n >= 3 && b[0] == 0xEF && b[1] == 0xBB && b[2] == 0xBF ? 3 : 0;
        var i = bom;
        while (i < n && IsSpace(b[i])) i++;
        var page = i < n && b[i] == 0x3C;   // "<"
        var out_ = new List<ulong>(page ? 0 : n / 22);
        if (!page)
            for (var start = bom; start <= n;)
            {
                var end = start;
                while (end < n && b[end] != 0x0A) end++;
                if (Domain(b, start, end) is { } r) out_.Add(DomainSet.Hash(b[r.Lo..r.Hi]));
                start = end + 1;
            }
        return new Result([.. out_], page);
    }

    /// <summary>The domains in a text, lowercase, for tests and small lists.</summary>
    public static List<string> Domains(string text)
    {
        var b = Encoding.UTF8.GetBytes(text);
        var out_ = new List<string>();
        for (var start = 0; start <= b.Length;)
        {
            var end = start;
            while (end < b.Length && b[end] != 0x0A) end++;
            if (Domain(b, start, end) is { } r) out_.Add(Encoding.UTF8.GetString(b, r.Lo, r.Hi - r.Lo).ToLowerInvariant());
            start = end + 1;
        }
        return out_;
    }

    // ---------------------------------------------------------------- one line

    static bool IsSpace(byte c) => c is 0x20 or 0x09 or 0x0D or 0x0A;
    static bool IsDigit(byte c) => c is >= 0x30 and <= 0x39;
    static bool IsDomainByte(byte c) => c is >= 0x61 and <= 0x7A or >= 0x41 and <= 0x5A || IsDigit(c) || c is 0x2D or 0x2E or 0x5F;

    /// <summary>Where the domain is in this line, or null if the line names none.</summary>
    static (int Lo, int Hi)? Domain(ReadOnlySpan<byte> b, int from, int to)
    {
        int lo = from, hi = to;
        while (lo < hi && IsSpace(b[lo])) lo++;
        while (hi > lo && IsSpace(b[hi - 1])) hi--;
        if (lo >= hi) return null;
        var c = b[lo];
        if (c is 0x23 or 0x21 or 0x5B or 0x40) return null;   // # ! [ @
        if (c == 0x7C)   // |
        {
            if (hi - lo <= 3 || b[lo + 1] != 0x7C) return null;
            var e = lo + 2;
            while (e < hi && IsDomainByte(b[e])) e++;
            if (e >= hi || b[e] != 0x5E) return null;   // ^
            if (e + 1 < hi)
            {
                // only the options that still mean the whole domain
                var tail = Encoding.UTF8.GetString(b[(e + 1)..hi]);
                if (tail != "$important" && tail != "$all") return null;
            }
            return Named(b, lo + 2, e);
        }
        var hash = -1;
        for (var k = lo; k < hi; k++) if (b[k] == 0x23) { hash = k; break; }
        if (hash >= 0)   // a comment after the entry
        {
            // example.org##.banner and its kin are page rules, not a domain
            if (hash + 1 < hi && b[hash + 1] is 0x23 or 0x40 or 0x3F or 0x24 or 0x25) return null;
            hi = hash;
            while (hi > lo && IsSpace(b[hi - 1])) hi--;
            if (lo >= hi) return null;
        }
        var t1 = lo;
        while (t1 < hi && !IsSpace(b[t1])) t1++;
        if (t1 == hi) return Named(b, lo, hi);   // one word: a plain list
        // a hosts line: an address, then the name
        if (!IsAddress(b, lo, t1)) return null;
        var s2 = t1;
        while (s2 < hi && IsSpace(b[s2])) s2++;
        var t2 = s2;
        while (t2 < hi && !IsSpace(b[t2])) t2++;
        return Named(b, s2, t2);
    }

    static bool IsAddress(ReadOnlySpan<byte> b, int lo, int hi)
    {
        bool colon = false, digitsDots = true;
        for (var i = lo; i < hi; i++)
        {
            var c = b[i];
            if (c == 0x3A) { colon = true; digitsDots = false; }
            else if (!(IsDigit(c) || c == 0x2E))
            {
                digitsDots = false;
                if (!(c is >= 0x61 and <= 0x66 or >= 0x41 and <= 0x46)) return false;
            }
        }
        return colon || digitsDots;
    }

    /// <summary>
    /// The domain at b[lo..hi] if it is one: letters, digits, dashes and underscores in names with dots between, at
    /// least two names, not an address, not a local name. A leading "www." is dropped, since a page's "www." is
    /// dropped too when it is looked up.
    /// </summary>
    static (int Lo, int Hi)? Named(ReadOnlySpan<byte> b, int lo, int hi)
    {
        var len = hi - lo;
        if (len < 3 || len > 253) return null;
        int dots = 0;
        var numeric = true;
        for (var i = lo; i < hi; i++)
        {
            var c = b[i];
            if (!IsDomainByte(c)) return null;
            if (c == 0x2E)
            {
                dots++;
                if (i == lo || i == hi - 1 || b[i - 1] == 0x2E) return null;
            }
            else if (!IsDigit(c)) numeric = false;
        }
        if (dots < 1 || numeric) return null;
        var l = lo;
        if (len > 4 && (b[lo] | 0x20) == 0x77 && (b[lo + 1] | 0x20) == 0x77 && (b[lo + 2] | 0x20) == 0x77 && b[lo + 3] == 0x2E && b[(lo + 4)..hi].IndexOf((byte)0x2E) >= 0)
            l = lo + 4;
        if (hi - l is 21 or 10)   // only a name that long can be one of the local ones
        {
            var name = Encoding.UTF8.GetString(b[l..hi]).ToLowerInvariant();
            if (name is "localhost.localdomain" or "local.host") return null;
        }
        return (l, hi);
    }
}
