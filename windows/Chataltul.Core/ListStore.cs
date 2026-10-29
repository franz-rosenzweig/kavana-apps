using System.Formats.Tar;
using System.IO.Compression;
using System.Net;
using System.Text.Json;

namespace Chataltul.Core;

/// <summary>What is known about one downloaded list.</summary>
public sealed class ListStatus
{
    public int Count { get; set; }
    /// <summary>When the copy on this PC was made, and when it was last checked against the web (ms).</summary>
    public long Updated { get; set; }
    public long Checked { get; set; }
    /// <summary>The last try, good or bad.</summary>
    public long Tried { get; set; }
    public string? Etag { get; set; }
    public string? LastModified { get; set; }
    /// <summary>Why the last try failed, in a few words; null when it worked.</summary>
    public string? Error { get; set; }
}

/// <summary>What a download came back with.</summary>
public abstract record Fetched
{
    /// <summary>The list's bytes, and what to ask with next time to learn whether it changed.</summary>
    public sealed record Body(byte[] Bytes, string? Etag, string? LastModified) : Fetched;
    public sealed record NotModified : Fetched;
    public sealed record Failed(string Why) : Fetched;
}

/// <summary>Gets a list from the web.</summary>
public interface IListFetcher
{
    Task<Fetched> Fetch(Uri url, string? etag, string? lastModified, long maxBytes);
}

/// <summary>How a category stands, for its row.</summary>
public abstract record CategoryState
{
    /// <summary>Not on this PC yet; about this many KB to download (0: not known).</summary>
    public sealed record None(int SizeKB) : CategoryState;
    public sealed record Working : CategoryState;
    public sealed record Ready(int Sites, long Updated) : CategoryState;
    /// <summary>The last try failed; Sites are already here from before.</summary>
    public sealed record Failed(string Why, int Sites) : CategoryState;
}

/// <summary>
/// The ready-made lists on this PC: downloaded when a category is switched on, kept as hashes in lists\, looked at
/// again about once a week. A bad answer (an error, a web page, a list that is nearly empty) never replaces the copy
/// already here. Its own calls may come from any thread; Changed is raised on whichever finished the work. The Mac's
/// ListStore.swift.
/// </summary>
public sealed class CategoryStore : IDomainMatcher
{
    public const long RefreshAfterMs = 7 * 86_400_000L, RetryAfterMs = 3_600_000;
    /// <summary>The most that is downloaded for one list.</summary>
    public const long MaxBytes = 64 * 1024 * 1024;

    public string Folder { get; }
    /// <summary>The lists the person added by link (the app keeps them in its settings).</summary>
    public List<ListCategory> Custom { get; set; } = [];
    public event Action? Changed;

    readonly Dictionary<string, DomainSet> sets = [];
    readonly Dictionary<string, ListStatus> status = [];
    readonly HashSet<string> working = [];
    readonly object gate = new();
    readonly IListFetcher fetcher;
    readonly bool network;
    readonly Func<long> clock;
    string StatusFile => Path.Join(Folder, "status.json");

    /// <summary><c>network</c> false never goes to the web (tests that fake the lists).</summary>
    public CategoryStore(string folder, IListFetcher? fetcher = null, bool network = true, Func<long>? clock = null)
    {
        Folder = folder;
        this.fetcher = fetcher ?? new HttpFetcher();
        this.network = network;
        this.clock = clock ?? (() => Format.NowMs());
        Directory.CreateDirectory(folder);
        Load();
    }

    string FileFor(string sourceId) => Path.Join(Folder, sourceId + ".chd");

    static bool SafeId(string id) => id.Length > 0 && id.IndexOfAny(['/', '\\', ':', '.']) < 0;

    /// <summary>What is on disk from before.</summary>
    void Load()
    {
        Dictionary<string, ListStatus>? saved;
        try { saved = JsonSerializer.Deserialize<Dictionary<string, ListStatus>>(File.ReadAllText(StatusFile), J.Files); }
        catch (Exception) { return; }
        foreach (var (id, st) in saved ?? new Dictionary<string, ListStatus>())
        {
            if (!SafeId(id)) continue;
            DomainSet? set;
            try { set = DomainSet.FromSerialized(File.ReadAllBytes(FileFor(id))); } catch (Exception) { continue; }
            if (set == null) continue;
            sets[id] = set;
            st.Count = set.Count;
            status[id] = st;
        }
    }

    void SaveStatus()
    {
        string json;
        lock (gate) json = JsonSerializer.Serialize(new SortedDictionary<string, ListStatus>(status), J.Files);
        try { Files.WriteWhole(StatusFile, json); } catch (Exception) { }
    }

    // ---------------------------------------------------------------- what is where

    public ListCategory? Category(string id) => Catalog.Category(id, Custom);

    public List<ListSource> Sources(IEnumerable<string> ids)
    {
        var seen = new HashSet<string>();
        var out_ = new List<ListSource>();
        foreach (var id in ids)
            foreach (var s in Category(id)?.Sources ?? [])
                if (seen.Add(s.Id)) out_.Add(s);
        return out_;
    }

