using System.Numerics;
using Chataltul.Core;
using Microsoft.Graphics.Canvas;
using Microsoft.Graphics.Canvas.Brushes;
using Microsoft.Graphics.Canvas.Effects;
using Microsoft.Graphics.Canvas.Geometry;
using Microsoft.Graphics.Canvas.Text;
using Microsoft.Graphics.Canvas.UI.Xaml;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Windows.Foundation;
using Windows.UI;

namespace Chataltul;

// The Cat Café in 3D, drawn with Win2D. It follows the web's cafe3d.js (and the Mac's CafeScene.swift): a floating
// room seen from above at 60 degrees (the camera is orthographic; the floor is squashed by K and a tile of height
// rises by KZ on screen), with the things you own placed by that file's own table, the cats that came home sitting
// on them, and the two back walls. It spins in quarter turns when you let go. What is left out: the cats walking
// about, the arrival drop and its sparkles (the room is a picture of a day, not a toy).

/// <summary>A cat in the café: the block it came home from (Key), which cat, its paintings, and what its tip says.</summary>
sealed record CafeCatData(string Key, string Id, string Src, string? NapSrc, bool Kitten, string Title, string Sub,
    IReadOnlyDictionary<string, string>? Poses);

/// <summary>What the café is made of: its size, theme and light, the things in it, the cats that came home, the kittens that left.</summary>
sealed class CafeData
{
    /// <summary>"day", "week", "month" or "big": how many tiles across (a room that's too full grows).</summary>
    public string Size { get; init; } = "day";
    public string Theme { get; init; } = "classic";
    /// <summary>"morning", "day", "evening" or "night".</summary>
    public string Light { get; init; } = "day";
    /// <summary>Dark mode: the room is dimmed a little.</summary>
    public bool Dim { get; init; }
    /// <summary>The ids of the things the account owns.</summary>
    public IReadOnlyList<string> Things { get; init; } = [];
    /// <summary>Each thing's side and back paintings, where it has them.</summary>
    public IReadOnlyDictionary<string, (string? Side, string? Back)> Views { get; init; } = new Dictionary<string, (string? Side, string? Back)>();
    public IReadOnlyList<CafeCatData> Cats { get; init; } = [];
    /// <summary>The blocks whose kittens wandered off: they leave paw prints.</summary>
    public IReadOnlyList<string> Gone { get; init; } = [];
    public int Seed { get; init; } = 7;

    /// <summary>What the café is made of, for these blocks: the cats that came home, the things the account owns, the theme and the time of day.</summary>
    public static CafeData Make(Store store, IReadOnlyList<Hist> blocks, Span span, bool dark, int seed)
    {
        var st = store.State;
        var views = new Dictionary<string, (string? Side, string? Back)>();
        foreach (var t in st.Things) views[t.Id] = (t.Side, t.Back);
        var hour = DateTime.Now.Hour;
        return new CafeData
        {
            Size = span == Span.Day ? "day" : span == Span.Week ? "week" : "month",
            Theme = st.View.Settings.Theme,
            Light = hour is >= 5 and < 10 ? "morning" : hour is >= 10 and < 17 ? "day" : hour is >= 17 and < 21 ? "evening" : "night",
            Dim = dark,
            Things = st.Things.Select(t => t.Id).ToList(),
            Views = views,
            Seed = seed,
            Cats = blocks.Where(b => b.Kitten == "home").OrderBy(b => b.Start).Take(60).Select(b =>
            {
                var c = st.Cat(b.Cat);
                return new CafeCatData(b.Id, c.Id, c.Portrait ?? $"cats/{c.Id}-1.webp", c.Nap ?? $"cats/{c.Id}-nap-1.webp",
                    b.Minutes < 25, c.Name, b.Title, c.Poses.Count > 0 ? c.Poses : null);
            }).ToList(),
            Gone = blocks.Where(b => b.Kitten == "gone").Select(b => b.Id).ToList(),
        };
    }
}

// ---------------------------------------------------------------- the paintings, trimmed to their painted part

/// <summary>A painting trimmed to its painted part: the bitmap, the part of it to draw (in its DIPs), and that part's height over its width.</summary>
sealed record CafePiece(CanvasBitmap Bitmap, Rect Source, double Aspect);

/// <summary>
/// The paintings as Win2D bitmaps, loaded on the canvas's device the first time the room asks for one and trimmed
/// to their painted part like the Mac's CafeArt. A painting arrives a moment later and the room is drawn again; a
/// missing file is simply not drawn. A new device (the old one lost) starts the cache over.
/// </summary>
sealed class CafePaintings(CanvasControl canvas)
{
    readonly Dictionary<string, CafePiece?> pieces = [];
    readonly HashSet<string> loading = [];
    int generation;

    public void Reset()
    {
        foreach (var p in pieces.Values) p?.Bitmap.Dispose();
        pieces.Clear();
        loading.Clear();
        generation++;
    }

    /// <summary>The painting by its web path, if it has arrived; else it is sent for (outside the drawing) and null for now.</summary>
    public CafePiece? Get(string webPath)
    {
        if (pieces.TryGetValue(webPath, out var p)) return p;
        if (loading.Add(webPath))
        {
            var gen = generation;
            Ui.Post(() => _ = Load(webPath, gen));
        }
        return null;
    }

    async Task Load(string webPath, int gen)
    {
        CafePiece? piece = null;
        var file = Path.Join(AppContext.BaseDirectory, "art", Art.File(webPath));
        if (gen == generation && File.Exists(file))
        {
            try
            {
                var bitmap = await CanvasBitmap.LoadAsync(canvas, file);
                if (gen != generation) { bitmap.Dispose(); return; }
                piece = Trim(bitmap);
            }
            catch (Exception e) { Log.Write($"café painting {webPath}: {e.Message}"); }
        }
        if (gen != generation) return;
        pieces[webPath] = piece;
        loading.Remove(webPath);
        canvas.Invalidate();
    }

