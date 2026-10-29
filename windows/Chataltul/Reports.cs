using Chataltul.Core;
using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Shapes;
using Windows.Storage;
using Windows.Storage.Pickers;
using Windows.UI;

namespace Chataltul;

/// <summary>
/// The reports' pieces: where the blocks went (the bar and its apps), and the week in review (a few words made on
/// this PC, each day by tag against the goal, a question box answered from the numbers, the streak with its pause,
/// how the blocks went, what pulled the person away). The Mac's WeekReview.swift.
/// </summary>
static class Reports
{
    // read each time: the accent follows the theme
    static Color[] WentColors => [Palette.Accent, Palette.C(0x7FB1F5), Palette.C(0xFFCF8A), Palette.C(0xA99BE8), Palette.C(0xF29BB0), Palette.C(0x8A8A95)];

    static Border Card(params UIElement?[] children) => V.Card(V.Col(10, children), new Thickness(16, 14, 16, 14));

    /// <summary>A bar of the apps' shares, side by side.</summary>
    public static Grid WentBar(IReadOnlyList<WentRow> rows, double height = 10)
    {
        var g = new Grid { ColumnSpacing = 2, Height = height };
        for (var i = 0; i < rows.Count; i++)
        {
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(0.02, rows[i].Share), GridUnitType.Star) });
            var seg = new Border { CornerRadius = new CornerRadius(height / 2), Background = Palette.B(WentColors[i % WentColors.Length]) };
            Grid.SetColumn(seg, i);
            g.Children.Add(seg);
        }
        return g;
    }

    /// <summary>"Where your blocks went": the bar and each app's share, or nothing when there's nothing kept.</summary>
    public static UIElement? WentPanel(IReadOnlyList<WentRow> rows)
    {
        if (rows.Count == 0) return null;
        var list = V.Col(4);
        for (var i = 0; i < rows.Count; i++)
        {
            var r = rows[i];
            var dot = new Rectangle { Width = 8, Height = 8, RadiusX = 2, RadiusY = 2, Fill = Palette.B(WentColors[i % WentColors.Length]), VerticalAlignment = VerticalAlignment.Center };
            var words = V.T($"{r.Label}  {Format.FmtMin(r.Minutes)} · {Math.Round(r.Share * 100, MidpointRounding.AwayFromZero):0}%", 12,
                color: r.Distraction ? Palette.Caution : Palette.Text2);
            list.Children.Add(V.Row(6, dot, words));
        }
        return Card(V.Spread(V.Strong("Where your blocks went"), V.Caption("only on this PC")), WentBar(rows), list);
    }

    /// <summary>The block that came home, in the popover: the bar and the top three ("Writer 41 min, Edge: youtube.com 6 min").</summary>
    public static UIElement? WentLine(IReadOnlyList<WentRow> rows)
    {
        if (rows.Count == 0) return null;
        var line = new TextBlock { TextWrapping = TextWrapping.WrapWholeWords, FontSize = 12 };
        foreach (var (r, i) in rows.Take(3).Select((x, n) => (x, n)))
        {
            if (i > 0) line.Inlines.Add(new Microsoft.UI.Xaml.Documents.Run { Text = ", ", Foreground = Palette.B(Palette.Text2) });
            line.Inlines.Add(new Microsoft.UI.Xaml.Documents.Run { Text = $"{r.Label} {Format.FmtMin(r.Minutes)}", Foreground = Palette.B(r.Distraction ? Palette.Caution : Palette.Text2) });
        }
        return V.Col(6, V.Label("WHERE IT WENT"), WentBar(rows, 6), line);
    }

    // ---------------------------------------------------------------- the week in review

    /// <summary>The week, in a few words and its pictures. <c>ask</c> answers a question about it.</summary>
    public static UIElement Week(Store store, IReadOnlyList<Hist> blocks, Period p, Func<string?, Color> tagColor,
        string question, string answer, Action<string> keepQuestion, Action ask, Action<bool> typing)
    {
        var f = Review.Facts(blocks, p, store.EngineSettings.Goal, store.StreakWithPause);
        var summary = V.Card(Beside(V.Painting(Art.Kitten(store.State.Companion), 76),
            V.Col(6, V.Label("YOUR WEEK, IN A FEW WORDS · MADE ON THIS PC"), V.T(Review.Summary(f), 16, color: Palette.Text, wrap: true))), new Thickness(18, 16, 18, 16));
        var went = store.Settings.RecordTiming ? WentPanel(store.WentRows(blocks.Select(b => b.Id))) : null;
        var left = V.Col(16, EachDay(store, blocks, p, tagColor), Ask(question, answer, keepQuestion, ask, typing), went);
        var right = V.Col(16, StreakCard(f, blocks, p), Outcomes(f), Pulled(f));
        right.Width = 330;
        var g = new Grid { ColumnSpacing = 16 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Grid.SetColumn(right, 1);
        g.Children.Add(left);
        g.Children.Add(right);
        return V.Col(16, summary, g);
    }

    static Grid Beside(UIElement a, UIElement b)
    {
        var g = new Grid { ColumnSpacing = 18 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        ((FrameworkElement)b).VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn((FrameworkElement)b, 1);
        g.Children.Add(a);
        g.Children.Add(b);
        return g;
    }

    /// <summary>Each day of the week stacked by tag, with the goal's line.</summary>
    static UIElement EachDay(Store store, IReadOnlyList<Hist> blocks, Period p, Func<string?, Color> tagColor)
    {
        var stacks = Insights.SlotsByTag(blocks, p);
        var totals = stacks.Select(s => s.Values.Sum()).ToArray();
        var goal = store.EngineSettings.Goal;
        var most = Math.Max(1, Math.Max(totals.DefaultIfEmpty(0).Max(), goal));
        const double tall = 140;
        var bars = new Grid { ColumnSpacing = 16, Height = tall + 4 };
        for (var i = 0; i < stacks.Length; i++)
        {
            bars.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var col = new StackPanel { VerticalAlignment = VerticalAlignment.Bottom, Spacing = 2 };
            if (totals[i] == 0) col.Children.Add(new Border { Height = 6, CornerRadius = new CornerRadius(3), Background = Palette.B(Palette.RingTrack) });
            foreach (var (tag, m) in stacks[i].OrderByDescending(kv => kv.Key, StringComparer.Ordinal))
                col.Children.Add(new Border { Height = Math.Max(3, m / (double)most * tall), Background = Palette.B(tagColor(tag)) });
            Grid.SetColumn(col, i);
            bars.Children.Add(col);
        }
        if (goal > 0)
        {
            var line = new Rectangle { Height = 1, Fill = Palette.B(Palette.Text3), VerticalAlignment = VerticalAlignment.Bottom, Margin = new Thickness(0, 0, 0, goal / (double)most * tall) };
            Grid.SetColumnSpan(line, Math.Max(1, stacks.Length));
            bars.Children.Add(line);
        }
        var days = new Grid { ColumnSpacing = 16 };
        for (var i = 0; i < 7; i++)
        {
            days.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var d = V.Caption(Review.Days[i][..3], align: TextAlignment.Center);
            d.HorizontalAlignment = HorizontalAlignment.Center;
            Grid.SetColumn(d, i);
            days.Children.Add(d);
        }
        var keys = V.Row(16);
        foreach (var t in stacks.SelectMany(s => s.Keys).Distinct().Order(StringComparer.Ordinal))
            keys.Children.Add(V.Row(5, new Rectangle { Width = 9, Height = 9, Fill = Palette.B(tagColor(t)) }, V.Caption(t.Length == 0 ? "Untagged" : t)));
        return Card(V.Spread(V.Strong("Each day"), goal > 0 ? V.Caption($"goal {Format.FmtMin(goal)} a day") : null), bars, days, keys);
    }

    static UIElement Ask(string question, string answer, Action<string> keep, Action ask, Action<bool> typing)
    {
        var box = V.Field("Ask about your week, like: when do I focus best?", question);
        box.TextChanged += (_, _) => keep(box.Text);
        box.GotFocus += (_, _) => typing(true);
        box.LostFocus += (_, _) => typing(false);
        box.KeyDown += (_, e) => { if (e.Key == Windows.System.VirtualKey.Enter) { e.Handled = true; typing(false); ask(); } };
        var go = V.Btn("Ask", ask, accent: true);
        var row = new Grid { ColumnSpacing = 10 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Grid.SetColumn(go, 1);
        row.Children.Add(box);
        row.Children.Add(go);
        return Card(row,
            answer.Length > 0 ? V.T(answer, 14, color: Palette.Text, wrap: true) : null,
            answer.Length > 0 ? V.Caption("Answered from the week's numbers, on this PC.") : null);
    }

    static UIElement StreakCard(WeekFacts f, IReadOnlyList<Hist> blocks, Period p)
    {
        var focused = blocks.Select(b => Format.Local(b.Start).Date).ToHashSet();
        var paused = f.Streak.PausedDays.ToHashSet();
        var days = new Grid { ColumnSpacing = 8 };
        for (var i = 0; i < 7; i++)
        {
            days.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var day = p.Start.Date.AddDays(i);
            bool on = focused.Contains(day), pz = paused.Contains(day), future = day > DateTime.Today;
            var cell = new Border
            {
                Height = 28, CornerRadius = new CornerRadius(8), Opacity = future ? 0.5 : 1,
                Background = Palette.B(on ? Palette.Accent : pz ? Palette.C(0xFFCF8A) : Palette.RingTrack),
                Child = pz ? V.Icon(V.Glyph.Pause, 11, Palette.C(0x7A4A12)) : null,
            };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(cell, on ? "Focused" : pz ? "The pause" : "No block");
            Grid.SetColumn(cell, i);
            days.Children.Add(cell);
        }
        var used = f.Streak.PausedDays.FirstOrDefault(d => Format.Ms(d) >= p.From && Format.Ms(d) < p.To);
        var note = used != default ? V.Caption($"{used:dddd} used the week's pause, so the streak kept going.", wrap: true) : null;
        return Card(V.Spread(V.Strong(Format.Plural(f.Streak.Days, "day") + " in a row"), V.Caption(f.Streak.PausesLeft > 0 ? "1 pause left this week" : "this week's pause is used")), days, note);
    }

    static UIElement Outcomes(WeekFacts f)
    {
        static Border Tile(int n, string label, Color fill)
        {
            var t = V.Col(2, V.ClockText(n.ToString(), 22), V.Caption(label, align: TextAlignment.Center));
            ((TextBlock)t.Children[1]).HorizontalAlignment = HorizontalAlignment.Center;
            return new Border { Child = t, Padding = new Thickness(0, 10, 0, 10), CornerRadius = new CornerRadius(10), Background = Palette.B(fill) };
        }
        return Card(V.Strong("How the blocks went"), V.Even(8,
            Tile(f.Done, "Done", Color.FromArgb(40, Palette.Accent.R, Palette.Accent.G, Palette.Accent.B)), Tile(f.Partly, "Partly", Palette.C(0xFFCF8A, 0.25)), Tile(f.NotReally, "Not really", Palette.C(0xF29BB0, 0.22))));
    }

    static UIElement Pulled(WeekFacts f)
    {
        var list = V.Col(6);
        if (f.Reasons.Count == 0) list.Children.Add(V.Caption("Nothing noted. When you answer Drifted, the cat asks what it was.", wrap: true));
        foreach (var (id, times) in f.Reasons.Take(5)) list.Children.Add(V.Spread(V.Body(Core.Reasons.Label(id)), V.Caption(Format.Plural(times, "time"))));
        return Card(V.Strong("What pulled you away"), list);
    }

    // ---------------------------------------------------------------- export

    /// <summary>A file the person saves where they choose (the self-test puts it beside its pictures).</summary>
    public static async Task Save(IntPtr hwnd, string name, string text, string kind, string extension)
    {
        if (App.Options.SelfTest)
        {
            if (App.Options.Shots is { } dir) { Directory.CreateDirectory(dir); File.WriteAllText(System.IO.Path.Join(dir, name), text); }
            Saved.Add(name);
            return;
        }
        try
        {
            var picker = new FileSavePicker { SuggestedStartLocation = PickerLocationId.DocumentsLibrary, SuggestedFileName = System.IO.Path.GetFileNameWithoutExtension(name) };
            picker.FileTypeChoices.Add(kind, [extension]);
            WinRT.Interop.InitializeWithWindow.Initialize(picker, hwnd);
            if (await picker.PickSaveFileAsync() is { } file) await FileIO.WriteTextAsync(file, text);
        }
        catch (Exception e) { Log.Write($"export {name}: {e.Message}"); }
    }

    /// <summary>What the self-test exported, by name.</summary>
    public static readonly List<string> Saved = [];
}
