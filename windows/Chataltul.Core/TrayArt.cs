namespace Chataltul.Core;

/// <summary>
/// What the tray icon shows: the kitten, and a ring that empties while a block runs. A state for ready,
/// focusing (with an orange dot while a check-in waits), paused, on a break and came home. The Mac's menu bar
/// item (StatusController.swift) has the time beside the icon; the notification area has no room for that, so
/// the time is in the icon's tip.
/// </summary>
public abstract record TrayState
{
    public sealed record Ready : TrayState;
    public sealed record Focusing(double Remaining, string Text, bool Pending) : TrayState;
    public sealed record Paused(double Remaining, string Text) : TrayState;
    public sealed record OnBreak(double Remaining, string Text) : TrayState;
    public sealed record Home : TrayState;

    public static TrayState Of(Block? active, Block? awaiting, Brk? onBreak, long now)
    {
        if (awaiting != null) return new Home();
        if (active is { } b)
        {
            var at = b.Paused ?? now;
            var total = Math.Max(1, b.End - b.Start);
            var remaining = b.Stopwatch ? 1.0 : 1 - Math.Clamp((double)(at - b.Start) / total, 0, 1);
            var text = Format.FmtClock(b.Stopwatch ? at - b.Start : Math.Max(0, b.End - at));
            if (b.Paused != null) return new Paused(remaining, text);
            return new Focusing(remaining, text, b.Pending != null);
        }
        if (onBreak is { } k)
        {
            var total = Math.Max(1, k.End - k.Start);
            return new OnBreak((double)Math.Max(0, k.End - now) / total, Format.FmtClock(Math.Max(0, k.End - now)));
        }
        return new Ready();
    }

    /// <summary>
    /// What the picture depends on, so it is only drawn again when something it shows has moved: the ring in
    /// steps of a 64th, which is finer than a 32-pixel icon can show.
    /// </summary>
    public string Key => this switch
    {
        Focusing f => $"f{Step(f.Remaining)}{(f.Pending ? "p" : "")}",
        Paused p => $"p{Step(p.Remaining)}",
        OnBreak k => $"b{Step(k.Remaining)}",
        Home => "h",
        _ => "r",
    };

    static int Step(double r) => (int)Math.Ceiling(Math.Clamp(r, 0, 1) * 64);

    /// <summary>The icon's tip: "Chataltul · 24:59 left · Write the intro".</summary>
    public string Tip(string? title, string? catName) => this switch
    {
        Focusing f => Join("Chataltul", $"{f.Text}{(title == null ? "" : " · " + title)}", f.Pending ? "a check-in is waiting" : null),
        Paused p => Join("Chataltul", $"paused · {p.Text}", title),
        OnBreak k => Join("Chataltul", $"break · {k.Text}"),
        Home => Join("Chataltul", catName == null ? "came home" : $"{catName} came home"),
        _ => "Chataltul",
    };

    static string Join(params string?[] parts) => string.Join(" · ", parts.Where(p => !string.IsNullOrEmpty(p)));
}

/// <summary>Draws the tray icon at any size, with no drawing library: the Mac's 18-point shapes, sampled 16 times a pixel.</summary>
public static class TrayArt
{
    public sealed record Colors(uint Ring, uint Orange, uint Blue, uint Ink, uint Heart);

    /// <summary>The colors that read on a dark taskbar, and on a light one.</summary>
    public static Colors For(bool darkTaskbar) => darkTaskbar
        ? new(0x5FD0C0, 0xFFCF8A, 0x8CC4F0, 0xFFFFFF, 0x221D27)
        : new(0x2C8A7E, 0xE98A3C, 0x3D7BE0, 0x1C1C1C, 0xFFFFFF);

