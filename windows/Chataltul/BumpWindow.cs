using Chataltul.Core;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Shapes;

namespace Chataltul;

/// <summary>
/// "Leave anyway": counts only when held for two seconds. It looks like Windows' own button and fills as it is
/// held; a plain press only says "Hold to leave". The rules are HoldTracker's; this is the button.
/// </summary>
sealed class HoldButton
{
    public Grid View { get; }
    readonly Rectangle fill;
    readonly TextBlock text;
    readonly HoldTracker tracker = new();
    DispatcherQueueTimer? timer;
    public Action OnHeld { get; set; } = () => { };

    public HoldButton()
    {
        fill = new Rectangle { Fill = Palette.B(Palette.Caution), Opacity = 0.3, HorizontalAlignment = HorizontalAlignment.Left, Width = 0 };
        text = V.T("Leave anyway", 14, color: Palette.Text, align: TextAlignment.Center);
        text.HorizontalAlignment = HorizontalAlignment.Center;
        text.VerticalAlignment = VerticalAlignment.Center;
        View = new Grid
        {
            Height = 36, CornerRadius = new CornerRadius(4), Background = Palette.B(Palette.Layer),
            BorderBrush = Palette.B(Palette.Stroke), BorderThickness = new Thickness(1),
        };
        View.Children.Add(fill);
        View.Children.Add(text);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(View, "Leave anyway. Hold for two seconds.");
        View.PointerPressed += (_, e) => { View.CapturePointer(e.Pointer); Down(Clock.Now); e.Handled = true; };
        View.PointerReleased += (_, e) => { Up(Clock.Now); View.ReleasePointerCapture(e.Pointer); };
        View.PointerCaptureLost += (_, _) => Up(Clock.Now);
        View.PointerCanceled += (_, _) => Up(Clock.Now);
    }

    public bool Holding => tracker.Holding;

    public void Down(long now)
    {
        if (timer != null) return;
        tracker.Down(now);
        timer = Ui.Every(TimeSpan.FromMilliseconds(33), () => Check(Clock.Now));
    }

    public void Up(long now)
    {
        if (!tracker.Holding && timer == null) return;
        timer?.Stop();
        timer = null;
        tracker.Up(now);
        if (tracker.Hint) text.Text = "Hold to leave";
        fill.Width = 0;
    }

    /// <summary>The fill moves on; once it's full, the kitten wanders off.</summary>
    public void Check(long now)
    {
        fill.Width = View.ActualWidth * tracker.Progress(now);
        if (!tracker.Check(now)) return;
        timer?.Stop();
        timer = null;
        fill.Width = 0;
        OnHeld();
    }

    public string Words => text.Text;
}

/// <summary>
/// The speed bump: the cat walks over the app or site on the list. The screen dims, all but the taskbar (so the
/// tray icon can still be reached), and a card like one of Windows' dialogs comes up with the cat on it. It takes
/// the keyboard too, so a key press can't reach the page underneath. No close, no pause, no snooze: Back to work,
/// or hold Leave anyway, or wait the 15 seconds out. The Mac's SpeedBumpView and BumpController.
/// </summary>
sealed class BumpWindow
{
    public Floating Win { get; } = new("Chataltul speed bump", activates: true) { KeepOnClose = true };
    readonly List<Action<long>> tickers = [];
    DispatcherQueueTimer? timer;
    public HoldButton? Hold { get; private set; }

    public BumpCard? Card { get; private set; }
    /// <summary>Wait it out, locked or used up: shown when there's no speed bump card.</summary>
    public OtherCard? Other { get; private set; }
    public long GraceMs { get; set; } = SpeedBump.DefaultGraceMs;
    public Action BackToWork { get; set; } = () => { };
    public Action BackToWorkOther { get; set; } = () => { };
    public Action LeaveHeld { get; set; } = () => { };
    public Action EditList { get; set; } = () => { };
    public bool IsShown => Win.IsShown;
    /// <summary>The words on the card now, for the self-test.</summary>
    public string Line { get; private set; } = "";

    public void Show(BumpCard c)
    {
        Card = c;
        Present();
    }

    /// <summary>Another listed thing came to the front while the card is up: the same card and countdown, a new name.</summary>
    public void Update(BumpCard c)
    {
        if (!IsShown) { Show(c); return; }
        Card = c;
        Present();
    }

    public void ShowOther(OtherCard c)
    {
        Other = c;
        Present();
    }

    /// <summary>The speed bump's card goes; the window stays if a wait or a lock is still up.</summary>
    public void HideBump()
    {
        Card = null;
        Hold = null;
        if (Other != null) Present(); else Hide();
    }

    public void HideOther()
    {
        Other = null;
        if (Card != null) Present(); else Hide();
    }

