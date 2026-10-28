namespace Chataltul.Core;

/// <summary>
/// Which of the web's paintings shows where (mac/Chataltul/Resources/cats and cafe, made into PNG files for
/// Windows by windows/tools/convert-art.py), and how a block's cat grows (web/app.js renderSky). The Mac's Art.swift.
/// </summary>
public static class Art
{
    public enum Stage { Nap, Kitten, Grown, Home, Gone }

    static Dictionary<string, CatInfo> cats = [];

    /// <summary>Each cat's painting paths, from the engine's cats (cats.js): a repainted cat's have a new number.</summary>
    public static void Learn(IEnumerable<CatInfo> list)
    {
        var d = new Dictionary<string, CatInfo>();
        foreach (var c in list) d.TryAdd(c.Id, c);
        cats = d;
    }

    /// <summary>The web's paths name WebP paintings; the app carries them as PNG, which every Windows can read.</summary>
    public static string File(string path) => Path.ChangeExtension(path, ".png").Replace('/', Path.DirectorySeparatorChar);

    public static string Portrait(string id) => cats.TryGetValue(id, out var c) && c.Portrait != null ? c.Portrait : $"cats/{id}-1.webp";
    public static string Kitten(string id) => cats.TryGetValue(id, out var c) && c.Kitten != null ? c.Kitten : $"cats/{id}-kitten-1.webp";
    public static string Nap(string id) => cats.TryGetValue(id, out var c) && c.Nap != null ? c.Nap : $"cats/{id}-nap-1.webp";
    public const string Gone = "cafe/gone-1.webp";

    /// <summary>A pose painting (walk, loaf, stretch, wash, back) if this cat has it, else its napping picture.</summary>
    public static string Pose(string id, string pose) => cats.TryGetValue(id, out var c) && c.Poses.TryGetValue(pose, out var p) ? p : Nap(id);
    public static bool HasPose(string id, string pose) => cats.TryGetValue(id, out var c) && c.Poses.ContainsKey(pose);

    /// <summary>Which picture a block's cat is in: it grows with the block.</summary>
    public static (Stage Stage, double Progress) StageOf(Block b, long now)
    {
        if (b.Kitten == "gone") return (Stage.Gone, 0);
        if (b.Kitten == "home") return (Stage.Home, 1);
        var total = Math.Max(1, b.End - b.Start);
        var p = Math.Clamp((double)(now - b.Start) / total, 0, 1);
        var s = p < 0.25 ? Stage.Nap : p < 0.75 ? Stage.Kitten : Stage.Grown;
        if (s == Stage.Grown && SmallBlock(b, now)) s = Stage.Kitten;
        return (s, p);
    }

    /// <summary>Under 25 minutes, a cat comes home a kitten.</summary>
    public static bool SmallBlock(Block b, long now) => b.Stopwatch ? now - b.Start < 25 * 60_000 : b.Minutes < 25;

    public static string PathFor(string cat, Stage stage, bool small = false) => stage switch
    {
        Stage.Nap => Nap(cat),
        Stage.Kitten => Kitten(cat),
        Stage.Grown => Portrait(cat),
        Stage.Home => small ? Kitten(cat) : Portrait(cat),
        _ => Gone,
    };

    /// <summary>How big the cat is in its window (web/app.js growSize).</summary>
    public static double Size(Stage stage, double p) => stage switch
    {
        Stage.Nap => 0.55 + 0.13 * (p / 0.25),
        Stage.Kitten => 0.68 + 0.18 * ((p - 0.25) / 0.5),
        Stage.Grown => 0.86 + 0.14 * ((p - 0.75) / 0.25),
        Stage.Home => 1,
        _ => 0.8,
    };
}
