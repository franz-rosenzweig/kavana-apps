using System.Runtime.InteropServices;
using Microsoft.UI;
using Microsoft.UI.Composition;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Windows.Foundation;
using Windows.Graphics;

namespace Chataltul;

/// <summary>What is behind a floating window's content.</summary>
enum Backdrop
{
    /// <summary>Nothing: the desktop shows through wherever the content doesn't paint (the cat, the speed bump).</summary>
    Clear,
    /// <summary>Windows 11's acrylic, with the system's rounded corners, border and shadow, as its own flyouts have.</summary>
    Acrylic,
    /// <summary>Only what the content paints.</summary>
    None,
}

/// <summary>
/// A window that floats over everything without a frame: the popover, the cat in the corner, the speed bump,
/// the park field. It stays on top, isn't in the taskbar or Alt+Tab, and the ones that mustn't take the keyboard
/// (the cat in the corner) never become the active window. A clear one is see-through where nothing is drawn,
/// and where it has shapes, clicks outside them go through to whatever is underneath. The Mac's FloatingPanel.
/// </summary>
sealed class Floating
{
    /// <summary>The self-test keeps every window far off the screen, so nothing shows on the desk of whoever runs it.</summary>
    public static bool Offscreen;
    const int Away = 40_000;

    public Window Window { get; }
    public IntPtr Hwnd { get; }
    public AppWindow AppWindow => Window.AppWindow;
    /// <summary>Everything in the window sits in here; it's see-through.</summary>
    public Grid Root { get; } = new() { Background = new SolidColorBrush(Colors.Transparent) };
    public bool IsShown { get; private set; }
    readonly bool activates;

    /// <summary>The window lost the front (only for the ones that take it).</summary>
    public event Action? Deactivated;
    /// <summary>Alt+F4 does nothing at all to it (the speed bump has no way out but its own).</summary>
    public bool KeepOnClose { get; set; }

    public Floating(string title, bool activates, Backdrop backdrop = Backdrop.Clear)
    {
        this.activates = activates;
        Window = new Window { Title = title, Content = Root };
        Hwnd = WinRT.Interop.WindowNative.GetWindowHandle(Window);
        if (AppWindow.Presenter is OverlappedPresenter p)
        {
            p.SetBorderAndTitleBar(false, false);
            p.IsResizable = false;
            p.IsMaximizable = false;
            p.IsMinimizable = false;
            p.IsAlwaysOnTop = true;
        }
        AppWindow.IsShownInSwitchers = false;
        var ex = Native.ExStyle(Hwnd) | Native.WS_EX_TOOLWINDOW;
        ex &= ~Native.WS_EX_APPWINDOW;
        if (!activates) ex |= Native.WS_EX_NOACTIVATE;
        Native.SetExStyle(Hwnd, ex);
        if (backdrop == Backdrop.Acrylic)
        {
            // a flyout: Windows 11 rounds it, draws its border and its shadow
            Window.SystemBackdrop = new DesktopAcrylicBackdrop();
            var round = Native.DWMWCP_ROUND;
            Native.DwmSetWindowAttribute(Hwnd, Native.DWMWA_WINDOW_CORNER_PREFERENCE, ref round, sizeof(int));
        }
        else
        {
            // no rounded corners or border from Windows 11: what's drawn has its own
            var corner = Native.DWMWCP_DONOTROUND;
            Native.DwmSetWindowAttribute(Hwnd, Native.DWMWA_WINDOW_CORNER_PREFERENCE, ref corner, sizeof(int));
            var border = Native.DWMWA_COLOR_NONE;
            Native.DwmSetWindowAttribute(Hwnd, Native.DWMWA_BORDER_COLOR, ref border, sizeof(uint));
            if (backdrop == Backdrop.Clear) Window.SystemBackdrop = new ClearBackdrop();
        }
        AppWindow.Closing += (_, e) => { e.Cancel = true; if (!KeepOnClose) Hide(); };
        Window.Activated += (_, e) =>
        {
            if (e.WindowActivationState == WindowActivationState.Deactivated && IsShown) Deactivated?.Invoke();
        };
    }

    /// <summary>What the window shows, its size in DIPs worked out from it.</summary>
    public FrameworkElement? Content
    {
        get => Root.Children.Count > 0 ? Root.Children[0] as FrameworkElement : null;
        set
        {
            Root.Children.Clear();
            if (value != null) Root.Children.Add(value);
        }
    }

    /// <summary>
    /// Runs <c>place</c> again once WinUI has laid the window out: Windows' buttons and boxes only take their full
    /// size when their templates arrive, after the window is on the screen, so a size measured before is short.
    /// </summary>
    public void Refit(Action place)
    {
        Ui.After(TimeSpan.FromMilliseconds(60), () => { if (IsShown) place(); });
        Ui.After(TimeSpan.FromMilliseconds(250), () => { if (IsShown) place(); });
    }

    /// <summary>The size the content wants at this width (DIPs).</summary>
    public Size Measure(double width)
    {
        if (Content is not { } c) return new Size(width, 100);
        c.Measure(new Size(width, double.PositiveInfinity));
        var s = c.DesiredSize;
        return s.Height <= 0 ? new Size(width, 100) : new Size(Math.Max(s.Width, width), s.Height);
    }