    void Present()
    {
        Palette.Dark = Palette.SystemDark();
        Win.Root.RequestedTheme = Palette.Theme;
        Win.Root.Background = Palette.B(Palette.Scrim);
        if (Card is { } c) Win.Content = Build(c);
        else if (Other is { } o) Win.Content = BuildOther(o);
        else { Hide(); return; }
        if (!Win.IsShown)
        {
            // everything but the taskbar, on the screen the pointer is on
            var work = Native.WorkAreaAt(Native.Cursor());
            Win.Move(work.Left, work.Top, work.Width, work.Height);
            Win.Show();
        }
        timer ??= Ui.Every(TimeSpan.FromMilliseconds(100), () => Tick(Clock.Now));
        Tick(Clock.Now);
    }

    public void Hide()
    {
        timer?.Stop();
        timer = null;
        Card = null;
        Other = null;
        Hold = null;
        Win.Hide();
    }

    public void Tick(long now)
    {
        foreach (var t in tickers.ToList()) t(now);
    }

    FrameworkElement Build(BumpCard c)
    {
        tickers.Clear();
        var line = V.Body("", wrap: true, color: Palette.Text2, align: TextAlignment.Center);
        line.HorizontalAlignment = HorizontalAlignment.Center;
        var count = V.ClockText("", 24);
        count.VerticalAlignment = VerticalAlignment.Center;
        var ring = V.Arc(72, 5, 1, Palette.Caution);
        var dial = new Grid { Width = 72, Height = 72, HorizontalAlignment = HorizontalAlignment.Center };
        dial.Children.Add(V.Ring(72, 5, Palette.RingTrack));
        dial.Children.Add(ring);
        dial.Children.Add(count);
        var left = V.Caption("");
        var block = V.Card(V.Spread(V.Strong(c.Title), left), new Thickness(12, 10, 12, 10));
        tickers.Add(now =>
        {
            Line = c.Line(now, GraceMs);
            line.Text = Line;
            count.Text = c.SecondsLeft(now, GraceMs).ToString();
            V.SetArc(ring, 72, 5, c.Fraction(now, GraceMs));
            left.Text = $"{Format.MinLeft(Math.Max(0, c.BlockEnd - now))} min left";
        });
        var back = V.Btn("Back to work", () => BackToWork(), accent: true, stretch: true, height: 40);
        var hold = new HoldButton { OnHeld = () => LeaveHeld() };
        Hold = hold;
        var hint = V.Caption($"Hold for 2 seconds. {c.CatName} wanders off, and the block keeps going without {c.CatName}.", wrap: true, align: TextAlignment.Center);
        hint.HorizontalAlignment = HorizontalAlignment.Center;
        var foot = V.Row(2, V.Caption($"{c.Target.Label} is on your list until {Format.ClockTime(c.BlockEnd)} ·"), V.Link("Edit the list", () => EditList()));
        foot.HorizontalAlignment = HorizontalAlignment.Center;
        var title = V.T("Boo! You're focusing.", 28, FontWeights.SemiBold, Palette.Text, align: TextAlignment.Center);
        title.HorizontalAlignment = HorizontalAlignment.Center;
        var words = V.Col(12, title, line, dial, block, V.Col(8, back, hold.View), hint, V.Divider(), foot);
        return WithCat(words, c.Cat);
    }

    /// <summary>The card, with the cat walking in over its top, in the middle of the dimmed screen.</summary>
    static FrameworkElement WithCat(UIElement words, string catId)
    {
        var card = V.Surface(words, new Thickness(28, 88, 28, 20));
        card.Width = 440;
        var cat = V.Painting(Art.HasPose(catId, "walk") ? Art.Pose(catId, "walk") : Art.Portrait(catId), 180);
        cat.HorizontalAlignment = HorizontalAlignment.Center;
        cat.VerticalAlignment = VerticalAlignment.Top;
        cat.Margin = new Thickness(0, -88, 56, 0);
        var stack = new Grid { HorizontalAlignment = HorizontalAlignment.Center, VerticalAlignment = VerticalAlignment.Center, Margin = new Thickness(0, 0, 0, 60) };
        stack.Children.Add(card);
        stack.Children.Add(cat);
        var all = new Grid { Background = Palette.B(Microsoft.UI.Colors.Transparent) };
        all.Children.Add(stack);
        return all;
    }