    /// <summary>The icon, <c>size</c> pixels square: BGRA with straight alpha, rows from the top.</summary>
    public static byte[] Draw(TrayState state, int size, bool darkTaskbar)
    {
        var c = For(darkTaskbar);
        var k = new Canvas(size);
        switch (state)
        {
            case TrayState.Focusing f:
                k.Stroke(Circle(), 1.6, c.Ring, 0.25);
                k.Stroke(Arc(f.Remaining), 1.6, c.Ring);
                k.Fill(Head(filled: true), c.Ring);
                if (f.Pending) k.Disc(15.3, 2.9, 2.7, c.Orange);
                break;
            case TrayState.Paused p:
                k.Stroke(Arc(p.Remaining), 1.6, c.Orange);
                k.Stroke([(7.2, 6.2), (7.2, 11.8)], 1.7, c.Orange);
                k.Stroke([(10.8, 6.2), (10.8, 11.8)], 1.7, c.Orange);
                break;
            case TrayState.OnBreak b:
                k.Stroke(Circle(), 1.6, c.Blue, 0.25);
                k.Stroke(Arc(b.Remaining), 1.6, c.Blue);
                k.Fill(Head(filled: true), c.Blue);
                break;
            case TrayState.Home:
                k.Fill(Head(filled: true), c.Ring);
                k.Fill(Heart(), c.Heart);
                break;
            default:
                k.Stroke(Head(filled: false), 1.3, c.Ink, closed: true);
                break;
        }
        return k.Bgra();
    }

    // ---- the shapes, in the Mac's 18 x 18 box (y down)

    /// <summary>The kitten's head from the mockups: filled, or the bigger outline for ready.</summary>
    static List<(double X, double Y)> Head(bool filled)
    {
        var p = new Outline();
        if (filled)
        {
            p.Move(5.6, 8.2); p.Line(5.6, 5.4); p.Line(7.4, 6.9); p.Line(10.6, 6.9); p.Line(12.4, 5.4); p.Line(12.4, 8.2);
            p.Curve(12.4, 10.6, 11, 12.4, 9, 12.4);
            p.Curve(7, 12.4, 5.6, 10.6, 5.6, 8.2);
        }
        else
        {
            p.Move(4.6, 8.4); p.Line(4.6, 4.6); p.Line(7.1, 6.6); p.Line(10.9, 6.6); p.Line(13.4, 4.6); p.Line(13.4, 8.4);
            p.Curve(13.4, 11.4, 11.6, 13.6, 9, 13.6);
            p.Curve(6.4, 13.6, 4.6, 11.4, 4.6, 8.4);
        }
        return p.Points;
    }

    static List<(double X, double Y)> Heart()
    {
        var p = new Outline();
        p.Move(9, 12.2);
        p.Curve(7.4, 11.2, 6.6, 10.4, 6.6, 9.5);
        p.Curve(6.6, 8.2, 8.5, 8.2, 9, 9.1);
        p.Curve(9.5, 8.2, 11.4, 8.2, 11.4, 9.5);
        p.Curve(11.4, 10.4, 10.6, 11.2, 9, 12.2);
        return p.Points;
    }

    const double R = 7.6, CX = 9, CY = 9;

    static List<(double X, double Y)> Circle() => Arc(1.0001);

    /// <summary>Clockwise from the top, as much of the ring as is left.</summary>
    static List<(double X, double Y)> Arc(double remaining)
    {
        var sweep = 2 * Math.PI * Math.Clamp(remaining, 0.001, 1.0001);
        var n = Math.Max(2, (int)Math.Ceiling(sweep / (Math.PI / 60)));
        var pts = new List<(double, double)>(n + 1);
        for (var i = 0; i <= n; i++)
        {
            var a = -Math.PI / 2 + sweep * i / n;
            pts.Add((CX + R * Math.Cos(a), CY + R * Math.Sin(a)));
        }
        return pts;
    }

