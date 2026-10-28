using Chataltul.Core;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Shapes;
using Windows.Foundation;

namespace Chataltul;

/// <summary>
/// The round window with the cat in it and a ring around it (the Mac's CatWindow): a sky that goes from
/// afternoon to dusk as the block goes on (night in dark mode), the window's bars, the cat growing from napping
/// to kitten to grown, and the ring that empties with the time.
/// </summary>
sealed class RoundWindow
{
    public Grid View { get; }
    readonly double size, inner;
    readonly Microsoft.UI.Xaml.Shapes.Path ring;
    readonly Ellipse dusk;
    readonly ImageBrush cat;
    readonly ScaleTransform grow;
    string picture = "";

    public RoundWindow(double size, bool deepSky = false)
    {
        this.size = size;
        inner = size * 0.865;
        View = new Grid { Width = size, Height = size, HorizontalAlignment = HorizontalAlignment.Center };
        View.Children.Add(V.Ring(size, 4, Palette.RingTrack));
        ring = V.Arc(size, 4, 1, Palette.Accent);
        ring.Visibility = Visibility.Collapsed;
        View.Children.Add(ring);

        var window = new Grid { Width = inner, Height = inner, HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center };
        window.Children.Add(new Ellipse { Fill = V.Vertical(Palette.Dark ? (deepSky ? Palette.SkyDeep : Palette.SkyNight) : Palette.SkyDay) });
        dusk = new Ellipse { Fill = V.Vertical(Palette.SkyDusk), Opacity = 0 };
        if (!Palette.Dark) window.Children.Add(dusk);
        else
            foreach (var (x, y, r, a) in new[] { (0.30, 0.22, 1.0, 0.9), (0.70, 0.30, 1.0, 0.7), (0.55, 0.12, 1.0, 0.8), (0.82, 0.52, 1.0, 0.6), (0.18, 0.48, 1.0, 0.6), (0.40, 0.40, 0.8, 0.5) })
                window.Children.Add(new Ellipse
                {
                    Width = r * 2, Height = r * 2, Fill = Palette.B(Palette.C(0xFFFFFF, a)),
                    HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
                    Margin = new Thickness(x * inner - r, y * inner - r, 0, 0),
                });
        // the window's bars: down from the top to a third, and across at a third, inside the circle
        var bar = Palette.B(Palette.Rim);
        window.Children.Add(new Rectangle
        {
            Width = 3, Height = inner / 3, Fill = bar, Opacity = 0.75,
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Top,
        });
        var half = inner / 2;
        var dy = half - inner / 3;
        var chord = 2 * Math.Sqrt(Math.Max(0, half * half - dy * dy));
        window.Children.Add(new Rectangle
        {
            Width = chord, Height = 3, Fill = bar, Opacity = 0.75,
            HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Top, Margin = new Thickness(0, inner / 3 - 1.5, 0, 0),
        });
        // the cat, standing at the bottom of the window and cut to its circle
        grow = new ScaleTransform { CenterX = 0.5, CenterY = 0.97, ScaleX = 0.62, ScaleY = 0.62 };
        cat = new ImageBrush { Stretch = Stretch.Uniform, AlignmentY = AlignmentY.Bottom, RelativeTransform = grow };
        window.Children.Add(new Ellipse { Fill = cat });
        window.Children.Add(new Ellipse { Stroke = Palette.B(Palette.Rim), StrokeThickness = 6 });
        View.Children.Add(window);
    }

    /// <summary>
    /// <c>remaining</c>: the share of the block left, 1 down to 0, for the ring (null: no ring). <c>growth</c>: how
    /// big the cat is (Art.Size). <c>sky</c>: how far into the block, for the dusk.
    /// </summary>
    public void Update(string catPicture, double growth, double? remaining, double sky = 0, bool paused = false)
    {
        if (catPicture != picture)
        {
            picture = catPicture;
            cat.ImageSource = V.Picture(catPicture);
        }
        grow.ScaleX = grow.ScaleY = 0.78 * growth;
        dusk.Opacity = Math.Max(0, sky - 0.4) / 0.6 * 0.8;
        if (remaining is { } r)
        {
            ring.Visibility = Visibility.Visible;
            ring.Stroke = Palette.B(paused ? Palette.Caution : Palette.Accent);
            V.SetArc(ring, size, 4, r);
        }
        else ring.Visibility = Visibility.Collapsed;
    }

    /// <summary>A block's cat at this moment.</summary>
    public void Update(Block b, long now)
    {
        var at = b.Paused ?? now;
        var total = Math.Max(1, b.End - b.Start);
        var p = Math.Clamp((double)(at - b.Start) / total, 0, 1);
        var (stage, prog) = Art.StageOf(b, at);
        Update(Art.PathFor(b.Cat, stage, Art.SmallBlock(b, now)), Art.Size(stage, prog), b.Stopwatch ? null : 1 - p, p, b.Paused != null);
    }
}
