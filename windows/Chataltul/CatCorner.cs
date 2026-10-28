using Chataltul.Core;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Windows.Foundation;

namespace Chataltul;

sealed record CatButton(string Label, Action Action, bool Primary = false, string? Glyph = null);

/// <summary>What the cat in the corner says: a check-in, a nudge, the end of a block, the end of a break.</summary>
sealed record CatCardModel(string Cat, string Title)
{
    public string? Label { get; init; }
    /// <summary>A picture to show instead of the cat's portrait (the paw prints of a cat that left).</summary>
    public string? Picture { get; init; }
    public string? Body { get; init; }
    public IReadOnlyList<CatButton> Buttons { get; init; } = [];
    public string? Hint { get; init; }
    /// <summary>Many small buttons in rows (the reasons for a drift), instead of a row of big ones.</summary>
    public bool Compact { get; init; }
}

/// <summary>
/// The cat peeking up from the corner of the screen with a card beside it. It never takes the front from the
/// app being worked in, and a click anywhere but the card and the cat goes through to that app. A new card
/// takes the place of the old. The Mac's CatController and CatCardView.
/// </summary>
sealed class CatCorner
{
    public Floating Win { get; } = new("Chataltul cat", activates: false);
    DispatcherQueueTimer? timer;
    Action? onTimeout;
    Border? card;
    FrameworkElement? catView;

    public string? Kind { get; private set; }
    public CatCardModel? Model { get; private set; }
    public bool IsShown => Win.IsShown;
    /// <summary>The self-test leaves out the slide and the breathing.</summary>
    public static bool Still;

    /// <summary>Puts a cat up. <c>seconds</c>: it goes by itself after this long, and <c>timeout</c> runs then.</summary>
    public void Show(string kind, CatCardModel model, double seconds, Action? timeout = null)
    {
        Hide();
        Kind = kind;
        Model = model;
        onTimeout = timeout;
        Palette.Dark = Palette.SystemDark();
        Win.Root.RequestedTheme = Palette.Theme;
        Win.Content = Build(model);
        Place();
        Win.Show();
        Win.Refit(() => { if (Kind == kind) Place(); });
        timer = Ui.After(TimeSpan.FromSeconds(seconds), () =>
        {
            if (Kind != kind) return;
            var t = onTimeout;
            Hide();
            t?.Invoke();
        });
    }

    public void Hide(string? only = null)
    {
        if (only != null && Kind != only) return;
        timer?.Stop();
        timer = null;
        onTimeout = null;
        Kind = null;
        Model = null;
        Win.Hide();
    }

    /// <summary>Presses the button with these words, as a click would (the self-test).</summary>
    public bool Press(string label)
    {
        var b = Model?.Buttons.FirstOrDefault(x => x.Label == label);
        b?.Action();
        return b != null;
    }

    FrameworkElement Build(CatCardModel m)
    {
        // a card like one of Windows 11's notifications: the app's name, the words, Windows' buttons
        var words = V.Col(10);
        words.Children.Add(V.Row(6, V.AppIcon(16), V.Caption(m.Label != null ? $"Chataltul · {m.Label}" : "Chataltul")));
        words.Children.Add(V.T(m.Title, 16, FontWeights.SemiBold, Palette.Text, wrap: true));
        if (!string.IsNullOrEmpty(m.Body)) words.Children.Add(V.Body(m.Body, wrap: true, color: Palette.Text2));
        if (m.Buttons.Count > 0) words.Children.Add(m.Compact ? Rows(m.Buttons) : V.Even(8, m.Buttons.Select(Big).ToArray()));
        if (m.Hint != null) words.Children.Add(V.Caption(m.Hint, wrap: true));
        var close = Quiet(V.IconBtn(V.Glyph.Close, () => Hide(), "Close", 28));
        close.HorizontalAlignment = HorizontalAlignment.Right;
        close.VerticalAlignment = VerticalAlignment.Top;
        close.Margin = new Thickness(0, -8, -8, 0);
        var inside = new Grid();
        inside.Children.Add(words);
        inside.Children.Add(close);
        card = V.Surface(inside);
        card.Width = 330;
        card.HorizontalAlignment = HorizontalAlignment.Right;
        card.VerticalAlignment = VerticalAlignment.Bottom;
        card.Margin = new Thickness(0, 0, 6, 84);

        // the cat: the top of its painting, peeking over the edge of the screen
        var picture = V.Painting(m.Picture ?? Art.Portrait(m.Cat), 150);
        picture.VerticalAlignment = VerticalAlignment.Top;
        picture.RenderTransformOrigin = new Point(0.5, 1);
        var breath = new ScaleTransform();
        picture.RenderTransform = breath;
        var peek = new Grid { Width = 150, Height = 104, HorizontalAlignment = HorizontalAlignment.Right, VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 34, 0) };
        peek.Clip = new RectangleGeometry { Rect = new Rect(0, 0, 150, 104) };
        peek.Children.Add(picture);
        catView = peek;