    /// <summary>The box around the pixels that are painted (alpha over 30), looked at every second pixel.</summary>
    static CafePiece Trim(CanvasBitmap bitmap)
    {
        var px = bitmap.SizeInPixels;
        int w = (int)px.Width, h = (int)px.Height;
        int bx = 0, by = 0, bw = w, bh = h;
        byte[] bytes = [];
        try { bytes = bitmap.GetPixelBytes(); } catch (Exception) { }
        // BGRA, 8 bits each: the alpha is the fourth byte
        if (w > 0 && h > 0 && bytes.Length == w * h * 4)
        {
            int x0 = w, y0 = h, x1 = 0, y1 = 0;
            for (var y = 0; y < h; y += 2)
                for (var x = 0; x < w; x += 2)
                    if (bytes[(y * w + x) * 4 + 3] > 30) { x0 = Math.Min(x0, x); x1 = Math.Max(x1, x); y0 = Math.Min(y0, y); y1 = Math.Max(y1, y); }
            if (x1 > x0 && y1 > y0) { bx = x0; by = y0; bw = Math.Min(w - x0, x1 - x0 + 2); bh = Math.Min(h - y0, y1 - y0 + 2); }
        }
        var dip = bitmap.Size.Width / Math.Max(1, w);
        return new CafePiece(bitmap, new Rect(bx * dip, by * dip, bw * dip, bh * dip), (double)bh / Math.Max(1, bw));
    }
}

// ---------------------------------------------------------------- the scene

/// <summary>The room: where everything stands, the camera that turns it, and the drawing. <c>art</c> gives a painting by its web path.</summary>
sealed class CafeScene(Func<string, CafePiece?> art)
{
    const double K = 0.5, KZ = 0.866, QUARTER = Math.PI / 2;

    sealed record Theme3D(Color Wall0, Color Wall1, Color Dots, Color Skirt, Color SkirtLine, Color Floor0, Color Floor1, Color Board, Color Slab, Color Trim);

    static Color C(uint rgb, double a = 1) => Palette.C(rgb, a);

    static readonly Dictionary<string, Theme3D> THEMES = new()
    {
        ["classic"] = new(C(0xFDF5E8), C(0xF5E4CB), C(0xC49668, 0.32), C(0xECD2AE), C(0xD9B386), C(0xE8BD85), C(0xDBA56B), C(0x7C4A22, 0.17), C(0xB9845A), C(0xF6DCB6)),
        ["mint"] = new(C(0xE8F6EE), C(0xD3EBDE), C(0x3C8C64, 0.24), C(0xCFE3D6), C(0xA9C9B5), C(0xEAD4AB), C(0xDCC191), C(0x785528, 0.15), C(0xA48A66), C(0xF1E5C9)),
        ["sakura"] = new(C(0xFDE9F0), C(0xF8D5E2), C(0xE1789B, 0.36), C(0xF3C9D6), C(0xE3A2B7), C(0xF2DDC9), C(0xE5C9B0), C(0x965A46, 0.13), C(0xC99C88), C(0xFBE8DD)),
        ["library"] = new(C(0x31604C), C(0x284F3E), C(0xFFE6B4, 0.16), C(0x5B3B26), C(0x462C1C), C(0x7C5438), C(0x684430), C(0x000000, 0.22), C(0x4D3322), C(0x8C6748)),
        ["garden"] = new(C(0xEAF7FF), C(0xCFE9FB), C(0xFFFFFF, 0.85), C(0x8FCF7A), C(0x76B862), C(0x9FD480), C(0x86C266), C(0x3C7828, 0.16), C(0x7B9A5A), C(0xD8EFC6)),
        ["night"] = new(C(0x232849), C(0x2D3257), C(0xFFF0B4, 0.6), C(0x3A3550), C(0x4C4568), C(0x4F3D2E), C(0x403126), C(0x000000, 0.28), C(0x2E2622), C(0x5D4F43)),
    };

    /// <summary>Where a thing stands (u, v: a share of the room), how wide it is in tiles, its footprint, where a cat sits on it, where its light is, and which goes first.</summary>
    sealed record Place(double U, double V, double W, int Pri, double[]? Fp = null, double? Perch = null, double? Light = null);

    static readonly Dictionary<string, Place> PLACE = new()
    {
        ["cattree"] = new(0.12, 0.12, 1.05, 1, Perch: 0.93), ["cushion"] = new(0.47, 0.5, 0.95, 1, Perch: 0.2),
        ["box"] = new(0.8, 0.8, 1.0, 1, Perch: 0.48), ["armchair"] = new(0.15, 0.8, 1.3, 1, Fp: [1.05, 1.2], Perch: 0.42),
        ["counter"] = new(0.6, 0.12, 2.05, 2, Fp: [2.1, 1.1], Perch: 0.6), ["fireplace"] = new(0.1, 0.48, 1.35, 2, Fp: [0.9, 1.45], Light: 0.35),
        ["bookshelf"] = new(0.9, 0.1, 0.95, 3, Perch: 0.97), ["lamp"] = new(0.09, 0.3, 0.72, 4, Light: 0.9),
        ["plant"] = new(0.9, 0.48, 0.84, 5), ["fishbowl"] = new(0.9, 0.3, 0.64, 6), ["yarnbasket"] = new(0.43, 0.88, 0.72, 6),
        ["jackolantern"] = new(0.64, 0.34, 0.82, 7, Light: 0.6), ["cauldron"] = new(0.3, 0.66, 0.8, 7, Light: 0.5),
        ["snowman"] = new(0.72, 0.56, 0.85, 7), ["candles"] = new(0.88, 0.66, 0.42, 8, Light: 0.7),
        ["sled"] = new(0.56, 0.9, 1.1, 8, Fp: [1.15, 0.8]), ["snowglobe"] = new(0.32, 0.3, 0.5, 9),
    };
    static readonly (string Id, char Edge, double U, double V, double W)[] WALLS = [("window", 'N', 0.33, 0.3, 1.5), ("clock", 'W', 0.68, 0.42, 0.62)];
    static readonly HashSet<string> SOFT = ["cushion", "box", "armchair"];
    static readonly (char Edge, (int X, int Y) A, (int X, int Y) B)[] EDGES = [('N', (0, 0), (1, 0)), ('W', (0, 0), (0, 1)), ('E', (1, 0), (1, 1)), ('S', (0, 1), (1, 1))];
    static readonly Dictionary<string, (int N, int Fits)> SIZES = new() { ["day"] = (4, 8), ["week"] = (6, 22), ["month"] = (8, 38), ["big"] = (10, 60) };
    static readonly string[] ORDER = ["day", "week", "month", "big"];
    static readonly Dictionary<string, double> POSE_W = new() { ["walk"] = 0.98, ["loaf"] = 0.82, ["stretch"] = 1.05, ["wash"] = 0.8, ["back"] = 0.78 };
    static readonly string[] PICKS = ["loaf", "wash", "stretch", "back"];
    static readonly (float X, float Y)[] TOES = [(0.07f, -0.045f), (0.09f, 0f), (0.07f, 0.045f)];

