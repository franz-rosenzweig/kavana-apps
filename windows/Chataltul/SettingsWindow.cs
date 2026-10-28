using Chataltul.Core;
using Microsoft.UI;
using Microsoft.UI.Text;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics;
using Windows.System;

namespace Chataltul;

/// <summary>
/// Settings: General, Blocks and check-ins, Blocklist, Shortcuts, as Windows 11's own Settings looks: Mica, the
/// title in a tall title bar with the system's caption buttons, the pages on the left. Ctrl+Plus, Ctrl+Minus,
/// Ctrl+0 and Ctrl+wheel make the text bigger or smaller. Closing it only hides it. The Mac's SettingsWindow.swift
/// (Breaks and Account come later on Windows).
/// </summary>
sealed class SettingsWindow
{
    readonly Store store;
    readonly Controller app;
    public Window Window { get; }
    readonly Grid root = new();
    readonly ColumnDefinition leftPad = new() { Width = new GridLength(0) }, rightPad = new() { Width = new GridLength(138) };
    readonly TextBlock titleText = new() { Text = "Chataltul Settings", FontSize = 12, VerticalAlignment = VerticalAlignment.Center };
    readonly NavigationView nav;
    readonly ScrollViewer page = new()
    {
        ZoomMode = ZoomMode.Enabled, MinZoomFactor = 0.75f, MaxZoomFactor = 2f,
        HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
    };
    readonly Grid holder = new() { Padding = new Thickness(28, 12, 28, 28), HorizontalAlignment = HorizontalAlignment.Left };
    public string Pane { get; private set; } = "general";
    public bool IsShown { get; private set; }
    /// <summary>Which shortcut is waiting for its new keys, if any.</summary>
    string? recording;
    /// <summary>A change made by a control on the page, which shows it already: the page isn't built again under it.</summary>
    bool quiet;

    void Quietly(Action<WinSettings> edit)
    {
        quiet = true;
        try { store.ChangeSettings(edit); } finally { quiet = false; }
    }

    static readonly (string Id, string Title, string Glyph)[] Panes =
    [
        ("general", "General", ""), ("blocks", "Blocks and check-ins", ""), ("blocklist", "Blocklist", ""), ("shortcuts", "Shortcuts", ""),
    ];

    public SettingsWindow(Store store, Controller app)
    {
        this.store = store;
        this.app = app;
        page.Content = holder;
        nav = new NavigationView
        {
            PaneDisplayMode = NavigationViewPaneDisplayMode.Left, IsBackButtonVisible = NavigationViewBackButtonVisible.Collapsed,
            IsSettingsVisible = false, IsPaneToggleButtonVisible = false, OpenPaneLength = 260, Content = page,
            IsTitleBarAutoPaddingEnabled = false,
        };
        foreach (var (id, title, glyph) in Panes)
            nav.MenuItems.Add(new NavigationViewItem { Content = title, Tag = id, Icon = new FontIcon { Glyph = glyph } });
        nav.SelectionChanged += (_, e) =>
        {
            if (e.SelectedItem is NavigationViewItem { Tag: string id } && id != Pane) { Pane = id; Build(); }
        };

        // the title row: the icon and the name, the rest of it moves the window, the caption buttons at its right
        var titleRow = new Grid { Height = 48 };
        titleRow.ColumnDefinitions.Add(leftPad);
        titleRow.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        titleRow.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        titleRow.ColumnDefinitions.Add(rightPad);
        var iconFile = Path.Join(AppContext.BaseDirectory, "Assets", "Chataltul.ico");
        var icon = new Image { Width = 16, Height = 16, Margin = new Thickness(16, 0, 0, 0), VerticalAlignment = VerticalAlignment.Center };
        if (File.Exists(iconFile)) icon.Source = new BitmapImage(new Uri(iconFile));
        Grid.SetColumn(icon, 1);
        var drag = new Grid { Background = new SolidColorBrush(Colors.Transparent) };
        titleText.Margin = new Thickness(12, 0, 0, 0);
        drag.Children.Add(titleText);
        Grid.SetColumn(drag, 2);
        titleRow.Children.Add(icon);
        titleRow.Children.Add(drag);
        root.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
        root.RowDefinitions.Add(new RowDefinition { Height = new GridLength(1, GridUnitType.Star) });
        Grid.SetRow(nav, 1);
        root.Children.Add(titleRow);
        root.Children.Add(nav);

        Window = new Window { Title = "Chataltul Settings", Content = root };
        Window.SystemBackdrop = new MicaBackdrop();
        Window.ExtendsContentIntoTitleBar = true;
        Window.SetTitleBar(drag);
        var bar = Window.AppWindow.TitleBar;
        bar.PreferredHeightOption = TitleBarHeightOption.Tall;
        bar.ButtonBackgroundColor = Colors.Transparent;
        bar.ButtonInactiveBackgroundColor = Colors.Transparent;
        PaintCaptionButtons();
        root.ActualThemeChanged += (_, _) => PaintCaptionButtons();
        root.Loaded += (_, _) => FitTitleBar();
        Window.AppWindow.Changed += (_, e) => { if (e.DidSizeChange) FitTitleBar(); };
        // as any Windows title bar: dimmer while another window is in front
        Window.Activated += (_, e) => icon.Opacity = titleText.Opacity = e.WindowActivationState == WindowActivationState.Deactivated ? 0.5 : 1;
        Window.AppWindow.Closing += (_, e) => { e.Cancel = true; Hide(); };
        if (Window.AppWindow.Presenter is OverlappedPresenter p) p.IsMaximizable = false;
        if (File.Exists(iconFile)) Window.AppWindow.SetIcon(iconFile);

        // the text size: Ctrl and the wheel zoom the page by themselves; the keys are below
        page.SizeChanged += (_, _) => Reflow();
        page.ViewChanged += (_, e) =>
        {
            Reflow();
            if (!e.IsIntermediate && Math.Abs(page.ZoomFactor - store.Settings.TextZoom) > 0.01) Quietly(s => s.TextZoom = page.ZoomFactor);
        };
        page.Loaded += (_, _) => page.ChangeView(null, null, Math.Clamp(store.Settings.TextZoom, 0.75f, 2f), true);
        nav.PreviewKeyDown += OnKey;
        store.SettingsChanged += () => { if (IsShown && recording == null && !quiet) Build(); };
    }