    /// <summary>The scale of the screen at this point (1 at 96 dpi).</summary>
    public static double ScaleAt(Native.POINT p)
    {
        var monitor = Native.MonitorFromPoint(p, Native.MONITOR_DEFAULTTONEAREST);
        return GetDpiForMonitor(monitor, 0, out var dpi, out _) == 0 && dpi > 0 ? dpi / 96.0 : Native.GetDpiForSystem() / 96.0;
    }

    [DllImport("shcore.dll")] static extern int GetDpiForMonitor(IntPtr monitor, int type, out uint x, out uint y);

    /// <summary>Puts the window here, in pixels (far off the screen in the self-test).</summary>
    public void Move(int x, int y, int width, int height)
    {
        if (Offscreen) { x -= Away; y -= Away; }
        AppWindow.MoveAndResize(new RectInt32(x, y, Math.Max(1, width), Math.Max(1, height)));
    }

    /// <summary>The window's place on the screen, in pixels, as it would be without the self-test's offset.</summary>
    public Native.RECT Bounds
    {
        get
        {
            Native.GetWindowRect(Hwnd, out var r);
            if (Offscreen) { r.Left += Away; r.Right += Away; r.Top += Away; r.Bottom += Away; }
            return r;
        }
    }

    public void Show()
    {
        // off the screen in the self-test, nothing takes the front from whoever is working
        if (activates && !Offscreen)
        {
            AppWindow.Show(true);
            Window.Activate();
            Native.SetForegroundWindow(Hwnd);
        }
        else AppWindow.Show(false);
        // above other windows that float too, without taking the front
        Native.SetWindowPos(Hwnd, Native.HWND_TOPMOST, 0, 0, 0, 0, Native.SWP_NOMOVE | Native.SWP_NOSIZE | Native.SWP_NOACTIVATE);
        IsShown = true;
    }

    public void Hide()
    {
        if (!IsShown) return;
        IsShown = false;
        AppWindow.Hide();
    }

    /// <summary>
    /// Only these rounded rectangles (DIPs, from the window's top left) take clicks; anywhere else in the window
    /// a click goes through to the app underneath, as if the window weren't there.
    /// </summary>
    public void SetShapes(IEnumerable<(Rect Rect, double Radius)> shapes, double scale)
    {
        IntPtr all = Native.CreateRectRgn(0, 0, 0, 0);
        foreach (var (r, radius) in shapes)
        {
            var piece = Native.CreateRoundRectRgn((int)Math.Floor(r.X * scale), (int)Math.Floor(r.Y * scale),
                (int)Math.Ceiling(r.Right * scale) + 1, (int)Math.Ceiling(r.Bottom * scale) + 1,
                (int)Math.Round(radius * 2 * scale), (int)Math.Round(radius * 2 * scale));
            Native.CombineRgn(all, all, piece, Native.RGN_OR);
            Native.DeleteObject(piece);
        }
        if (Native.SetWindowRgn(Hwnd, all, true) == 0) Native.DeleteObject(all);   // once set, Windows owns it
    }

    /// <summary>The whole window takes clicks again.</summary>
    public void ClearShapes() => Native.SetWindowRgn(Hwnd, IntPtr.Zero, true);

    /// <summary>Where an element is in the window, in DIPs.</summary>
    public Rect BoundsOf(FrameworkElement e) =>
        e.TransformToVisual(Root).TransformBounds(new Rect(0, 0, e.ActualWidth, e.ActualHeight));
}

/// <summary>
/// A backdrop that is nothing at all: the window shows what's behind it wherever its content doesn't paint.
/// (WinUI has Mica and Acrylic; a see-through window takes a clear brush of the system compositor.)
/// </summary>
sealed class ClearBackdrop : SystemBackdrop
{
    static Windows.UI.Composition.Compositor? compositor;
    static object? queueController;

    protected override void OnTargetConnected(ICompositionSupportsSystemBackdrop target, XamlRoot root)
    {
        base.OnTargetConnected(target, root);
        EnsureSystemQueue();
        compositor ??= new Windows.UI.Composition.Compositor();
        target.SystemBackdrop = compositor.CreateColorBrush(Windows.UI.Color.FromArgb(0, 0, 0, 0));
    }

    /// <summary>Light or dark, active or not, a clear backdrop stays clear (the base one would throw: it has no controller).</summary>
    protected override void OnDefaultSystemBackdropConfigurationChanged(ICompositionSupportsSystemBackdrop target, XamlRoot xamlRoot) { }

    protected override void OnTargetDisconnected(ICompositionSupportsSystemBackdrop target)
    {
        base.OnTargetDisconnected(target);
        target.SystemBackdrop = null;
    }

    [StructLayout(LayoutKind.Sequential)]
    struct DispatcherQueueOptions { public int dwSize, threadType, apartmentType; }

    [DllImport("CoreMessaging.dll")]
    static extern int CreateDispatcherQueueController(DispatcherQueueOptions options, [MarshalAs(UnmanagedType.IUnknown)] out object controller);

    /// <summary>The system compositor needs Windows' own dispatcher queue on this thread, next to WinUI's.</summary>
    static void EnsureSystemQueue()
    {
        if (Windows.System.DispatcherQueue.GetForCurrentThread() != null || queueController != null) return;
        var o = new DispatcherQueueOptions { dwSize = Marshal.SizeOf<DispatcherQueueOptions>(), threadType = 2, apartmentType = 2 };
        CreateDispatcherQueueController(o, out queueController);
    }
}