    /// <summary>The light by the time of day: a tint over the room, a glow by the things that give light, and how far it reaches.</summary>
    static readonly Dictionary<string, (Color? Tint, Color? Glow, double R)> TINTS = new()
    {
        ["morning"] = (C(0xA0C8FF, 0.12), null, 0), ["day"] = (null, null, 0),
        ["evening"] = (C(0xFF7828, 0.16), C(0xFFD696, 0.28), 1.9),
        ["night"] = (C(0x121642, 0.3), C(0xFFC878, 0.34), 2.0),
    };

    /// <summary>The same numbers every time for the same seed (xorshift, 32 bits).</summary>
    static Func<double> Rng(long seed)
    {
        var s = unchecked((uint)(seed == 0 ? 1 : seed));
        return () => { s ^= s << 13; s ^= s >> 17; s ^= s << 5; return s / 4294967296.0; };
    }

    static long Hash(string str)
    {
        uint h = 7;
        foreach (var r in str.EnumerateRunes()) h = unchecked(h * 31 + (uint)r.Value);
        return h;
    }

    /// <summary>Never throws when a is over b (a thing wider than the room): a wins, as in Swift.</summary>
    static double Clamp(double x, double a, double b) => Math.Max(a, Math.Min(b, x));
    static double Hypot(double x, double y) => Math.Sqrt(x * x + y * y);
    static Vector2 Vec(double x, double y) => new((float)x, (float)y);

    sealed class Sprite
    {
        public string Id = "", Key = "", Src = "";
        public double W = 1, X, Y, Z;
        public double[]? Fp;
        public double? Perch, Light;
        public int Pri;
        public Sprite? Seat;
        public bool IsCat, Nap, Flip, Kitten;
        public double Phase;
        public string Title = "", Sub = "";
        /// <summary>Where it was last drawn, for a click.</summary>
        public (double X, double Y, double W, double H)? Box;
        public double Depth;
    }

    sealed record WallItem(string? Id, char Edge, double U, double V, double W, bool Door = false);
    readonly record struct Paw(double X, double Y, double A, int Side);

    public int N { get; private set; } = 6;
    double Center => N / 2.0;
    string theme = "classic", light = "day";
    bool dim;
    List<Sprite> things = [], cats = [];
    List<WallItem> walls = [];
    double[]? rug;
    List<Paw> paws = [];
    IReadOnlyDictionary<string, (string? Side, string? Back)> views = new Dictionary<string, (string? Side, string? Back)>();
    string sig = "";
    /// <summary>How tall the walls are, how deep the base under the floor, how wide its rim.</summary>
    const double WallH = 2.25, BaseDepth = 0.5, Rim = 0.14;

    // the camera
    public double Angle = Math.PI / 4, Goal = Math.PI / 4, SpinV;
    public bool Dragging;
    double S, ox, oy, width = 1, height = 1, ca = 1, sa;
    double time;
    public int CatCount => cats.Count;

    // ---------------- what to draw

    public void Show(CafeData data)
    {
        var signature = $"{data.Size}|{string.Join(",", data.Things)}|{string.Join(",", data.Cats.Select(c => c.Key + c.Src))}|{string.Join(",", data.Gone)}|{data.Seed}";
        theme = THEMES.ContainsKey(data.Theme) ? data.Theme : "classic";
        light = data.Light; dim = data.Dim; views = data.Views;
        if (signature == sig) return;
        sig = signature;
        var i = Math.Max(0, Array.IndexOf(ORDER, data.Size));
        while (i < ORDER.Length - 1 && data.Cats.Count > SIZES[ORDER[i]].Fits) i++;
        N = SIZES[ORDER[i]].N;
        var r = Rng(data.Seed);
        Layout(data.Things);
        cats = []; paws = [];
        var seats = things.Where(t => t.Perch != null).OrderBy(t => t.Pri).ToList();
        var posers = 0;
        for (var k = 0; k < data.Cats.Count; k++)
        {
            var cat = data.Cats[k];
            if (k < seats.Count)
            {
                var seat = seats[k];
                AddCat(cat, seat, 0, 0, cat.NapSrc != null && SOFT.Contains(seat.Id) && r() < 0.55);
                continue;
            }
            if (FreeSpot(r) is not { } spot) continue;
            var m = AddCat(cat, null, spot.X, spot.Y, cat.NapSrc != null && !cat.Kitten && r() < 0.3);
            // a cat with pose paintings stands in one of them
            if (cat.Poses is { } poses && posers < 3 && !cat.Kitten)
            {
                posers++;
                var pick = PICKS[(int)(r() * 4) % 4];
                if (poses.TryGetValue(pick, out var p)) { m.Src = p; m.W = POSE_W.GetValueOrDefault(pick, 0.8); m.Nap = pick == "loaf"; }
            }
        }
        // the kittens that wandered off left paw prints
        foreach (var key in data.Gone.Take(6))
        {
            var pr = Rng(Hash(key));
            if (FreeSpot(pr) is not { } s) continue;
            var a = pr() * Math.PI * 2;
            Trail(s.X, s.Y, Clamp(s.X + Math.Cos(a) * 1.3, 0.3, N - 0.3), Clamp(s.Y + Math.Sin(a) * 1.3, 0.3, N - 0.3));
        }
    }

    /// <summary>Every painting the room may draw as it is now (from any side), so they can be sent for at once.</summary>
    public IEnumerable<string> Paths()
    {
        if (rug != null) yield return "cafe/rug-1.webp";
        foreach (var it in walls) if (it.Id != null) yield return $"cafe/{it.Id}-1.webp";
        foreach (var t in things)
        {
            yield return t.Src;
            if (!views.TryGetValue(t.Id, out var v)) continue;
            if (v.Side != null) yield return v.Side;
            if (v.Back != null) yield return v.Back;
        }
        foreach (var c in cats) yield return c.Src;
    }

    void Layout(IReadOnlyList<string> ids)
    {
        things = []; walls = []; rug = null;
        var own = ids.ToHashSet();
        double nn = N;
        if (own.Contains("rug")) { double rw = nn * 0.42, rh = nn * 0.32; rug = [nn / 2 - rw / 2 + nn * 0.04, nn / 2 - rh / 2 + nn * 0.06, rw, rh]; }
        foreach (var (wid, edge, u, v, ww) in WALLS)
            if (own.Contains(wid)) walls.Add(new WallItem(wid, edge, u * nn, v, ww));
        if (own.Contains("batgarland"))
            foreach (var e in "NW") walls.Add(new WallItem("batgarland", e, nn / 2, 0.05, Math.Min(nn - 0.6, 4.2)));
        walls.Add(new WallItem(null, 'E', nn * 0.55, 0, 0, Door: true));
        var sill = own.Contains("snowglobe") && own.Contains("window");
        if (sill && walls.FirstOrDefault(x => x.Id == "window") is { } win) walls.Add(new WallItem("snowglobe", 'N', win.U - 0.42, win.V + 0.98, 0.36));
        var list = PLACE.Where(kv => own.Contains(kv.Key) && !(kv.Key == "snowglobe" && sill))
            .OrderBy(kv => kv.Value.Pri).ThenBy(kv => kv.Key, StringComparer.Ordinal).ToList();
        var big = N >= 6 ? 1 : 0.86;
        foreach (var (id, p) in list)
        {
            var t = new Sprite { Id = id, Src = $"cafe/{id}-1.webp", W = p.W * big, Fp = p.Fp?.Select(f => f * big).ToArray(), Perch = p.Perch, Light = p.Light, Pri = p.Pri };
            if (SpotNear(p.U * nn, p.V * nn, t) is not { } spot) continue;
            t.X = spot.X; t.Y = spot.Y;
            things.Add(t);
        }
    }

