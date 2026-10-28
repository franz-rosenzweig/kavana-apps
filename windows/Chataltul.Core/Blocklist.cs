using System.Text.Json.Serialization;

namespace Chataltul.Core;

/// <summary>A listed app: found by its program file (discord.exe), shown by its name.</summary>
public sealed record ListedApp
{
    /// <summary>The program's file name, lower case: what the front window's process runs.</summary>
    public string Exe { get; set; } = "";
    public string Name { get; set; } = "";

    public ListedApp() { }
    public ListedApp(string exe, string name) { Exe = NormalizeExe(exe); Name = name; }

    /// <summary>"C:\Program Files\Discord\Discord.exe" and "Discord.EXE" both give "discord.exe".</summary>
    public static string NormalizeExe(string path) => Path.GetFileName(path.Trim().Trim('"')).ToLowerInvariant();
}

/// <summary>What happens when something on a list is opened. Version 0.1 has the speed bump; the others come with 0.2.</summary>
public enum GuardMode
{
    /// <summary>The cat walks over it: 15 seconds to come back, or hold to leave and the kitten wanders off.</summary>
    SpeedBump,
    /// <summary>A 30-second countdown with the cat before it opens, every time.</summary>
    WaitItOut,
    /// <summary>It doesn't open until the block ends.</summary>
    Locked,
}

/// <summary>When a list is watched: during every block, and also on weekdays between two times, or always (0.2).</summary>
public sealed class GuardWhen
{
    public enum Kinds { Blocks, Weekdays, Always }
    public Kinds Kind { get; set; } = Kinds.Blocks;
    /// <summary>Minutes after midnight, for weekdays.</summary>
    public int FromMin { get; set; } = 9 * 60;
    public int ToMin { get; set; } = 17 * 60;
}

/// <summary>
/// One list: apps and sites, and what to do when one of them is opened. The shape is the Mac's whole one
/// (Blocklist.swift), so the lists, modes, schedules, allowances and ready-made lists of 0.2 need no new file.
/// </summary>
public sealed class GuardList
{
    public string Id { get; set; } = Guid.NewGuid().ToString();
    public string Name { get; set; } = "Distractions";
    /// <summary>Which of the app's colors marks it (0 to 5).</summary>
    public int Color { get; set; }
    public List<ListedApp> Apps { get; set; } = [];
    public List<string> Sites { get; set; } = [];
    public GuardMode Mode { get; set; } = GuardMode.SpeedBump;
    public GuardWhen When { get; set; } = new();
    /// <summary>Minutes a day allowed outside blocks (0: none).</summary>
    public int AllowanceMin { get; set; }
    /// <summary>The ready-made lists switched on (Categories, in 0.2), on top of the typed sites.</summary>
    public List<string> Categories { get; set; } = [];
    /// <summary>Sites a ready-made list never matches (and their subdomains).</summary>
    public List<string> Allow { get; set; } = [];

    [JsonIgnore] public int Count => Apps.Count + Sites.Count;

    /// <summary>The typed site this address is on, if any.</summary>
    public string? Site(string url)
    {
        if (Blocklist.Host(url) is not { } host) return null;
        return Sites.FirstOrDefault(s => host == s || host.EndsWith("." + s, StringComparison.Ordinal));
    }
}

/// <summary>A list, and the thing on it that was found.</summary>
public sealed record GuardHit(GuardList List, Target Target);

/// <summary>What the person is looking at, as far as the list cares.</summary>
public abstract record Target
{
    /// <summary>For the card: "youtube.com" or "Discord".</summary>
    public abstract string Label { get; }
}

public sealed record AppTarget(ListedApp App) : Target
{
    public override string Label => App.Name;
}

public sealed record SiteTarget(string Domain, string Browser) : Target
{
    public override string Label => Domain;
}

/// <summary>What the cat walks over: lists of apps, and of sites in a browser the app can read.</summary>
public sealed class Blocklist
{
    public List<GuardList> Lists { get; set; } = [];

    public Blocklist() { }

    /// <summary>One list called Distractions with these in it.</summary>
    public Blocklist(IEnumerable<ListedApp> apps, IEnumerable<string> sites) =>
        Lists = [new GuardList { Apps = [.. apps], Sites = [.. sites] }];

    /// <summary>
    /// What a new install starts with: the sites most people mean. No app is listed for them: nothing on
    /// Windows is as plainly a distraction as Messages on the Mac, and nothing they might need for work is.
    /// </summary>
    public static Blocklist Starter()
    {
        var b = new Blocklist([], ["youtube.com", "reddit.com", "x.com", "instagram.com", "facebook.com", "tiktok.com", "news.ycombinator.com"]);
        b.Lists[0].Id = "distractions";
        return b;
    }