    public ListStatus? StatusOf(string sourceId) { lock (gate) return status.GetValueOrDefault(sourceId); }

    public CategoryState State(string id)
    {
        if (Category(id) is not { } cat) return new CategoryState.None(0);
        lock (gate)
        {
            if (cat.Sources.Any(s => working.Contains(s.Id))) return new CategoryState.Working();
            var have = cat.Sources.Where(s => sets.ContainsKey(s.Id)).ToList();
            var sites = have.Sum(s => sets[s.Id].Count);
            if (cat.Sources.Select(s => status.GetValueOrDefault(s.Id)?.Error).FirstOrDefault(e => e != null) is { } why) return new CategoryState.Failed(why, sites);
            if (have.Count == cat.Sources.Count) return new CategoryState.Ready(sites, have.Select(s => status.GetValueOrDefault(s.Id)?.Updated ?? 0).DefaultIfEmpty(0).Min());
            return new CategoryState.None(cat.Sources.Where(s => !sets.ContainsKey(s.Id)).Sum(s => s.SizeKB));
        }
    }

    /// <summary>The listed domain this host falls under in one of these categories, or null.</summary>
    public string? Match(string host, IEnumerable<string> categories)
    {
        foreach (var s in Sources(categories))
        {
            DomainSet? set;
            lock (gate) set = sets.GetValueOrDefault(s.Id);
            if (set?.FirstSuffix(host) is { } d) return d;
        }
        return null;
    }

    /// <summary>Puts a list in place without going to the web (tests, and the pictures).</summary>
    public void Install(IEnumerable<string> domains, string sourceId)
    {
        var set = DomainSet.Of(domains);
        var now = clock();
        lock (gate)
        {
            sets[sourceId] = set;
            status[sourceId] = new ListStatus { Count = set.Count, Updated = now, Checked = now, Tried = now };
        }
        Changed?.Invoke();
    }

    /// <summary>Shows a list as downloading, or as failed with this reason (the pictures).</summary>
    public void Simulate(string sourceId, bool isWorking = false, string? error = null)
    {
        lock (gate)
        {
            if (isWorking) working.Add(sourceId); else working.Remove(sourceId);
            if (error != null)
            {
                if (!status.TryGetValue(sourceId, out var st)) status[sourceId] = st = new ListStatus();
                st.Error = error;
            }
        }
        Changed?.Invoke();
    }

    /// <summary>Forgets the copy of these lists (a list the person took away).</summary>
    public void Remove(IEnumerable<string> sourceIds)
    {
        foreach (var id in sourceIds.Where(SafeId))
        {
            lock (gate) { sets.Remove(id); status.Remove(id); }
            try { File.Delete(FileFor(id)); } catch (Exception) { }
        }
        SaveStatus();
        Changed?.Invoke();
    }

    // ---------------------------------------------------------------- downloading

    /// <summary>
    /// Brings the lists of these categories up to date: the ones not here yet, the ones last checked over a week ago,
    /// and (after an hour) the ones that failed. <c>force</c> fetches them all now. Done when every one has an answer.
    /// </summary>
    public async Task Refresh(IEnumerable<string> ids, bool force = false)
    {
        if (!network) return;
        var due = Sources(ids).Where(s => ShouldFetch(s, force)).ToList();
        await Task.WhenAll(due.Select(Fetch));
    }

    bool ShouldFetch(ListSource s, bool force)
    {
        lock (gate)
        {
            if (working.Contains(s.Id)) return false;
            if (force) return true;
            if (!status.TryGetValue(s.Id, out var st)) return true;
            var now = clock();
            if (st.Error != null) return now - st.Tried >= RetryAfterMs;
            return !sets.ContainsKey(s.Id) || now - st.Checked >= RefreshAfterMs;
        }
    }

    async Task Fetch(ListSource s)
    {
        if (!SafeId(s.Id) || !Uri.TryCreate(s.Url, UriKind.Absolute, out var url)) { Fail(s, "Not an address"); return; }
        ListStatus? have;
        lock (gate)
        {
            working.Add(s.Id);
            // a copy is only reused if it is really here
            have = sets.ContainsKey(s.Id) ? status.GetValueOrDefault(s.Id) : null;
        }
        Changed?.Invoke();
        Fetched result;
        try { result = await fetcher.Fetch(url, have?.Etag, have?.LastModified, MaxBytes).ConfigureAwait(false); }
        catch (Exception) { result = new Fetched.Failed("Couldn't reach it"); }
        switch (result)
        {
            case Fetched.NotModified:
                {
                    var now = clock();
                    lock (gate)
                    {
                        var st = status.GetValueOrDefault(s.Id) ?? new ListStatus();
                        st.Checked = now; st.Tried = now; st.Error = null;
                        status[s.Id] = st;
                        working.Remove(s.Id);
                    }
                    SaveStatus();
                    Changed?.Invoke();
                    break;
                }
            case Fetched.Failed f:
                Fail(s, f.Why);
                break;
            case Fetched.Body body:
                {
                    var built = await Task.Run(() => Build(s, body.Bytes)).ConfigureAwait(false);
                    if (built.Set is not { } set) { Fail(s, built.Why ?? "Not a list of sites"); break; }
                    try { Files.WriteBytesWhole(FileFor(s.Id), set.Serialized()); } catch (Exception) { }
                    var now = clock();
                    lock (gate)
                    {
                        sets[s.Id] = set;
                        status[s.Id] = new ListStatus { Count = set.Count, Updated = now, Checked = now, Tried = now, Etag = body.Etag, LastModified = body.LastModified };
                        working.Remove(s.Id);
                    }
                    SaveStatus();
                    Changed?.Invoke();
                    break;
                }
        }
    }

