using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Windows.Graphics;
using Windows.System;

namespace Chataltul;

/// <summary>
/// A window as Windows 11's own apps have it: Mica, the content up into a tall title bar with the icon and the
/// title, the system's caption buttons in the theme's colors, the pages on the left. The page's text gets bigger or
/// smaller with Ctrl+Plus, Ctrl+Minus, Ctrl+0 and Ctrl+wheel, and wraps at any size. Closing it only hides it.
/// Settings and the app window are each one of these.
/// </summary>
sealed class MicaShell
{
    public Window Window { get; }
    public IntPtr Hwnd { get; }
    public NavigationView Nav { get; }
    /// <summary>The page's content goes in here; it is as wide as the window at any text size.</summary>
    public Grid Holder { get; } = new() { Padding = new Thickness(28, 12, 28, 28), HorizontalAlignment = HorizontalAlignment.Left };
    public ScrollViewer Page { get; } = new()
    {
        ZoomMode = ZoomMode.Enabled, MinZoomFactor = 0.75f, MaxZoomFactor = 2f,
        HorizontalScrollMode = ScrollMode.Disabled, HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
    };
    public bool IsShown { get; private set; }
    /// <summary>The page the person picked on the left, by its id.</summary>
    public event Action<string>? Picked;
    /// <summary>The text size settled at a new size.</summary>
    public event Action<float>? Zoomed;
    /// <summary>A key, before the zoom keys look at it: true if it was taken.</summary>
    public Func<KeyRoutedEventArgs, bool>? BeforeKey { get; set; }
    /// <summary>The window was closed (hidden).</summary>
    public event Action? Hidden;

    readonly Grid root = new();
    readonly ColumnDefinition leftPad = new() { Width = new GridLength(0) }, rightPad = new() { Width = new GridLength(138) };
    readonly TextBlock titleText = new() { FontSize = 12, VerticalAlignment = VerticalAlignment.Center };
    readonly double width, height;
    float zoom = 1;
    bool picking;

    public MicaShell(string title, IEnumerable<(string Id, string Title, string Glyph)> panes, double width, double height, double paneLength = 260)
    {
        this.width = width;
        this.height = height;
        Page.Content = Holder;
        Nav = new NavigationView
        {
            PaneDisplayMode = NavigationViewPaneDisplayMode.Left, IsBackButtonVisible = NavigationViewBackButtonVisible.Collapsed,
            IsSettingsVisible = false, IsPaneToggleButtonVisible = false, OpenPaneLength = paneLength, Content = Page,
            IsTitleBarAutoPaddingEnabled = false,
        };
        foreach (var (id, name, glyph) in panes)
            Nav.MenuItems.Add(new NavigationViewItem { Content = name, Tag = id, Icon = new FontIcon { Glyph = glyph } });
        Nav.SelectionChanged += (_, e) =>
        {
            if (!picking && e.SelectedItem is NavigationViewItem { Tag: string id }) Picked?.Invoke(id);
        };

        // the title row: the icon and the name, the rest of it moves the window, the caption buttons at its right
        titleText.Text = title;
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
        Grid.SetRow(Nav, 1);
        root.Children.Add(titleRow);
        root.Children.Add(Nav);

        Window = new Window { Title = title, Content = root };
        Hwnd = WinRT.Interop.WindowNative.GetWindowHandle(Window);
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
        if (File.Exists(iconFile)) Window.AppWindow.SetIcon(iconFile);

        // the text size: Ctrl and the wheel zoom the page by themselves; the keys are below
        Page.SizeChanged += (_, _) => Reflow();
        Page.ViewChanged += (_, e) =>
        {
            Reflow();
            if (!e.IsIntermediate && Math.Abs(Page.ZoomFactor - zoom) > 0.01) { zoom = Page.ZoomFactor; Zoomed?.Invoke(zoom); }
        };
        Page.Loaded += (_, _) => Page.ChangeView(null, null, zoom, true);
        Nav.PreviewKeyDown += OnKey;
    }

    public ElementTheme Theme { set => root.RequestedTheme = value; }

    /// <summary>The text size it opens at (the saved one).</summary>
    public float StartZoom { set => zoom = Math.Clamp(value, 0.75f, 2f); }