    /// <summary>
    /// Wait it out (30 seconds with the cat, then it opens), locked (it doesn't open until the block ends) or used up
    /// (today's allowance is spent). Nothing hurries it: Back to work, or wait. The Mac's OtherCardView.
    /// </summary>
    FrameworkElement BuildOther(OtherCard c)
    {
        tickers.Clear();
        Hold = null;
        var title = V.T(c.Title, 28, FontWeights.SemiBold, Palette.Text, wrap: true, align: TextAlignment.Center);
        title.HorizontalAlignment = HorizontalAlignment.Center;
        var line = V.Body("", wrap: true, color: Palette.Text2, align: TextAlignment.Center);
        line.HorizontalAlignment = HorizontalAlignment.Center;
        UIElement? dial = null;
        var count = V.ClockText("", 24);
        count.VerticalAlignment = VerticalAlignment.Center;
        var ring = V.Arc(72, 5, 1, Palette.Accent);
        if (c.Kind == OtherKind.WaitItOut)
        {
            var d = new Grid { Width = 72, Height = 72, HorizontalAlignment = HorizontalAlignment.Center };
            d.Children.Add(V.Ring(72, 5, Palette.RingTrack));
            d.Children.Add(ring);
            d.Children.Add(count);
            dial = d;
        }
        tickers.Add(now =>
        {
            Line = c.Line(now);
            line.Text = Line;
            count.Text = c.SecondsLeft(now).ToString();
            V.SetArc(ring, 72, 5, c.Fraction(now));
        });
        var back = V.Btn("Back to work", () => BackToWorkOther(), accent: true, stretch: true, height: 40);
        var hint = V.Caption(c.Kind switch
        {
            OtherKind.WaitItOut => "It opens by itself when the wait is over, and a visit that waited may stay a while.",
            OtherKind.Locked => "A locked list stays shut until the block ends, and the block can't be stopped meanwhile.",
            _ => "Its time outside your blocks is used up for today. It opens again tomorrow, or after the window ends.",
        }, wrap: true, align: TextAlignment.Center);
        hint.HorizontalAlignment = HorizontalAlignment.Center;
        var foot = V.Row(2, V.Caption($"{c.Target.Label} is on your {c.ListName} list ·"), V.Link("Edit the list", () => EditList()));
        foot.HorizontalAlignment = HorizontalAlignment.Center;
        return WithCat(V.Col(12, title, line, dial, back, hint, V.Divider(), foot), c.Cat);
    }
}

/// <summary>
/// Park a thought: a box from any app (Ctrl+Alt+P), like Windows' own search box. The thought goes to Later and
/// onto the block. Esc, or a click elsewhere, closes it. The Mac's ParkView.
/// </summary>
sealed class ParkWindow
{
    const double Width = 600;
    readonly Store store;
    public Floating Win { get; } = new("Chataltul park", activates: true, Backdrop.Acrylic);
    TextBox? box;

    public ParkWindow(Store store)
    {
        this.store = store;
        Win.Deactivated += Hide;
    }

    public bool IsShown => Win.IsShown;

    public void Toggle() { if (IsShown) Hide(); else Show(); }

    public void Show()
    {
        Palette.Dark = Palette.SystemDark();
        Win.Root.RequestedTheme = Palette.Theme;
        Win.Content = Build();
        var at = Native.Cursor();
        var work = Native.WorkAreaAt(at);
        var scale = Floating.ScaleAt(at);
        var size = Win.Measure(Width);
        int w = (int)Math.Ceiling(size.Width * scale), h = (int)Math.Ceiling(size.Height * scale);
        Win.Move(work.Left + (work.Width - w) / 2, work.Top + (int)(work.Height * 0.18), w, h);
        Win.Show();
        Win.Refit(() =>
        {
            var again = Win.Measure(Width);
            Win.Move(work.Left + (work.Width - w) / 2, work.Top + (int)(work.Height * 0.18), w, (int)Math.Ceiling(again.Height * scale));
        });
        Ui.Post(() => box?.Focus(FocusState.Programmatic));
    }

    public void Hide() => Win.Hide();

    /// <summary>What Park does, from the box or the button.</summary>
    public bool Park(string text)
    {
        if (!store.Park(text)) return false;
        Hide();
        return true;
    }

    FrameworkElement Build()
    {
        var field = new TextBox { PlaceholderText = "A thought to park", FontSize = 18, VerticalAlignment = VerticalAlignment.Center };
        box = field;
        field.KeyDown += (_, e) =>
        {
            if (e.Key == Windows.System.VirtualKey.Enter) { e.Handled = true; Park(field.Text); }
            else if (e.Key == Windows.System.VirtualKey.Escape) { e.Handled = true; Hide(); }
        };
        var go = V.Btn("Park", () => Park(field.Text), accent: true, height: 36);
        go.VerticalAlignment = VerticalAlignment.Center;
        var top = new Grid { ColumnSpacing = 12 };
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        top.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var bulb = V.Icon(V.Glyph.Bulb, 20, Palette.Accent);
        bulb.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(field, 1);
        Grid.SetColumn(go, 2);
        top.Children.Add(bulb);
        top.Children.Add(field);
        top.Children.Add(go);
        var col = V.Col(12, top);
        var later = store.State.View.Later;
        if (later.Count > 0)
        {
            var list = V.Col(6, V.Label("Already in Later"));
            foreach (var l in later.Take(4))
                list.Children.Add(V.Row(10, new Ellipse { Width = 12, Height = 12, Stroke = Palette.B(Palette.Text3), StrokeThickness = 1.5 }, V.Body(l.Text)));
            col.Children.Add(V.Divider());
            col.Children.Add(list);
        }
        col.Children.Add(V.Spread(V.Caption(store.Active != null ? "It waits in Later. Your block keeps going." : "It waits in Later."),
            V.Caption($"{store.Settings.ParkKey.Label} from any app")));
        return new Border
        {
            Child = col, Width = Width, Padding = new Thickness(16),
            HorizontalAlignment = HorizontalAlignment.Left, VerticalAlignment = VerticalAlignment.Top,
        };
    }
}