    /// <summary>A try that failed: the copy already here (if any) stays.</summary>
    void Fail(ListSource s, string why)
    {
        lock (gate)
        {
            var st = status.GetValueOrDefault(s.Id) ?? new ListStatus();
            st.Error = why;
            st.Tried = clock();
            status[s.Id] = st;
            working.Remove(s.Id);
        }
        SaveStatus();
        Changed?.Invoke();
    }

    /// <summary>The downloaded bytes as a set, or why they aren't one.</summary>
    public static (DomainSet? Set, string? Why) Build(ListSource s, byte[] bytes)
    {
        byte[] data;
        if (s.Member is { } member)
        {
            if (Tar.Read(member, bytes, 256L * 1024 * 1024) is not { } d) return (null, "Couldn't open it");
            data = d;
        }
        else data = bytes;
        var r = ListParser.Parse(data);
        if (r.WebPage) return (null, "Not a list of sites");
        var set = new DomainSet(r.Hashes);
        if (set.Count < Math.Max(1, s.Floor)) return (null, "Too few sites");
        return (set, null);
    }
}

/// <summary>One file out of a .tar.gz, read to memory: nothing in the archive is written anywhere, so nothing in it can name a place to be put.</summary>
public static class Tar
{
    public static byte[]? Read(string member, byte[] archive, long maxBytes)
    {
        try
        {
            using var gz = new GZipStream(new MemoryStream(archive), CompressionMode.Decompress);
            using var tar = new TarReader(gz);
            while (tar.GetNextEntry() is { } e)
            {
                var name = e.Name.TrimStart('.', '/');
                if (name != member || e.DataStream == null) continue;
                if (e.Length > maxBytes) return null;
                using var m = new MemoryStream();
                e.DataStream.CopyTo(m);
                return m.Length == 0 ? null : m.ToArray();
            }
        }
        catch (Exception) { }
        return null;
    }
}

/// <summary>
/// Downloads with HttpClient: https only (http only to this PC, for tests), no cookies, nothing sent but "have you
/// changed" headers, cut off at maxBytes.
/// </summary>
public sealed class HttpFetcher : IListFetcher
{
    static readonly HttpClient Http = new(new SocketsHttpHandler { UseCookies = false, AutomaticDecompression = DecompressionMethods.All })
    {
        Timeout = TimeSpan.FromMinutes(5),
    };

    public async Task<Fetched> Fetch(Uri url, string? etag, string? lastModified, long maxBytes)
    {
        var local = url.Host is "127.0.0.1" or "localhost";
        if (url.Scheme != Uri.UriSchemeHttps && !(url.Scheme == Uri.UriSchemeHttp && local)) return new Fetched.Failed("Not a secure address");
        using var req = new HttpRequestMessage(HttpMethod.Get, url);
        req.Headers.UserAgent.ParseAdd("Chataltul");
        if (etag != null) req.Headers.TryAddWithoutValidation("If-None-Match", etag);
        if (lastModified != null) req.Headers.TryAddWithoutValidation("If-Modified-Since", lastModified);
        try
        {
            using var res = await Http.SendAsync(req, HttpCompletionOption.ResponseHeadersRead).ConfigureAwait(false);
            if (res.StatusCode == HttpStatusCode.NotModified) return new Fetched.NotModified();
            if (res.StatusCode != HttpStatusCode.OK) return new Fetched.Failed($"Server said {(int)res.StatusCode}");
            if (res.Content.Headers.ContentLength > maxBytes) return new Fetched.Failed("Too big");
            await using var body = await res.Content.ReadAsStreamAsync().ConfigureAwait(false);
            using var m = new MemoryStream();
            var buffer = new byte[81920];
            int n;
            while ((n = await body.ReadAsync(buffer).ConfigureAwait(false)) > 0)
            {
                m.Write(buffer, 0, n);
                if (m.Length > maxBytes) return new Fetched.Failed("Too big");
            }
            return new Fetched.Body(m.ToArray(), res.Headers.ETag?.ToString(), res.Content.Headers.LastModified?.ToString("R"));
        }
        catch (Exception) { return new Fetched.Failed("Couldn't reach it"); }
    }
}