    static (double W, double D) Foot(Sprite t) => t.Fp is { } f ? (f[0], f[1]) : (t.W * 0.9, t.W * 0.9);

    (double X, double Y)? SpotNear(double x, double y, Sprite t)
    {
        double nn = N;
        var (fw, fd) = Foot(t);
        bool Ok(double px, double py)
        {
            if (!(px - fw / 2 >= 0.08 && px + fw / 2 <= nn - 0.08 && py - fd / 2 >= 0.08 && py + fd / 2 <= nn - 0.08)) return false;
            foreach (var o in things)
            {
                var (ow, od) = Foot(o);
                if (Math.Abs(px - o.X) < (fw + ow) / 2 + 0.05 && Math.Abs(py - o.Y) < (fd + od) / 2 + 0.05) return false;
            }
            if (rug is { } rg && t.Id != "rug" && px > rg[0] + 0.3 && px < rg[0] + rg[2] - 0.3 && py > rg[1] + 0.3 && py < rg[1] + rg[3] - 0.3
                && !SOFT.Contains(t.Id) && t.Id != "yarnbasket") return false;
            return true;
        }
        double cx = Clamp(x, fw / 2 + 0.08, nn - fw / 2 - 0.08), cy = Clamp(y, fd / 2 + 0.08, nn - fd / 2 - 0.08);
        if (Ok(cx, cy)) return (cx, cy);
        for (var rad = 0.25; rad < nn * 0.6; rad += 0.25)
            for (var k = 0; k < 16; k++)
            {
                var a = k / 16.0 * Math.PI * 2;
                double qx = cx + Math.Cos(a) * rad, qy = cy + Math.Sin(a) * rad;
                if (Ok(qx, qy)) return (qx, qy);
            }
        return null;
    }

    bool Occupied(double x, double y, double pad = 0.55)
    {
        foreach (var t in things)
        {
            var (fw, fd) = Foot(t);
            if (Math.Abs(x - t.X) < fw / 2 + pad * 0.6 && Math.Abs(y - t.Y) < fd / 2 + pad * 0.6) return true;
        }
        foreach (var c in cats) if (c.Seat == null && Hypot(c.X - x, c.Y - y) < pad * 1.3) return true;
        return false;
    }

    (double X, double Y)? FreeSpot(Func<double> r)
    {
        double nn = N;
        for (var k = 0; k < 120; k++)
        {
            double x = 0.6 + r() * (nn - 1.2), y = 0.6 + r() * (nn - 1.2);
            if (!Occupied(x, y, k > 90 ? 0.4 : 0.55)) return (x, y);
        }
        return null;
    }

    Sprite AddCat(CafeCatData d, Sprite? seat, double x, double y, bool nap)
    {
        var asleep = nap && d.NapSrc != null && !d.Kitten;
        var s = new Sprite
        {
            IsCat = true, Key = d.Key, Id = d.Id, Title = d.Title, Sub = d.Sub,
            Src = asleep ? d.NapSrc! : d.Src, Nap = asleep, Kitten = d.Kitten,
            W = d.Kitten ? 0.66 : asleep ? 0.9 : 0.8,
            Seat = seat,
            X = seat != null ? seat.X + 0.04 : x, Y = seat != null ? seat.Y + 0.06 : y,
            Phase = Hash(d.Key) % 600 / 100.0,
            Flip = Hash(d.Key) % 2 == 1,
        };
        if (seat != null) s.W = Math.Min(s.W, seat.W * (asleep ? 0.86 : 0.76));
        cats.Add(s);
        return s;
    }

    void Trail(double x0, double y0, double x1, double y1)
    {
        var m = Math.Max(3, (int)Math.Round(Hypot(x1 - x0, y1 - y0) / 0.32, MidpointRounding.AwayFromZero));
        var ang = Math.Atan2(y1 - y0, x1 - x0);
        for (var i = 0; i <= m; i++)
        {
            var t = (double)i / m;
            paws.Add(new Paw(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, ang, i % 2));
        }
    }

    double HeightZ(Sprite s) => art(s.Src) is { } p ? s.W * p.Aspect / KZ : s.W / KZ;

    // ---------------- the camera

    /// <summary>One frame: the spring that turns the room to its goal, and the room fitted to the canvas (in DIPs).</summary>
    public void Step(double dt, double w, double h)
    {
        dt = Math.Clamp(dt, 0, 0.05);
        time += dt;
        width = w; height = h;
        if (!Dragging)
        {
            var d = Goal - Angle;
            SpinV += (d * 70 - SpinV * 14) * dt;
            Angle += SpinV * dt;
            if (Math.Abs(d) < 1e-4 && Math.Abs(SpinV) < 1e-3) { Angle = Goal; SpinV = 0; }
        }
        ca = Math.Cos(Angle); sa = Math.Sin(Angle);
        // fit the room to the canvas
        var r = (N / 2.0 + Rim) * Math.Sqrt(2);
        double x0 = -r, x1 = r, y0 = -r * K - WallH * KZ - 0.15, y1 = r * K + (BaseDepth + 0.35) * KZ;
        const double m = 12;
        S = Math.Min((width - 2 * m) / (x1 - x0), (height - 2 * m) / (y1 - y0));
        ox = width / 2 - (x0 + x1) / 2 * S;
        oy = height / 2 - (y0 + y1) / 2 * S;
    }

    /// <summary>Is there room to draw in?</summary>
    public bool Ready => S > 0 && width > 0 && height > 0;
    public bool Moving => Math.Abs(Goal - Angle) > 1e-4 || Math.Abs(SpinV) > 1e-3 || Dragging;

