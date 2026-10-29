using System.Text.Json;

namespace Chataltul.Core;

// Where each block went, app by app: which app was in front each second of a block, and for a browser the site (the
// domain only, never an address or a title). It lives in timing.json on this PC and is never sent anywhere: it isn't
// in the doc the sync carries. The Mac's Timing.swift.

public sealed class TimedApp
{
    /// <summary>The program's file (discord.exe): what the blocklist knows an app by.</summary>
    public string Exe { get; set; } = "";
    public string Name { get; set; } = "";
    public long Ms { get; set; }
}

public sealed class TimedSite
{
    public string Domain { get; set; } = "";
    public string Browser { get; set; } = "";
    public long Ms { get; set; }
}

public sealed class BlockTiming
{
    public long Start { get; set; }
    public Dictionary<string, TimedApp> Apps { get; set; } = [];
    public Dictionary<string, TimedSite> Sites { get; set; } = [];

    public long TotalMs => Apps.Values.Sum(a => a.Ms);
}

/// <summary>All the blocks' timings, by block id.</summary>
public sealed class TimingLog
{
    public Dictionary<string, BlockTiming> Blocks { get; set; } = [];

    public void Add(string block, long start, string exe, string name, long ms, (string Domain, string Browser)? site = null)
    {
        if (!Blocks.TryGetValue(block, out var t)) Blocks[block] = t = new BlockTiming { Start = start };
        if (!t.Apps.TryGetValue(exe, out var a)) t.Apps[exe] = a = new TimedApp { Exe = exe, Name = name };
        a.Ms += ms;
        if (site is { } s)
        {
            if (!t.Sites.TryGetValue(s.Domain, out var x)) t.Sites[s.Domain] = x = new TimedSite { Domain = s.Domain, Browser = s.Browser };
            x.Ms += ms;
        }
    }

    /// <summary>Blocks older than this are dropped (the log must not grow for ever).</summary>
    public void Prune(long before)
    {
        foreach (var id in Blocks.Where(kv => kv.Value.Start < before).Select(kv => kv.Key).ToList()) Blocks.Remove(id);
    }

    public void Clear() => Blocks.Clear();

    /// <summary>The timings of these blocks, in no particular order.</summary>
    public List<BlockTiming> For(IEnumerable<string> ids) => ids.Select(id => Blocks.GetValueOrDefault(id)).OfType<BlockTiming>().ToList();

    public bool IsEmpty => Blocks.Count == 0;

    public static TimingLog Read(string folder)
    {
        try { return JsonSerializer.Deserialize<TimingLog>(File.ReadAllText(Path.Join(folder, "timing.json")), J.Files) ?? new TimingLog(); }
        catch (Exception) { return new TimingLog(); }
    }

    public void Write(string folder)
    {
        try { Files.WriteWhole(Path.Join(folder, "timing.json"), JsonSerializer.Serialize(this, J.Files)); } catch (Exception) { }
    }

    public static void Erase(string folder)
    {
        try { File.Delete(Path.Join(folder, "timing.json")); } catch (Exception) { }
    }
}

/// <summary>One line of "where it went". A site is kept under its browser's app name, so it lands on that app's line.</summary>
/// <param name="Sites">A browser's busiest sites, for its line ("Microsoft Edge: bbc.co.uk, youtube.com").</param>
/// <param name="Distraction">On a list of things that pull the person away.</param>
public sealed record WentRow(string Name, long Ms, double Share, List<string> Sites, bool Distraction)
{
    public string Label => Sites.Count == 0 ? Name : $"{Name}: {string.Join(", ", Sites)}";
    public int Minutes => Math.Max(1, (int)Math.Round(Ms / 60000.0, MidpointRounding.AwayFromZero));
}

public static class Went
{
    /// <summary>The apps (and sites) these blocks spent their time in, biggest first. Past the first few, they're "Other".</summary>
    public static List<WentRow> Rows(IEnumerable<BlockTiming> timings, Blocklist? blocklist = null, IDomainMatcher? matcher = null, int limit = 6)
    {
        var list = blocklist ?? new Blocklist();
        var apps = new Dictionary<string, TimedApp>();
        var sites = new Dictionary<string, Dictionary<string, long>>();
        foreach (var t in timings)
        {
            foreach (var (id, a) in t.Apps)
            {
                if (!apps.TryGetValue(id, out var x)) apps[id] = x = new TimedApp { Exe = id, Name = a.Name };
                x.Ms += a.Ms;
            }
            foreach (var (d, s) in t.Sites)
            {
                if (!sites.TryGetValue(s.Browser, out var bySite)) sites[s.Browser] = bySite = [];
                bySite[d] = bySite.GetValueOrDefault(d) + s.Ms;
            }
        }
        var total = Math.Max(1, apps.Values.Sum(a => a.Ms));
        var rows = apps.Values.OrderByDescending(a => a.Ms).ThenBy(a => a.Name, StringComparer.Ordinal).Select(a =>
        {
            var top = (sites.GetValueOrDefault(a.Name) ?? new Dictionary<string, long>()).OrderByDescending(kv => kv.Value).ThenBy(kv => kv.Key, StringComparer.Ordinal).Take(2).Select(kv => kv.Key).ToList();
            var bad = list.App(a.Exe) != null || top.Any(d => list.Sites.Contains(d) || list.Site($"https://{d}/") != null
                || list.Lists.Any(l => l.Site($"https://{d}/", matcher) != null));
            return new WentRow(a.Name, a.Ms, (double)a.Ms / total, top, bad);
        }).ToList();
        if (rows.Count > limit)
        {
            var rest = rows.Skip(limit - 1).ToList();
            rows = [.. rows.Take(limit - 1), new WentRow("Other", rest.Sum(r => r.Ms), rest.Sum(r => r.Share), [], false)];
        }
        return rows;
    }
}