        var all = new Grid { Width = 400, HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top };
        all.Children.Add(card);
        all.Children.Add(peek);
        if (!Still)
        {
            Breathe(breath);
            SlideUp(all);
        }
        card.SizeChanged += (_, _) => Shape();
        return all;
    }

    Button Big(CatButton b)
    {
        object content = b.Glyph != null ? V.Labelled(b.Glyph, b.Label) : b.Label;
        return Quiet(V.Btn(content, b.Action, accent: b.Primary, stretch: true, name: b.Label, height: 34));
    }

    /// <summary>A press on the cat's card doesn't take the keyboard from the app being worked in.</summary>
    static Button Quiet(Button b)
    {
        b.AllowFocusOnInteraction = false;
        b.IsTabStop = false;
        return b;
    }

    /// <summary>Three to a row.</summary>
    UIElement Rows(IReadOnlyList<CatButton> buttons)
    {
        var col = V.Col(6);
        for (var i = 0; i < buttons.Count; i += 3)
        {
            var row = V.Row(6);
            foreach (var b in buttons.Skip(i).Take(3))
                row.Children.Add(Quiet(V.Btn(b.Label, b.Action, name: b.Label)));
            col.Children.Add(row);
        }
        return col;
    }

    /// <summary>In the bottom right corner of the screen the pointer is on, just above the taskbar.</summary>
    void Place()
    {
        var at = Native.Cursor();
        var work = Native.WorkAreaAt(at);
        var scale = Floating.ScaleAt(at);
        var size = Win.Measure(400);
        int w = (int)Math.Ceiling(size.Width * scale), h = (int)Math.Ceiling(size.Height * scale);
        Win.Move(work.Right - w - (int)(12 * scale), work.Bottom - h, w, h);
        Shape();
    }

    /// <summary>Only the card and the cat take clicks.</summary>
    void Shape()
    {
        if (card == null || catView == null || card.ActualWidth <= 0) return;
        var scale = Native.GetDpiForWindow(Win.Hwnd) / 96.0;
        Win.SetShapes([(Layout(card), 8), (Layout(catView), 0)], scale);
    }

    /// <summary>Where an element sits in the window by layout alone (the slide doesn't move its shape).</summary>
    static Rect Layout(FrameworkElement e)
    {
        var o = e.ActualOffset;
        return new Rect(o.X, o.Y, e.ActualWidth, e.ActualHeight);
    }

    /// <summary>A gentle breathing: the painting rises and settles about once every four seconds.</summary>
    static void Breathe(ScaleTransform t)
    {
        var sb = new Storyboard { RepeatBehavior = RepeatBehavior.Forever };
        DoubleAnimation Part(string property, double to)
        {
            var a = new DoubleAnimation { From = 1, To = to, Duration = new Duration(TimeSpan.FromSeconds(1.96)), AutoReverse = true, EasingFunction = new SineEase { EasingMode = EasingMode.EaseInOut } };
            Storyboard.SetTarget(a, t);
            Storyboard.SetTargetProperty(a, property);
            return a;
        }
        sb.Children.Add(Part("ScaleY", 1.014));
        sb.Children.Add(Part("ScaleX", 0.994));
        sb.Begin();
    }

    static void SlideUp(FrameworkElement e)
    {
        var move = new TranslateTransform { Y = 190 };
        e.RenderTransform = move;
        e.Opacity = 0;
        var sb = new Storyboard();
        var a = new DoubleAnimation { From = 190, To = 0, Duration = new Duration(TimeSpan.FromMilliseconds(480)), EasingFunction = new BackEase { EasingMode = EasingMode.EaseOut, Amplitude = 0.3 } };
        Storyboard.SetTarget(a, move);
        Storyboard.SetTargetProperty(a, "Y");
        var f = new DoubleAnimation { From = 0, To = 1, Duration = new Duration(TimeSpan.FromMilliseconds(250)) };
        Storyboard.SetTarget(f, e);
        Storyboard.SetTargetProperty(f, "Opacity");
        sb.Children.Add(a);
        sb.Children.Add(f);
        sb.Begin();
    }
}