    public void Spin(int by) => Goal += by * QUARTER;
    public void Snap() => Goal = Math.Round((Angle - Math.PI / 4) / QUARTER, MidpointRounding.AwayFromZero) * QUARTER + Math.PI / 4;
    /// <summary>Straight to the goal, with no spring (the self-test).</summary>
    public void Settle() { Angle = Goal; SpinV = 0; }

    (Vector2 Pos, double D) P(double x, double y, double z = 0)
    {
        double dx = x - Center, dy = y - Center;
        double rx = dx * ca - dy * sa, ry = dx * sa + dy * ca;
        return (Vec(ox + rx * S, oy + ry * S * K - z * S * KZ), ry);
    }
    Vector2 Pt(double x, double y, double z = 0) => P(x, y, z).Pos;
    int Quarter() => ((int)Math.Round((Angle - Math.PI / 4) / QUARTER, MidpointRounding.AwayFromZero) % 4 + 4) % 4;

    // ---------------- drawing

    static Color Shade(Color c, double f)
    {
        byte Ch(byte v)
        {
            var x = v / 255.0;
            return (byte)Math.Round(255 * (f < 0 ? x * (1 + f) : x + (1 - x) * f));
        }
        return Color.FromArgb(255, Ch(c.R), Ch(c.G), Ch(c.B));
    }

    static void Poly(CanvasDrawingSession ds, Vector2[] pts, Color? fill, Color? stroke = null, float lw = 1)
    {
        using var g = CanvasGeometry.CreatePolygon(ds, pts);
        if (fill is { } f) ds.FillGeometry(g, f);
        if (stroke is { } s) ds.DrawGeometry(g, s, lw);
    }

    /// <summary>A matrix that lays flat drawing (units of tiles) on a plane of the room.</summary>
    static Matrix3x2 Plane(Vector2 o, Vector2 u, Vector2 v) => new(u.X - o.X, u.Y - o.Y, v.X - o.X, v.Y - o.Y, o.X, o.Y);

    static (double X, double Y) Out(char edge) => edge == 'N' ? (0.0, -Rim) : edge == 'S' ? (0.0, Rim) : edge == 'W' ? (-Rim, 0.0) : (Rim, 0.0);

    /// <summary>
    /// The room with its light. The light is a tint over everything drawn and a glow by the lamps, painted only
    /// where the room is (the Mac's sourceAtop): the room is recorded into one command list and the light into
    /// another, and a CompositeEffect in SourceAtop puts the second on the first (destination first, then source).
    /// </summary>
    public void Render(CanvasDrawingSession ds)
    {
        if (!HasLight) { Draw(ds); return; }
        using var room = new CanvasCommandList(ds);
        using (var rs = room.CreateDrawingSession()) Draw(rs);
        using var tint = new CanvasCommandList(ds);
        using (var ts = tint.CreateDrawingSession()) DrawLight(ts);
        using var atop = new CompositeEffect { Mode = CanvasComposite.SourceAtop };
        atop.Sources.Add(room);
        atop.Sources.Add(tint);
        ds.DrawImage(atop);
    }

    void Draw(CanvasDrawingSession ds)
    {
        var T = THEMES[theme];
        double nn = N, R = Rim, D = BaseDepth, H = WallH;
        // the shadow under the floating café
        var c0 = Pt(nn / 2, nn / 2, -D - 0.2);
        var sr = (float)(nn * 0.78 * S);
        ds.Transform = Matrix3x2.CreateScale(1, 0.42f) * Matrix3x2.CreateTranslation(c0);
        using (var shadow = new CanvasRadialGradientBrush(ds, C(0x281E14, 0.22), C(0x281E14, 0)) { Center = Vector2.Zero, RadiusX = sr, RadiusY = sr })
            ds.FillEllipse(Vector2.Zero, sr, sr, shadow);
        ds.Transform = Matrix3x2.Identity;
        // the base: the sides facing us, then the rim on top
        (double X, double Y)[] corners = [(-R, -R), (nn + R, -R), (nn + R, nn + R), (-R, nn + R)];
        var mortar = C(0x3C1E0A, 0.16);
        for (var i = 0; i < 4; i++)
        {
            var a = corners[i];
            var b = corners[(i + 1) % 4];
            if (P((a.X + b.X) / 2, (a.Y + b.Y) / 2).D <= 0) continue;
            var facing = Math.Abs(a.X == b.X ? ca : sa);
            Poly(ds, [Pt(a.X, a.Y, 0), Pt(b.X, b.Y, 0), Pt(b.X, b.Y, -D), Pt(a.X, a.Y, -D)], Shade(T.Slab, -0.12 * (1 - facing)));
            for (var row = 1; row < 3; row++)
            {
                var z = -D * row / 3;
                ds.DrawLine(Pt(a.X, a.Y, z), Pt(b.X, b.Y, z), mortar, 1);
            }
            var L = nn + 2 * R;
            for (var row = 0; row < 3; row++)
                for (var k = 0; k <= (int)(L * 1.6); k++)
                {
                    var t = (k + row % 2 * 0.5) / (L * 1.6);
                    if (t <= 0 || t >= 1) continue;
                    double x = a.X + (b.X - a.X) * t, y = a.Y + (b.Y - a.Y) * t;
                    ds.DrawLine(Pt(x, y, -D * row / 3), Pt(x, y, -D * (row + 1) / 3), mortar, 1);
                }
        }
        Poly(ds, corners.Select(cn => Pt(cn.X, cn.Y, 0)).ToArray(), T.Trim);
        // the floor, in boards
        for (var j = 0; j < N; j++)
            for (var i = 0; i < N; i++)
                Poly(ds, [Pt(i, j), Pt(i + 1, j), Pt(i + 1, j + 1), Pt(i, j + 1)], (i + j) % 2 == 1 ? T.Floor0 : T.Floor1);
        for (var k = 0; k <= N * 2; k++) ds.DrawLine(Pt(0, k / 2.0), Pt(nn, k / 2.0), T.Board, 1);
        // the rug, laid flat
        if (rug is { } rg && art("cafe/rug-1.webp") is { } rp)
        {
            ds.Transform = Plane(Pt(rg[0], rg[1]), Pt(rg[0] + 1, rg[1]), Pt(rg[0], rg[1] + 1));
            ds.DrawImage(rp.Bitmap, new Rect(0, 0, rg[2], rg[3]), rp.Source, 0.95f, CanvasImageInterpolation.HighQualityCubic);
            ds.Transform = Matrix3x2.Identity;
        }
        // paw prints
        var pawColor = C(0x6E4628, 0.4);
        foreach (var pw in paws)
        {
            ds.Transform = Matrix3x2.CreateTranslation(0, pw.Side == 1 ? 0.07f : -0.07f) * Matrix3x2.CreateRotation((float)pw.A)
                * Plane(Pt(pw.X, pw.Y), Pt(pw.X + 1, pw.Y), Pt(pw.X, pw.Y + 1));
            ds.FillEllipse(Vector2.Zero, 0.06f, 0.05f, pawColor);
            foreach (var (tx, ty) in TOES) ds.FillEllipse(new Vector2(tx, ty), 0.022f, 0.022f, pawColor);
        }
        ds.Transform = Matrix3x2.Identity;
        // the two walls at the back (the front two are cut away, like a dollhouse)
        var back = new List<(char Edge, (double X, double Y) A, (double X, double Y) B, double D)>();
        foreach (var (edge, ea, eb) in EDGES)
        {
            (double X, double Y) a = (ea.X * nn, ea.Y * nn), b = (eb.X * nn, eb.Y * nn);
            var mid = P((a.X + b.X) / 2, (a.Y + b.Y) / 2);
            if (mid.D < -0.01 * nn) back.Add((edge, a, b, mid.D));
        }
        back = back.OrderBy(bw => bw.D).ToList();
        foreach (var wl in back) DrawWall(ds, wl.Edge, wl.A, wl.B, T);
        foreach (var wl in back)
        {
            var o = Out(wl.Edge);
            var (A, B) = (wl.A, wl.B);
            Poly(ds, [Pt(A.X, A.Y, H), Pt(B.X, B.Y, H), Pt(B.X + o.X, B.Y + o.Y, H), Pt(A.X + o.X, A.Y + o.Y, H)], T.Trim, C(0x5A3C1E, 0.18));
            foreach (var e in new[] { A, B })
            {
                if (P(e.X, e.Y).D < -0.4 * nn) continue;
                Poly(ds, [Pt(e.X, e.Y, 0), Pt(e.X + o.X, e.Y + o.Y, 0), Pt(e.X + o.X, e.Y + o.Y, H), Pt(e.X, e.Y, H)], Shade(T.Trim, -0.08));
            }
        }
        // everything standing, back to front
        var q = Quarter();
        var all = things.Concat(cats).ToList();
        foreach (var s in all) s.Depth = P(s.X, s.Y).D + (s.Seat != null ? 0.08 : 0) + (s.IsCat ? 0.01 : 0);
        foreach (var s in all.OrderBy(x => x.Depth))
        {
            if (s.Seat is { } seat) s.Z = HeightZ(seat) * (seat.Perch ?? 0);
            if (s.IsCat) DrawSprite(ds, s, s.Src, s.Flip);
            else
            {
                var v = ThingSrc(s, q);
                DrawSprite(ds, s, v.Src, v.Flip);
            }
        }
        // low walls along the front edges, so the room has a lip
        const double lip = 0.2;
        foreach (var (edge, ea, eb) in EDGES)
        {
            (double X, double Y) a = (ea.X * nn, ea.Y * nn), b = (eb.X * nn, eb.Y * nn);
            if (P((a.X + b.X) / 2, (a.Y + b.Y) / 2).D < -0.01 * nn) continue;
            var o = Out(edge);
            (double X, double Y) fa = (a.X + o.X, a.Y + o.Y), fb = (b.X + o.X, b.Y + o.Y);
            Poly(ds, [Pt(fa.X, fa.Y, 0), Pt(fb.X, fb.Y, 0), Pt(fb.X, fb.Y, lip), Pt(fa.X, fa.Y, lip)], Shade(T.Skirt, -0.06));
            Poly(ds, [Pt(a.X, a.Y, lip), Pt(b.X, b.Y, lip), Pt(fb.X, fb.Y, lip), Pt(fa.X, fa.Y, lip)], T.Trim, C(0x5A3C1E, 0.15));
        }
    }