    /// <summary>The caption buttons in the theme's colors, light or dark, as the system's own title bars have them.</summary>
    void PaintCaptionButtons()
    {
        var dark = root.ActualTheme == ElementTheme.Dark;
        var bar = Window.AppWindow.TitleBar;
        var ink = dark ? Colors.White : Colors.Black;
        bar.ButtonForegroundColor = ink;
        bar.ButtonHoverForegroundColor = ink;
        bar.ButtonPressedForegroundColor = ink;
        bar.ButtonHoverBackgroundColor = dark ? ColorHelper.FromArgb(0x15, 0xFF, 0xFF, 0xFF) : ColorHelper.FromArgb(0x09, 0, 0, 0);
        bar.ButtonPressedBackgroundColor = dark ? ColorHelper.FromArgb(0x0B, 0xFF, 0xFF, 0xFF) : ColorHelper.FromArgb(0x06, 0, 0, 0);
        bar.ButtonInactiveForegroundColor = dark ? ColorHelper.FromArgb(0x5D, 0xFF, 0xFF, 0xFF) : ColorHelper.FromArgb(0x5C, 0, 0, 0);
    }

    /// <summary>Room for the caption buttons, which the system draws over the row.</summary>
    void FitTitleBar()
    {
        var scale = root.XamlRoot?.RasterizationScale ?? Native.GetDpiForWindow(WinRT.Interop.WindowNative.GetWindowHandle(Window)) / 96.0;
        if (scale <= 0) return;
        leftPad.Width = new GridLength(Window.AppWindow.TitleBar.LeftInset / scale);
        rightPad.Width = new GridLength(Window.AppWindow.TitleBar.RightInset / scale);
    }

    /// <summary>The page as wide as the window at any text size, so bigger text wraps rather than running off the side.</summary>
    void Reflow()
    {
        if (page.ActualWidth <= 0) return;
        holder.Width = Math.Max(320, page.ActualWidth / page.ZoomFactor);
    }

    /// <summary>The text size, as Ctrl+Plus, Ctrl+Minus and Ctrl+0 change it.</summary>
    public void Zoom(float factor) => page.ChangeView(null, null, Math.Clamp(factor, 0.75f, 2f));

    public float ZoomFactor => page.ZoomFactor;