    /// <summary>A path of lines and curves, flattened into points.</summary>
    sealed class Outline
    {
        public readonly List<(double X, double Y)> Points = [];
        public void Move(double x, double y) => Points.Add((x, y));
        public void Line(double x, double y) => Points.Add((x, y));
        public void Curve(double x1, double y1, double x2, double y2, double x, double y)
        {
            var (x0, y0) = Points[^1];
            for (var i = 1; i <= 16; i++)
            {
                var t = i / 16.0;
                var u = 1 - t;
                Points.Add((u * u * u * x0 + 3 * u * u * t * x1 + 3 * u * t * t * x2 + t * t * t * x,
                            u * u * u * y0 + 3 * u * u * t * y1 + 3 * u * t * t * y2 + t * t * t * y));
            }
        }
    }

    /// <summary>Coverage sampled on a 4 x 4 grid in each pixel, painted over in order.</summary>
    sealed class Canvas(int size)
    {
        const int S = 4;
        readonly double scale = size / 18.0;
        readonly double[] r = new double[size * size], g = new double[size * size], b = new double[size * size], a = new double[size * size];

        void Paint(Func<double, double, bool> inside, uint rgb, double alpha)
        {
            double cr = (rgb >> 16 & 0xFF) / 255.0, cg = (rgb >> 8 & 0xFF) / 255.0, cb = (rgb & 0xFF) / 255.0;
            for (var y = 0; y < size; y++)
                for (var x = 0; x < size; x++)
                {
                    var hits = 0;
                    for (var sy = 0; sy < S; sy++)
                        for (var sx = 0; sx < S; sx++)
                            if (inside((x + (sx + 0.5) / S) / scale, (y + (sy + 0.5) / S) / scale)) hits++;
                    if (hits == 0) continue;
                    var cov = alpha * hits / (S * S);
                    var i = y * size + x;
                    // premultiplied "over"
                    r[i] = cr * cov + r[i] * (1 - cov);
                    g[i] = cg * cov + g[i] * (1 - cov);
                    b[i] = cb * cov + b[i] * (1 - cov);
                    a[i] = cov + a[i] * (1 - cov);
                }
        }

        public void Fill(List<(double X, double Y)> poly, uint rgb, double alpha = 1) => Paint((x, y) => InPolygon(poly, x, y), rgb, alpha);

        public void Stroke(List<(double X, double Y)> line, double width, uint rgb, double alpha = 1, bool closed = false) =>
            Paint((x, y) => NearLine(line, x, y, width / 2, closed), rgb, alpha);

        public void Disc(double cx, double cy, double radius, uint rgb) =>
            Paint((x, y) => (x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius, rgb, 1);

        public byte[] Bgra()
        {
            var o = new byte[size * size * 4];
            for (var i = 0; i < size * size; i++)
            {
                var al = a[i];
                if (al <= 0) continue;
                o[i * 4] = Byte(b[i] / al);
                o[i * 4 + 1] = Byte(g[i] / al);
                o[i * 4 + 2] = Byte(r[i] / al);
                o[i * 4 + 3] = Byte(al);
            }
            return o;
        }

        static byte Byte(double v) => (byte)Math.Round(Math.Clamp(v, 0, 1) * 255);
    }

    static bool InPolygon(List<(double X, double Y)> p, double x, double y)
    {
        var inside = false;
        for (int i = 0, j = p.Count - 1; i < p.Count; j = i++)
        {
            var (xi, yi) = p[i];
            var (xj, yj) = p[j];
            if (yi > y != yj > y && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
        }
        return inside;
    }

    static bool NearLine(List<(double X, double Y)> p, double x, double y, double half, bool closed)
    {
        var h2 = half * half;
        var n = closed ? p.Count : p.Count - 1;
        for (var i = 0; i < n; i++)
        {
            var (ax, ay) = p[i];
            var (bx, by) = p[(i + 1) % p.Count];
            double dx = bx - ax, dy = by - ay;
            var len2 = dx * dx + dy * dy;
            var t = len2 == 0 ? 0 : Math.Clamp(((x - ax) * dx + (y - ay) * dy) / len2, 0, 1);
            double ex = ax + t * dx - x, ey = ay + t * dy - y;
            if (ex * ex + ey * ey <= h2) return true;
        }
        return false;
    }
}
