using System.Text.Json;

namespace Chataltul.Core;

/// <summary>A sound in the mix, and how loud.</summary>
public sealed record MixEntry(string Id, double Vol = 0.8);

/// <summary>
/// A background sound, as the web's sounds.js lists it (KINDS). <c>File</c> is its recording,
/// &lt;server&gt;/audio/&lt;file&gt;.mp3; the two noises have none and are made on the PC.
/// </summary>
public sealed record SoundKind(string Id, string Name, string? About, string Group, bool Free, string? Like, string? File);

public sealed record SoundGroup(string Id, string Name);

/// <summary>
/// The sound list. It is read from the Mac's sounds.json, which mac/tools/sync-web.sh makes out of KINDS in the web
/// repo's sounds.js, so the list is in one place and never typed in here. The Mac's Sounds.swift.
/// </summary>
public sealed class SoundCatalog
{
    public List<SoundGroup> Groups { get; init; } = [];
    public List<SoundKind> Kinds { get; init; } = [];
    public List<string> Meows { get; init; } = [];

    public static SoundCatalog Load(string file)
    {
        using var d = JsonDocument.Parse(System.IO.File.ReadAllText(file));
        var e = d.RootElement;
        return new SoundCatalog
        {
            Groups = e.Items("groups", g => new SoundGroup(g.Str("id"), g.Str("name"))),
            Kinds = e.Items("kinds", k => new SoundKind(k.Str("id"), k.Str("name"), k.OptStr("about"), k.Str("group"), k.Bool("free"), k.OptStr("like"), k.OptStr("file"))),
            Meows = e.Strings("meows"),
        };
    }

    public SoundKind? Kind(string id) => Kinds.FirstOrDefault(k => k.Id == id);
    public List<SoundKind> In(string group) => Kinds.Where(k => k.Group == group).ToList();

    /// <summary>Free, or bought in the shop ("sound-&lt;id&gt;").</summary>
    public static bool Owned(SoundKind kind, IEnumerable<string> shop) => kind.Free || shop.Contains($"sound-{kind.Id}");

    /// <summary>What in the mix can play: it exists and is owned. At most two.</summary>
    public List<MixEntry> Playable(IEnumerable<MixEntry> mix, IReadOnlyCollection<string> shop) =>
        mix.Where(m => Kind(m.Id) is { } k && Owned(k, shop)).Take(2).ToList();

    /// <summary>"Rain + Purring".</summary>
    public string Label(IEnumerable<MixEntry> mix, IReadOnlyCollection<string> shop)
    {
        var names = Playable(mix, shop).Select(m => Kind(m.Id)?.Name).OfType<string>().ToList();
        return names.Count == 0 ? "Sound" : string.Join(" + ", names);
    }

    /// <summary>Adds or removes a sound from the mix. A third one takes the oldest one's place.</summary>
    public static List<MixEntry> Toggle(string id, IReadOnlyList<MixEntry> mix) =>
        mix.Any(m => m.Id == id) ? mix.Where(m => m.Id != id).ToList() : mix.Append(new MixEntry(id)).TakeLast(2).ToList();
}