    public void Show(string? pane)
    {
        if (pane != null) Pane = pane;
        Palette.Dark = Palette.SystemDark();
        root.RequestedTheme = Palette.Theme;
        nav.SelectedItem = nav.MenuItems.OfType<NavigationViewItem>().FirstOrDefault(i => (string)i.Tag == Pane);
        Build();
        if (!IsShown)
        {
            var at = Native.Cursor();
            var work = Native.WorkAreaAt(at);
            var scale = Floating.ScaleAt(at);
            int w = (int)(900 * scale), h = (int)(640 * scale);
            var x = work.Left + (work.Width - w) / 2;
            var y = work.Top + (work.Height - h) / 2;
            if (Floating.Offscreen) { x -= 40_000; y -= 40_000; }
            Window.AppWindow.MoveAndResize(new RectInt32(x, y, w, h));
        }
        IsShown = true;
        // off the screen in the self-test, it doesn't take the front from whoever is working
        if (Floating.Offscreen) Window.AppWindow.Show(false);
        else Window.Activate();
    }

    public void Hide()
    {
        IsShown = false;
        recording = null;
        app.Recording = false;
        app.RegisterShortcuts();
        Window.AppWindow.Hide();
    }

    public UIElement Build()
    {
        UIElement content = Pane switch
        {
            "blocks" => Blocks(),
            "blocklist" => BlocklistPane(),
            "shortcuts" => Shortcuts(),
            _ => General(),
        };
        holder.Children.Clear();
        holder.Children.Add(content);
        Reflow();
        return content;
    }

    // ---------------------------------------------------------------- pieces