    /// <summary>A thing seen from the side or the back, when the room is turned and it has those paintings (else its front, mirrored).</summary>
    (string Src, bool Flip) ThingSrc(Sprite t, int q)
    {
        if (!views.TryGetValue(t.Id, out var vw) || q == 0) return (t.Src, false);
        if (q == 2 && vw.Back is { } b) return (b, false);
        if (q == 2 && vw.Side is { } s) return (s, true);
        if ((q == 1 || q == 3) && vw.Side is { } side) return (side, q == 3);
        return (t.Src, q == 3);
    }

    void DrawSprite(CanvasDrawingSession ds, Sprite s, string src, bool flip)
    {
        if (art(src) is not { } piece) { s.Box = null; return; }
        var p = Pt(s.X, s.Y, s.Z);
        double w = s.W * S, h = w * piece.Aspect;
        if (s.IsCat && !CafeView.Still)
        {   // breathing: a little taller, a little narrower
            var b = Math.Sin((time + s.Phase) * Math.PI * 2 / (s.Nap ? 5.2 : 3.6));
            h *= 1 + 0.024 * b; w *= 1 - 0.008 * b;
        }
        var sp = Pt(s.X, s.Y, s.Seat != null ? s.Z : 0);
        ds.FillEllipse(sp, (float)(w * 0.4), (float)(w * 0.4 * K), C(0x462808, s.IsCat ? 0.18 : 0.14));
        ds.Transform = (flip ? Matrix3x2.CreateScale(-1, 1) : Matrix3x2.Identity) * Matrix3x2.CreateTranslation(p);
        ds.DrawImage(piece.Bitmap, new Rect(-w / 2, -h, w, h), piece.Source, 1f, CanvasImageInterpolation.HighQualityCubic);
        ds.Transform = Matrix3x2.Identity;
        s.Box = (p.X - w / 2, p.Y - h, w, h);
    }