    public bool Maximizable
    {
        set { if (Window.AppWindow.Presenter is OverlappedPresenter p) { p.IsMaximizable = value; p.IsResizable = true; } }
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

    /// <summary>
    /// Room for the caption buttons, which the system draws over the row. Not while minimized (asking then crashes,
    /// as Daf found), and an inset of nothing (at load, or on the way back from minimized) never wipes the room kept.
    /// </summary>
    void FitTitleBar()
    {
        if (Window.AppWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Minimized }) return;
        var scale = root.XamlRoot?.RasterizationScale ?? Native.GetDpiForWindow(Hwnd) / 96.0;
        if (scale <= 0) return;
        var bar = Window.AppWindow.TitleBar;
        if (bar.RightInset <= 0 && bar.LeftInset <= 0) return;
        leftPad.Width = new GridLength(bar.LeftInset / scale);
        rightPad.Width = new GridLength(bar.RightInset / scale);
    }

    /// <summary>The page as wide as the window at any text size, so bigger text wraps rather than running off the side.</summary>
    void Reflow()
    {
        if (Page.ActualWidth <= 0) return;
        Holder.Width = Math.Max(320, Page.ActualWidth / Page.ZoomFactor);
    }

    /// <summary>How tall the page is in its own units (at this text size): for a page that fills the window.</summary>
    public double PageHeight => Page.ActualHeight > 0 ? Page.ActualHeight / Page.ZoomFactor : height - 48;

    /// <summary>The text size, as Ctrl+Plus, Ctrl+Minus and Ctrl+0 change it.</summary>
    public void Zoom(float factor) => Page.ChangeView(null, null, Math.Clamp(factor, 0.75f, 2f));

    public float ZoomFactor => Page.ZoomFactor;

    /// <summary>Marks a page as the one shown, without telling Picked.</summary>
    public void Select(string id)
    {
        picking = true;
        try { Nav.SelectedItem = Nav.MenuItems.Concat(Nav.FooterMenuItems).OfType<NavigationViewItem>().FirstOrDefault(i => i.Tag as string == id); }
        finally { picking = false; }
    }

    public void SetContent(UIElement content)
    {
        Holder.Children.Clear();
        Holder.Children.Add(content);
        Reflow();
    }

    public void Show()
    {
        if (!IsShown)
        {
            var at = Native.Cursor();
            var work = Native.WorkAreaAt(at);
            var scale = Floating.ScaleAt(at);
            int w = Math.Min((int)(width * scale), work.Width), h = Math.Min((int)(height * scale), work.Height);
            var x = work.Left + (work.Width - w) / 2;
            var y = work.Top + (work.Height - h) / 2;
            if (Floating.Offscreen) { x -= 40_000; y -= 40_000; }
            Window.AppWindow.MoveAndResize(new RectInt32(x, y, w, h));
        }
        IsShown = true;
        // off the screen in the self-test, it doesn't take the front from whoever is working
        if (Floating.Offscreen) Window.AppWindow.Show(false);
        else
        {
            Window.Activate();
            if (Window.AppWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Minimized } p) p.Restore();
        }
    }

    public void Hide()
    {
        if (!IsShown) return;
        IsShown = false;
        Window.AppWindow.Hide();
        Hidden?.Invoke();
    }

    void OnKey(object sender, KeyRoutedEventArgs e)
    {
        if (BeforeKey?.Invoke(e) == true) return;
        // Ctrl+Plus, Ctrl+Minus and Ctrl+0: the text size, as in a browser or Windows' own apps
        if (!Down(VirtualKey.Control) || Down(VirtualKey.Menu)) return;
        float? to = e.Key switch
        {
            VirtualKey.Add or OemPlus => Page.ZoomFactor * 1.1f,
            VirtualKey.Subtract or OemMinus => Page.ZoomFactor / 1.1f,
            VirtualKey.Number0 or VirtualKey.NumberPad0 => 1f,
            _ => null,
        };
        if (to is { } z) { Zoom(z); e.Handled = true; }
    }

    const VirtualKey OemPlus = (VirtualKey)0xBB, OemMinus = (VirtualKey)0xBD;

    public static bool Down(VirtualKey k) =>
        Microsoft.UI.Input.InputKeyboardSource.GetKeyStateForCurrentThread(k).HasFlag(Windows.UI.Core.CoreVirtualKeyStates.Down);
}