    static TextBlock Heading(string text) => new() { Text = text, FontSize = 24, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 0, 0, 8) };
    static TextBlock Section(string text) => new() { Text = text, FontSize = 15, FontWeight = FontWeights.SemiBold, Margin = new Thickness(0, 14, 0, 2) };
    static TextBlock Note(string text) => new() { Text = text, FontSize = 12, Opacity = 0.7, TextWrapping = TextWrapping.WrapWholeWords };

    static ToggleSwitch Switch(string header, bool on, Action<bool> changed)
    {
        var t = new ToggleSwitch { Header = header, IsOn = on };
        t.Toggled += (_, _) => changed(t.IsOn);
        return t;
    }

    static ComboBox Choice<T>(string header, IEnumerable<(T Value, string Label)> items, T now, Action<T> changed)
    {
        var list = items.ToList();
        var c = new ComboBox { Header = header, MinWidth = 220 };
        foreach (var (_, label) in list) c.Items.Add(label);
        c.SelectedIndex = Math.Max(0, list.FindIndex(i => EqualityComparer<T>.Default.Equals(i.Value, now)));
        c.SelectionChanged += (_, _) => { if (c.SelectedIndex >= 0) changed(list[c.SelectedIndex].Value); };
        return c;
    }

    static StackPanel Col(params UIElement?[] children)
    {
        var s = new StackPanel { Spacing = 10, MaxWidth = 620, HorizontalAlignment = HorizontalAlignment.Left };
        foreach (var c in children) if (c != null) s.Children.Add(c);
        return s;
    }

    void Engine(string key, object? value) => store.SetEngine(new() { [key] = value });

    // ---------------------------------------------------------------- general

    UIElement General()
    {
        var commitFile = Path.Join(AppContext.BaseDirectory, "js", "WEB_COMMIT");
        var commit = File.Exists(commitFile) ? File.ReadAllText(commitFile).Trim() : "?";
        var web = commit[..Math.Min(7, commit.Length)];
        var folder = new Button { Content = "Open the folder" };
        folder.Click += (_, _) => Outside.Open(store.Folder);
        return Col(
            Heading("General"),
            Switch("Open Chataltul when I sign in to Windows", Startup.IsOn, on => Startup.Set(on)),
            Switch("Cats and treats (the kitten that grows in each block)", store.State.CatsOn, on => Engine("cat", on)),
            Switch("The cat peeks up in the corner of the screen for check-ins", store.Settings.CatInCorner, on => Quietly(s => s.CatInCorner = on)),
            Note("Off, check-ins come as Windows notifications only."),
            Section("Where your things are"),
            Note($"The doc, these settings and the log are in {store.Folder}. Nothing leaves this PC: Chataltul for Windows has no account yet."),
            folder,
            Section("About"),
            Note($"Chataltul {App.Version} for Windows, with the web's engine at {web}."));
    }

    // ---------------------------------------------------------------- blocks and check-ins

    UIElement Blocks()
    {
        var s = store.EngineSettings;
        var intervals = new[] { 0, 10, 15, 20, 30, 45, 60 }.Select(i => (i, i == 0 ? "Not during a block" : $"Every {i} minutes"));
        var after = new NumberBox
        {
            Header = "Ask after this many minutes away from the PC", Value = store.Settings.AwayAfterMin, Minimum = 1, Maximum = 60,
            SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline, SmallChange = 1, MinWidth = 220, HorizontalAlignment = HorizontalAlignment.Left,
        };
        after.ValueChanged += (_, e) =>
        {
            if (!double.IsNaN(e.NewValue)) Quietly(x => x.AwayAfterMin = Math.Clamp((int)Math.Round(e.NewValue), 1, 60));
        };
        var awayChoices = new List<(AwayAction, string)> { (AwayAction.Ask, "Ask me whether they count"), (AwayAction.Count, "Count them") };
        if (store.State.CanTakeOff) awayChoices.Add((AwayAction.TakeOff, "Take them off"));
        var cycle = store.Settings.Cycle;
        var perDay = new NumberBox { Header = "Blocks in a day (the dashes in the popover)", Value = cycle.PerDay, Minimum = 1, Maximum = 16, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline, MinWidth = 220, HorizontalAlignment = HorizontalAlignment.Left };
        perDay.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) Quietly(x => x.Cycle.PerDay = Math.Clamp((int)e.NewValue, 1, 16)); };
        var longAfter = new NumberBox { Header = "A long break after this many blocks", Value = cycle.LongAfter, Minimum = 0, Maximum = 12, SpinButtonPlacementMode = NumberBoxSpinButtonPlacementMode.Inline, MinWidth = 220, HorizontalAlignment = HorizontalAlignment.Left };
        longAfter.ValueChanged += (_, e) => { if (!double.IsNaN(e.NewValue)) Quietly(x => x.Cycle.LongAfter = Math.Clamp((int)e.NewValue, 0, 12)); };
        return Col(
            Heading("Blocks and check-ins"),
            Choice("Check in", intervals, s.Interval, i => Engine("interval", i)),
            Choice("How", new[] { ("ask", "Ask me (Yes or Drifted)"), ("nudge", "Nudge me (a line, no answer)") }, s.Style, v => Engine("style", v)),
            Choice("Pausing", new[] { ("five", "Five minutes at most"), ("off", "No pausing") }, s.Pause, v => Engine("pause", v)),
            Switch("Count extra time: the block goes on past its end until I press Stop", s.Overtime, on => Engine("overtime", on)),
            Note("These are the web's own settings, so with an account they would follow you to the web and the phone."),
            Section("Away from the PC"),
            after,
            Choice("When I'm back", awayChoices, store.Settings.AwayAction, a => Quietly(x => x.AwayAction = a)),
            Note(store.State.CanTakeOff ? "Being away isn't drifting: the kitten stays either way."
                : "Being away isn't drifting: the kitten stays either way. \"Take them off\" needs a change in the web's engine (mac/docs/engine-takeoff.md), so for now the minutes count."),
            Section("The day"),
            perDay,
            longAfter);
    }

    // ---------------------------------------------------------------- the blocklist

    UIElement BlocklistPane()
    {
        var list = store.Settings.Blocklist;
        var apps = new StackPanel { Spacing = 6 };
        foreach (var a in list.Apps.ToList())
            apps.Children.Add(Item($"{a.Name}", a.Exe, () => store.ChangeSettings(s => s.Blocklist.RemoveApp(a.Exe))));
        if (!list.Apps.Any()) apps.Children.Add(Note("No apps yet."));

        var add = new DropDownButton { Content = "Add an app" };
        var menu = new MenuFlyout { Placement = Microsoft.UI.Xaml.Controls.Primitives.FlyoutPlacementMode.Bottom };
        menu.Opening += (_, _) =>
        {
            menu.Items.Clear();
            foreach (var (exe, name) in RunningApps())
            {
                var item = new MenuFlyoutItem { Text = $"{name}  ({exe})" };
                item.Click += (_, _) => store.ChangeSettings(s => s.Blocklist.AddApp(new ListedApp(exe, name)));
                menu.Items.Add(item);
            }
            if (menu.Items.Count == 0) menu.Items.Add(new MenuFlyoutItem { Text = "No other apps are open", IsEnabled = false });
            menu.Items.Add(new MenuFlyoutSeparator());
            var browse = new MenuFlyoutItem { Text = "Find a program…" };
            browse.Click += async (_, _) => await Browse();
            menu.Items.Add(browse);
        };
        add.Flyout = menu;

        var sites = new StackPanel { Spacing = 6 };
        foreach (var d in list.Sites.ToList())
            sites.Children.Add(Item(d, null, () => store.ChangeSettings(s => s.Blocklist.RemoveSite(d))));
        if (!list.Sites.Any()) sites.Children.Add(Note("No sites yet."));
        var siteBox = new TextBox { PlaceholderText = "youtube.com", MinWidth = 260 };
        var addSite = new Button { Content = "Add" };
        var problem = Note("");
        void AddSite()
        {
            var typed = siteBox.Text;
            if (Blocklist.NormalizeDomain(typed) == null) { problem.Text = "That isn't a site's address."; return; }
            store.ChangeSettings(s => s.Blocklist.AddSite(typed));
        }
        addSite.Click += (_, _) => AddSite();
        siteBox.KeyDown += (_, e) => { if (e.Key == VirtualKey.Enter) { e.Handled = true; AddSite(); } };
        var siteRow = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 8, Children = { siteBox, addSite } };

        return Col(
            Heading("Blocklist"),
            Switch("The speed bump: the cat walks over these during a block", store.Settings.SpeedBump, on => Quietly(s => s.SpeedBump = on)),
            Note("While a block runs and one of these comes to the front, the cat comes up: 15 seconds to come back, or hold Leave anyway, and your kitten wanders off. Once it has, the cat doesn't come up again for that block."),
            Section("Apps"),
            apps,
            add,
            Section("Websites"),
            sites,
            siteRow,
            problem,
            Note("A site covers its subdomains (youtube.com covers m.youtube.com)."),
            Switch("Look at the site open in my browser", store.Settings.ReadBrowsers, on => Quietly(s => s.ReadBrowsers = on)),
            Note("In " + string.Join(", ", Browser.All.Where(b => !b.Gecko).Select(b => b.Name)) + ", Chataltul reads the address of the front tab to find the sites on your list (Firefox and Zen are tried too). It only ever uses the site's name, and nothing leaves this PC."));
    }

    UIElement Item(string title, string? detail, Action remove)
    {
        var g = new Grid { ColumnSpacing = 10, MaxWidth = 520, HorizontalAlignment = HorizontalAlignment.Left };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var words = new StackPanel { VerticalAlignment = VerticalAlignment.Center };
        words.Children.Add(new TextBlock { Text = title, FontSize = 14 });
        if (detail != null) words.Children.Add(Note(detail));
        var x = new Button { Content = new FontIcon { Glyph = V.Glyph.Delete, FontSize = 14, FontFamily = V.Symbols }, Padding = new Thickness(8) };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(x, $"Take {title} off the list");
        x.Click += (_, _) => remove();
        Grid.SetColumn(x, 1);
        g.Children.Add(words);
        g.Children.Add(x);
        g.MinWidth = 420;
        return g;
    }

    /// <summary>Apps with a window open now, and the ones seen in front since Chataltul started; not those already listed.</summary>
    List<(string Exe, string Name)> RunningApps()
    {
        var found = new Dictionary<string, string>(app.Watcher.Seen);
        Native.EnumWindows((h, _) =>
        {
            if (!Native.IsWindowVisible(h) || Native.GetWindow(h, Native.GW_OWNER) != IntPtr.Zero || Native.GetWindowTextLength(h) == 0) return true;
            if (FrontApp.Of(h) is { } a && a.Pid != Environment.ProcessId && !Skip.Contains(a.Exe)) found.TryAdd(a.Exe, a.Name);
            return true;
        }, IntPtr.Zero);
        var listed = store.Settings.Blocklist.Apps.Select(a => a.Exe).ToHashSet();
        return found.Where(kv => !listed.Contains(kv.Key)).Select(kv => (kv.Key, kv.Value)).OrderBy(x => x.Value, StringComparer.CurrentCultureIgnoreCase).ToList();
    }

    static readonly HashSet<string> Skip = ["explorer.exe", "textinputhost.exe", "shellexperiencehost.exe", "searchhost.exe", "startmenuexperiencehost.exe", "applicationframehost.exe", "lockapp.exe"];

    async Task Browse()
    {
        var picker = new Windows.Storage.Pickers.FileOpenPicker { SuggestedStartLocation = Windows.Storage.Pickers.PickerLocationId.ComputerFolder };
        picker.FileTypeFilter.Add(".exe");
        WinRT.Interop.InitializeWithWindow.Initialize(picker, WinRT.Interop.WindowNative.GetWindowHandle(Window));
        var file = await picker.PickSingleFileAsync();
        if (file == null) return;
        store.ChangeSettings(s => s.Blocklist.AddApp(new ListedApp(file.Path, FrontApp.NameOf(file.Path))));
    }

    // ---------------------------------------------------------------- shortcuts

    UIElement Shortcuts()
    {
        var s = store.Settings;
        return Col(
            Heading("Shortcuts"),
            Note("They work in every app. Yes and Drifted are only taken while a check-in is waiting, so those keys are free the rest of the time."),
            Shortcut("start", "Start a block (press it again to start)", s.StartKey, KeyCombo.StartDefault),
            Shortcut("park", "Park a thought", s.ParkKey, KeyCombo.ParkDefault),
            Shortcut("yes", "Answer a check-in: Yes", s.YesKey, KeyCombo.YesDefault),
            Shortcut("drifted", "Answer a check-in: Drifted", s.DriftedKey, KeyCombo.DriftedDefault),
            app.ShortcutProblem is { } p ? Note(p) : null);
    }

    UIElement Shortcut(string id, string label, KeyCombo now, KeyCombo standard)
    {
        var row = new Grid { ColumnSpacing = 10, MaxWidth = 560, HorizontalAlignment = HorizontalAlignment.Left, MinWidth = 520 };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        var words = new TextBlock { Text = label, VerticalAlignment = VerticalAlignment.Center };
        var keys = new Button { Content = recording == id ? "Press the keys…" : now.Label, MinWidth = 160 };
        keys.Click += (_, _) =>
        {
            recording = id;
            app.Recording = true;
            app.Hotkeys.ClearAll();   // so the keys reach this window, not the shortcut
            Build();
        };
        var reset = new HyperlinkButton { Content = "Reset", IsEnabled = now != standard };
        reset.Click += (_, _) => Set(id, standard);
        Grid.SetColumn(keys, 1);
        Grid.SetColumn(reset, 2);
        row.Children.Add(words);
        row.Children.Add(keys);
        row.Children.Add(reset);
        return row;
    }

    void OnKey(object sender, Microsoft.UI.Xaml.Input.KeyRoutedEventArgs e)
    {
        if (recording == null)
        {
            // Ctrl+Plus, Ctrl+Minus and Ctrl+0: the text size, as in a browser or Windows' own apps
            if (!Down(VirtualKey.Control) || Down(VirtualKey.Menu)) return;
            float? to = e.Key switch
            {
                VirtualKey.Add or OemPlus => page.ZoomFactor * 1.1f,
                VirtualKey.Subtract or OemMinus => page.ZoomFactor / 1.1f,
                VirtualKey.Number0 or VirtualKey.NumberPad0 => 1f,
                _ => null,
            };
            if (to is { } z) { Zoom(z); e.Handled = true; }
            return;
        }
        var vk = (int)e.Key;
        if (e.Key == VirtualKey.Escape) { recording = null; app.Recording = false; app.RegisterShortcuts(); Build(); e.Handled = true; return; }
        if (KeyCombo.IsModifierKey(vk)) return;
        var mods = 0;
        if (Down(VirtualKey.Control)) mods |= KeyCombo.Control;
        if (Down(VirtualKey.Menu)) mods |= KeyCombo.Alt;
        if (Down(VirtualKey.Shift)) mods |= KeyCombo.Shift;
        if (Down(VirtualKey.LeftWindows) || Down(VirtualKey.RightWindows)) mods |= KeyCombo.Win;
        var combo = new KeyCombo(vk, mods);
        e.Handled = true;
        if (!combo.IsValid) return;   // needs Ctrl, Alt or Win with it
        var id = recording;
        recording = null;
        app.Recording = false;
        Set(id, combo);
    }

    const VirtualKey OemPlus = (VirtualKey)0xBB, OemMinus = (VirtualKey)0xBD;

    static bool Down(VirtualKey k) =>
        Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(k).HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down);

    void Set(string id, KeyCombo combo)
    {
        store.ChangeSettings(s =>
        {
            switch (id)
            {
                case "start": s.StartKey = combo; break;
                case "park": s.ParkKey = combo; break;
                case "yes": s.YesKey = combo; break;
                case "drifted": s.DriftedKey = combo; break;
            }
        });
        app.RegisterShortcuts();
        Build();
    }
}