    void DrawWall(CanvasDrawingSession ds, char edge, (double X, double Y) a0, (double X, double Y) b0, Theme3D T)
    {
        var (A, B) = (a0, b0);
        var flip = false;
        if (Pt(B.X, B.Y).X < Pt(A.X, A.Y).X) { (A, B) = (B, A); flip = true; }
        double L = N, H = WallH;
        Vector2 o = Pt(A.X, A.Y, H), e = Pt(B.X, B.Y, H);
        var wall = new Matrix3x2((float)((e.X - o.X) / L), (float)((e.Y - o.Y) / L), 0, (float)(S * KZ), o.X, o.Y);
        ds.Transform = wall;
        var face = new Rect(0, 0, L, H);
        using (var paint = new CanvasLinearGradientBrush(ds, T.Wall0, T.Wall1) { StartPoint = Vector2.Zero, EndPoint = new Vector2(0, (float)H) })
            ds.FillRectangle(face, paint);
        var lit = edge is 'N' or 'S' ? Math.Abs(sa) : Math.Abs(ca);
        ds.FillRectangle(face, C(0x503214, 0.1 * (1 - lit)));
        var row = 0;
        for (var yy = 0.22; yy < H - 0.4; yy += 0.3, row++)
            for (var xx = 0.15 + row % 2 * 0.15; xx < L; xx += 0.3)
                ds.FillEllipse(Vec(xx, yy), 0.022f, 0.022f, T.Dots);
        ds.FillRectangle(new Rect(0, H - 0.22, L, 0.22), T.Skirt);
        ds.FillRectangle(new Rect(0, H - 0.24, L, 0.04), T.SkirtLine);
        foreach (var it in walls)
        {
            if (it.Edge != edge) continue;
            var uu = flip ? L - it.U : it.U;
            if (it.Door) { DrawDoor(ds, wall, uu, H); continue; }
            if (it.Id is not { } id || art($"cafe/{id}-1.webp") is not { } pc) continue;
            ds.DrawImage(pc.Bitmap, new Rect(uu - it.W / 2, it.V, it.W, it.W * pc.Aspect), pc.Source, 1f, CanvasImageInterpolation.HighQualityCubic);
        }
        ds.Transform = Matrix3x2.Identity;
    }

    /// <summary>A rectangle with its top corners rounded by one radius and its bottom corners by another.</summary>
    static CanvasGeometry RoundRect(ICanvasResourceCreator rc, double x, double y, double w, double h, double top, double bottom)
    {
        top = Math.Min(top, w / 2); bottom = Math.Min(bottom, w / 2);
        if (top + bottom > h) { var f = h / (top + bottom); top *= f; bottom *= f; }
        const double k = 0.4477;   // 1 less 0.5523: where a cubic's handles sit for a quarter circle
        using var b = new CanvasPathBuilder(rc);
        b.BeginFigure(Vec(x + top, y));
        b.AddLine(Vec(x + w - top, y));
        b.AddCubicBezier(Vec(x + w - top * k, y), Vec(x + w, y + top * k), Vec(x + w, y + top));
        b.AddLine(Vec(x + w, y + h - bottom));
        b.AddCubicBezier(Vec(x + w, y + h - bottom * k), Vec(x + w - bottom * k, y + h), Vec(x + w - bottom, y + h));
        b.AddLine(Vec(x + bottom, y + h));
        b.AddCubicBezier(Vec(x + bottom * k, y + h), Vec(x, y + h - bottom * k), Vec(x, y + h - bottom));
        b.AddLine(Vec(x, y + top));
        b.AddCubicBezier(Vec(x, y + top * k), Vec(x + top * k, y), Vec(x + top, y));
        b.EndFigure(CanvasFigureLoop.Closed);
        return CanvasGeometry.CreatePath(b);
    }

    static void Fill(CanvasDrawingSession ds, CanvasGeometry g, Color c)
    {
        using (g) ds.FillGeometry(g, c);
    }

    readonly CanvasTextFormat openFormat = new()
    {
        FontFamily = "Segoe UI", FontSize = 17, FontWeight = FontWeights.ExtraBold,
        HorizontalAlignment = CanvasHorizontalAlignment.Center, VerticalAlignment = CanvasVerticalAlignment.Center,
        WordWrapping = CanvasWordWrapping.NoWrap,
    };

    /// <summary>The door, on the wall's own plane (<c>wall</c>): its frame, the glass, the knob and the OPEN sign.</summary>
    void DrawDoor(CanvasDrawingSession ds, Matrix3x2 wall, double u, double H)
    {
        const double w = 0.95, h = 1.75;
        double x = u - w / 2, y = H - h - 0.04;
        Fill(ds, RoundRect(ds, x - 0.06, y - 0.06, w + 0.12, h + 0.08, 0.5, 0), C(0xB98252));
        Fill(ds, RoundRect(ds, x, y, w, h, 0.45, 0), C(0xD9A46C));
        Fill(ds, RoundRect(ds, x + 0.18, y + 0.18, w - 0.36, 0.55, 0.3, 0.05), C(0xA7D7EE));
        ds.FillEllipse(Vec(x + w - 0.16, y + h * 0.58), 0.045f, 0.045f, C(0x7A4C2A));
        using (var sign = RoundRect(ds, x + 0.16, y + 0.86, w - 0.32, 0.26, 0.05, 0.05))
        {
            ds.FillGeometry(sign, C(0xFFF8EC));
            ds.DrawGeometry(sign, C(0x7A4C2A), 0.025f);
        }
        // the word at 17 points, scaled down to the wall's units and centered on the sign
        ds.Transform = Matrix3x2.CreateScale(0.01f) * Matrix3x2.CreateTranslation((float)u, (float)(y + 0.99)) * wall;
        ds.DrawText("OPEN", new Rect(-100, -20, 200, 40), C(0x2C8A7E), openFormat);
        ds.Transform = wall;
    }

    (Color? Tint, Color? Extra, Color? Glow, double R) Lighting()
    {
        if (!TINTS.TryGetValue(light, out var lt)) lt = (null, null, 0);
        Color? tint = lt.Tint ?? (dim ? C(0x0A0C1E, 0.28) : (Color?)null);
        Color? extra = dim && lt.Tint != null ? C(0x0A0C1E, 0.14) : (Color?)null;
        return (tint, extra, lt.Glow, lt.R);
    }

    public bool HasLight
    {
        get
        {
            var l = Lighting();
            return l.Tint != null || l.Extra != null || l.Glow != null;
        }
    }

    /// <summary>The light alone: the tint over the whole canvas and the glows near the things that give light (Render puts it on the room).</summary>
    void DrawLight(CanvasDrawingSession ds)
    {
        var (tint, extra, glow, reach) = Lighting();
        var face = new Rect(0, 0, width, height);
        if (tint is { } t) ds.FillRectangle(face, t);
        if (extra is { } x) ds.FillRectangle(face, x);
        if (glow is not { } g) return;
        foreach (var th in things)
        {
            if (th.Light is not { } lv) continue;
            var p = Pt(th.X, th.Y, HeightZ(th) * lv);
            var r = (float)(reach * S * (th.Id == "lamp" ? 1 : 0.55));
            using var brush = new CanvasRadialGradientBrush(ds, g, C(0xFFBE69, 0)) { Center = p, RadiusX = r, RadiusY = r };
            ds.FillRectangle(new Rect(p.X - r, p.Y - r, r * 2, r * 2), brush);
        }
    }