    [JsonIgnore] public IEnumerable<ListedApp> Apps => Lists.SelectMany(l => l.Apps);
    [JsonIgnore] public IEnumerable<string> Sites => Lists.SelectMany(l => l.Sites);
    [JsonIgnore] public int Count => Lists.Sum(l => l.Count);
    [JsonIgnore] public int CategoryCount => Lists.Sum(l => l.Categories.Count);
    [JsonIgnore] public bool IsEmpty => Count == 0 && CategoryCount == 0;
    /// <summary>Is there anything on the web to watch for?</summary>
    [JsonIgnore] public bool WatchesSites => Sites.Any() || CategoryCount > 0;

    public ListedApp? App(string exe)
    {
        var e = ListedApp.NormalizeExe(exe);
        return Apps.FirstOrDefault(a => a.Exe == e);
    }

    /// <summary>The listed domain a page address falls under (a listed domain covers its subdomains), or null.</summary>
    public string? Site(string url)
    {
        foreach (var l in Lists) if (l.Site(url) is { } s) return s;
        return null;
    }

    /// <summary>The list that has this app, if any.</summary>
    public GuardHit? Hit(string exe)
    {
        var e = ListedApp.NormalizeExe(exe);
        foreach (var l in Lists)
            if (l.Apps.FirstOrDefault(a => a.Exe == e) is { } a) return new GuardHit(l, new AppTarget(a));
        return null;
    }

    /// <summary>The list that has the site this address is on, if any.</summary>
    public GuardHit? Hit(string url, string browser)
    {
        foreach (var l in Lists)
            if (l.Site(url) is { } d) return new GuardHit(l, new SiteTarget(d, browser));
        return null;
    }

    /// <summary>"https://www.Reddit.com/r/cats?x=1" and "reddit.com/r/cats" both give "reddit.com".</summary>
    public static string? Host(string address)
    {
        var t = address.Trim();
        if (t.Length == 0 || t.Contains(' ')) return null;
        var withScheme = t.Contains("://", StringComparison.Ordinal) ? t : "https://" + t;
        if (!Uri.TryCreate(withScheme, UriKind.Absolute, out var u) || string.IsNullOrEmpty(u.Host)) return null;
        var host = u.IdnHost.ToLowerInvariant().TrimEnd('.');
        if (host.StartsWith("www.", StringComparison.Ordinal)) host = host[4..];
        return host.Length == 0 ? null : host;
    }

    /// <summary>What goes in the list when someone types a site: the bare domain, or null if it isn't one.</summary>
    public static string? NormalizeDomain(string typed) =>
        Host(typed) is { } h && h.Contains('.') ? h : null;

    /// <summary>Adds to the list with this id (or the first, or a new one called Distractions). False if it was there or isn't a site.</summary>
    public bool AddSite(string typed, string? listId = null)
    {
        if (NormalizeDomain(typed) is not { } d || Sites.Contains(d)) return false;
        ListFor(listId).Sites.Add(d);
        return true;
    }

    public bool AddApp(ListedApp app, string? listId = null)
    {
        if (string.IsNullOrEmpty(app.Exe) || Apps.Any(a => a.Exe == app.Exe)) return false;
        ListFor(listId).Apps.Add(app);
        return true;
    }

    public bool RemoveSite(string domain) => Lists.Sum(l => l.Sites.RemoveAll(s => s == domain)) > 0;

    public bool RemoveApp(string exe) => Lists.Sum(l => l.Apps.RemoveAll(a => a.Exe == exe)) > 0;

    GuardList ListFor(string? listId)
    {
        if (Lists.Count == 0) Lists.Add(new GuardList());
        return Lists.FirstOrDefault(l => l.Id == listId) ?? Lists[0];
    }
}

/// <summary>
/// Browsers whose address the app can read, with UI Automation: the address box of the front window. Unlike
/// the Mac, Windows asks for no permission, and Firefox and Zen are tried too (not yet seen working). Only the site's name is used.
/// </summary>
public sealed record Browser(string Exe, string Name, bool Gecko)
{
    public static readonly IReadOnlyList<Browser> All =
    [
        new("chrome.exe", "Chrome", false),
        new("msedge.exe", "Edge", false),
        new("brave.exe", "Brave", false),
        new("vivaldi.exe", "Vivaldi", false),
        new("opera.exe", "Opera", false),
        new("arc.exe", "Arc", false),
        new("firefox.exe", "Firefox", true),
        new("zen.exe", "Zen", true),
        new("librewolf.exe", "LibreWolf", true),
    ];

    public static Browser? With(string exe)
    {
        var e = ListedApp.NormalizeExe(exe);
        return All.FirstOrDefault(b => b.Exe == e);
    }

    /// <summary>
    /// Is what the address box holds a page's address (and not half a word being typed, or a search)? It needs
    /// a host with a dot in it, or localhost, or an address with its scheme.
    /// </summary>
    public static bool LooksLikeAddress(string? value)
    {
        if (string.IsNullOrWhiteSpace(value) || value.Trim().Contains(' ')) return false;
        if (Blocklist.Host(value) is not { } h) return false;
        return h.Contains('.') || h == "localhost" || value.Contains("://", StringComparison.Ordinal);
    }
}
