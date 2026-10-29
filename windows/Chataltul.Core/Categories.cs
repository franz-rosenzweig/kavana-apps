namespace Chataltul.Core;

/// <summary>One public list on the web that a category is made from.</summary>
/// <param name="Member">A tar.gz archive holds the list as this file (UT1's dating/domains); null for a plain text list.</param>
/// <param name="SizeKB">About how much it downloads, in KB (0 when not known).</param>
/// <param name="Floor">Fewer domains than this and what came back isn't the list, so it is thrown away.</param>
public sealed record ListSource(string Id, string Name, string Url, string? Member, string License, string Credit, int SizeKB, int Floor);

/// <summary>A kind of site (Adult, Social media...) made from one or more public lists, or a list the person added by its link.</summary>
public sealed record ListCategory(string Id, string Name, List<ListSource> Sources, bool Custom = false);

/// <summary>The ready-made lists. Nothing here is bundled: each one is downloaded to this PC when it is switched on. The Mac's Categories.swift.</summary>
public static class Catalog
{
    const string Ut1License = "CC BY-SA 4.0", Ut1Credit = "UT1 Blacklists, Université Toulouse Capitole";

    static ListSource Ut1(string name, string file, int kb, int floor) =>
        new($"ut1-{file}", $"UT1 {name}", $"https://dsi.ut-capitole.fr/blacklists/download/{file}.tar.gz", $"{file}/domains", Ut1License, Ut1Credit, kb, floor);

    static ListSource Steven(string name, string folder, int kb, int floor) =>
        new($"stevenblack-{folder}", $"StevenBlack {name}", $"https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/{folder}/hosts", null,
            "MIT", "StevenBlack/hosts", kb, floor);

    public static readonly IReadOnlyList<ListCategory> All =
    [
        new("adult", "Adult", [
            new("hagezi-nsfw", "Hagezi NSFW", "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/adblock/nsfw.txt", null, "GPL-3.0", "Hagezi DNS Blocklists", 1700, 1000),
            Steven("porn", "porn-only", 2000, 1000),
        ]),
        new("social", "Social media", [Ut1("social networks", "social_networks", 4, 100), Steven("social", "social-only", 200, 500)]),
        new("dating", "Dating", [Ut1("dating", "dating", 60, 1000)]),
        new("gambling", "Gambling", [Ut1("gambling", "gambling", 320, 5000), Steven("gambling", "gambling-only", 150, 500)]),
        new("games", "Games", [Ut1("games", "games", 200, 3000)]),
        new("video", "Video and music", [Ut1("audio and video", "audio-video", 25, 500)]),
        new("news", "News", [Ut1("press", "press", 26, 500)]),
        new("shopping", "Shopping", [Ut1("shopping", "shopping", 215, 3000)]),
    ];

    /// <summary>The category with this id: a ready-made one, or one of the person's own.</summary>
    public static ListCategory? Category(string id, IEnumerable<ListCategory>? custom = null) =>
        All.FirstOrDefault(c => c.Id == id) ?? custom?.FirstOrDefault(c => c.Id == id);

    /// <summary>
    /// A list from its link. Null unless it is an https address with a host. Any public hosts file, list of domains
    /// or AdGuard / Adblock list will do.
    /// </summary>
    public static ListCategory? Custom(string name, string link)
    {
        var t = link.Trim();
        if (!Uri.TryCreate(t, UriKind.Absolute, out var u) || u.Scheme != Uri.UriSchemeHttps || !u.Host.Contains('.') || u.UserInfo.Length > 0) return null;
        var id = "custom-" + Guid.NewGuid().ToString("N")[..8];
        var label = name.Trim();
        var source = new ListSource(id, u.Host, t, null, "", u.Host, 0, 5);
        return new ListCategory(id, label.Length == 0 ? u.Host : label, [source], true);
    }
}