    /// <summary>The cat or thing under a point, for a click.</summary>
    public (string Title, string Sub)? Hit(Point p)
    {
        foreach (var s in things.Concat(cats).OrderByDescending(x => x.Depth))
        {
            if (s.Box is not { } r || p.X < r.X || p.X >= r.X + r.W || p.Y < r.Y || p.Y >= r.Y + r.H) continue;
            return s.IsCat ? (s.Title, s.Sub) : (s.Id.Length > 0 ? char.ToUpperInvariant(s.Id[0]) + s.Id[1..] : "", "");
        }
        return null;
    }
}

// ---------------------------------------------------------------- the view

/// <summary>
/// The café, in a box: drag to turn it, click a cat. The room is drawn about 30 times a second while it is on
/// screen (the cats breathe), and the page behind gives it its card.
/// </summary>
sealed class CafeView
{
    /// <summary>The self-test: no animation timer, a drawing only when something changes, and no breathing.</summary>
    public static bool Still;

    public FrameworkElement View => root;
    public int CatCount => scene.CatCount;

    readonly Grid root = new();
    readonly CanvasControl canvas = new();
    readonly Border tip;
    readonly CafePaintings paintings;
    readonly CafeScene scene;
    readonly System.Diagnostics.Stopwatch clock = System.Diagnostics.Stopwatch.StartNew();
    DispatcherQueueTimer? timer, tipTimer;
    CanvasDevice? device;
    double last;
    double? dragStart;
    Point down;
    bool prefetch, failed;

    public CafeView()
    {
        paintings = new CafePaintings(canvas);
        scene = new CafeScene(paintings.Get);
        canvas.ClearColor = Palette.C(0x000000, 0);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(canvas, "Your Cat Café. Drag to turn it, click a cat.");
        tip = new Border
        {
            HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
            CornerRadius = new CornerRadius(8), BorderThickness = new Thickness(1), Padding = new Thickness(12, 7, 12, 7),
            IsHitTestVisible = false, Visibility = Visibility.Collapsed,
        };
        root.Children.Add(canvas);
        root.Children.Add(tip);

        canvas.CreateResources += (sender, _) =>
        {
            // bitmaps belong to a device: a new one (the old lost) loads them again
            if (ReferenceEquals(device, sender.Device)) return;
            device = sender.Device;
            paintings.Reset();
            prefetch = true;
        };
        canvas.Draw += OnDraw;
        canvas.Loaded += (_, _) =>
        {
            last = clock.Elapsed.TotalSeconds;
            if (Still || timer != null) return;
            timer = Ui.Every(TimeSpan.FromMilliseconds(33), () => { if (scene.Moving || scene.CatCount > 0) canvas.Invalidate(); });
        };
        canvas.Unloaded += (_, _) =>
        {
            timer?.Stop();
            timer = null;
            HideTip();
        };

        // drag to turn it (past 6 px), let go and it settles on a quarter turn; a click without a drag is a tip
        canvas.PointerPressed += (_, e) =>
        {
            var pt = e.GetCurrentPoint(canvas);
            if (pt.Properties.IsRightButtonPressed) return;
            down = pt.Position;
            dragStart = scene.Angle;
            canvas.CapturePointer(e.Pointer);
            e.Handled = true;
        };
        canvas.PointerMoved += (_, e) =>
        {
            if (dragStart is not { } start) return;
            var dx = e.GetCurrentPoint(canvas).Position.X - down.X;
            if (Math.Abs(dx) <= 6) return;
            scene.Dragging = true;
            scene.Angle = start + dx * 0.012;
            scene.Goal = scene.Angle;
            canvas.Invalidate();
        };
        canvas.PointerReleased += (_, e) =>
        {
            if (dragStart == null) return;
            var at = e.GetCurrentPoint(canvas).Position;
            End(at);
            canvas.ReleasePointerCapture(e.Pointer);
        };
        canvas.PointerCaptureLost += (_, _) => End(null);
        canvas.PointerCanceled += (_, _) => End(null);
    }

    /// <summary>What to draw. The same data again (the clock ticking on) only changes the light and the theme.</summary>
    public void Show(CafeData d)
    {
        scene.Show(d);
        prefetch = true;
        canvas.Invalidate();
    }

    /// <summary>Turns the room a quarter turn at a time (buttons elsewhere): -1 one way, 1 the other.</summary>
    public void Spin(int by)
    {
        scene.Spin(by);
        if (Still) scene.Settle();
        canvas.Invalidate();
    }

    void End(Point? at)
    {
        if (dragStart == null) return;
        dragStart = null;
        if (scene.Dragging)
        {
            scene.Dragging = false;
            scene.Snap();
            if (Still) scene.Settle();
        }
        else if (at is { } p)
        {
            if (scene.Hit(p) is { } h && h.Title.Length > 0) ShowTip(h.Title, h.Sub, p);
            else HideTip();
        }
        canvas.Invalidate();
    }

    void OnDraw(CanvasControl sender, CanvasDrawEventArgs args)
    {
        var now = clock.Elapsed.TotalSeconds;
        var dt = Still ? 0 : now - last;
        last = now;
        scene.Step(dt, sender.ActualWidth, sender.ActualHeight);
        if (!scene.Ready) return;
        if (prefetch)
        {
            prefetch = false;
            foreach (var path in scene.Paths()) paintings.Get(path);
        }
        try { scene.Render(args.DrawingSession); }
        catch (Exception e) when (!sender.Device.IsDeviceLost(e.HResult))
        {
            if (!failed) Log.Write($"café: {e}");
            failed = true;
        }
    }

    /// <summary>A cat's name and its block, above where it was clicked, for three seconds.</summary>
    void ShowTip(string title, string sub, Point at)
    {
        tipTimer?.Stop();
        tip.Child = V.Col(1, V.T(title, 13, FontWeights.Bold, Palette.Text, align: TextAlignment.Center),
            sub.Length > 0 ? V.T(sub, 12, color: Palette.Text2, align: TextAlignment.Center) : null);
        tip.Background = Palette.B(Palette.Surface);
        tip.BorderBrush = Palette.B(Palette.Stroke);
        tip.Margin = new Thickness(0);
        tip.Visibility = Visibility.Visible;
        tip.Measure(new Size(double.PositiveInfinity, double.PositiveInfinity));
        var size = tip.DesiredSize;
        var x = Math.Min(Math.Max(at.X, 80), root.ActualWidth - 80);
        var y = Math.Max(24, at.Y - 26);
        tip.Margin = new Thickness(x - size.Width / 2, y - size.Height / 2, 0, 0);
        tipTimer = Ui.After(TimeSpan.FromSeconds(3), HideTip);
    }

    void HideTip()
    {
        tipTimer?.Stop();
        tipTimer = null;
        tip.Visibility = Visibility.Collapsed;
    }
}
