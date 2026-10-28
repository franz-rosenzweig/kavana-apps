using Microsoft.UI.Text;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Imaging;
using Microsoft.UI.Xaml.Shapes;
using Windows.Foundation;
using Windows.UI;
using Windows.UI.ViewManagement;
using FontWeight = Windows.UI.Text.FontWeight;

namespace Chataltul;

/// <summary>
/// The look of Windows 11's own tray apps: its text, fills and strokes in light and dark, the accent the person
/// chose, the type sizes (Caption 12, Body 14, Subtitle 20, Title 28). The paintings, the sky in the round window
/// and the kitten's ring are Chataltul's own. The windows are built again when the theme changes, so a color is
/// read from here each time.
/// </summary>
static class Palette
{
    public static bool Dark { get; set; }
    /// <summary>The self-test draws every screen in light and in dark, whatever this PC is set to.</summary>
    public static bool? Force { get; set; }

    static readonly UISettings windowsColors = new();

    /// <summary>Is Windows set to dark for apps?</summary>
    public static bool SystemDark()
    {
        if (Force is { } f) return f;
        using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        return k?.GetValue("AppsUseLightTheme") is int i && i == 0;
    }

    /// <summary>Is the taskbar dark? (Windows 11's default is a dark taskbar even with light apps.)</summary>
    public static bool TaskbarDark()
    {
        using var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize");
        return k?.GetValue("SystemUsesLightTheme") is not int i || i == 0;
    }

    public static ElementTheme Theme => Dark ? ElementTheme.Dark : ElementTheme.Light;

    public static Color C(uint rgb, double alpha = 1) =>
        Color.FromArgb((byte)Math.Round(alpha * 255), (byte)(rgb >> 16 & 0xFF), (byte)(rgb >> 8 & 0xFF), (byte)(rgb & 0xFF));

    static Color D(uint light, double lightAlpha, uint dark, double darkAlpha) => Dark ? C(dark, darkAlpha) : C(light, lightAlpha);

    /// <summary>The person's accent, as Windows draws accent fills: darker on light, lighter on dark.</summary>
    public static Color Accent
    {
        get
        {
            try { return windowsColors.GetColorValue(Dark ? UIColorType.AccentLight2 : UIColorType.AccentDark1); }
            catch (Exception) { return Dark ? C(0x60CDFF) : C(0x005FB8); }
        }
    }

    public static Color Text => D(0x000000, 0.894, 0xFFFFFF, 1);
    public static Color Text2 => D(0x000000, 0.62, 0xFFFFFF, 0.786);
    public static Color Text3 => D(0x000000, 0.447, 0xFFFFFF, 0.545);
    /// <summary>A card on an acrylic or Mica window (CardBackgroundFillColorDefault).</summary>
    public static Color Layer => D(0xFFFFFF, 0.7, 0xFFFFFF, 0.05);
    /// <summary>A card on its own over the desktop, like a notification: solid.</summary>
    public static Color Surface => D(0xF9F9F9, 1, 0x2C2C2C, 1);
    public static Color Stroke => D(0x000000, 0.08, 0xFFFFFF, 0.09);
    public static Color Divider => D(0x000000, 0.08, 0xFFFFFF, 0.08);
    /// <summary>Windows' caution color: a paused block.</summary>
    public static Color Caution => D(0x9D5D00, 1, 0xFCE100, 1);
    public static Color RingTrack => D(0x000000, 0.09, 0xFFFFFF, 0.12);
    /// <summary>The frame of the round window.</summary>
    public static Color Rim => D(0xFFFFFF, 1, 0x454545, 1);
    /// <summary>The dim over the screen behind the speed bump.</summary>
    public static Color Scrim => C(0x000000, 0.42);

    public static (Color Top, Color Bottom) SkyDay => (C(0x8FCDF0), C(0xE7F6FF));
    public static (Color Top, Color Bottom) SkyDusk => (C(0x9FD2EE), C(0xFDF0D8));
    public static (Color Top, Color Bottom) SkyNight => (C(0x243A66), C(0x454F7E));
    public static (Color Top, Color Bottom) SkyDeep => (C(0x141A3E), C(0x2B2E5E));

    public static SolidColorBrush B(Color c) => new(c);
}

/// <summary>Small builders for the views, so each screen reads like the Mac's SwiftUI, in Windows' own controls.</summary>
static class V
{
    public static FontFamily Symbols => (FontFamily)Application.Current.Resources["SymbolThemeFontFamily"];

    public static class Glyph
    {
        public const string Play = "", Pause = "", Stop = "", Settings = "", Bulb = "",
            Check = "", Drift = "", Lock = "", Close = "", Add = "", Delete = "", Down = "";
    }

    /// <summary>Text. <c>color</c> null: the theme's own text color, inherited (so it follows a button's).</summary>
    public static TextBlock T(string text, double size = 14, FontWeight? weight = null, Color? color = null, bool wrap = false,
        TextAlignment align = TextAlignment.Left)
    {
        var t = new TextBlock
        {
            Text = text,
            FontSize = size,
            FontWeight = weight ?? FontWeights.Normal,
            TextWrapping = wrap ? TextWrapping.WrapWholeWords : TextWrapping.NoWrap,
            TextTrimming = wrap ? TextTrimming.None : TextTrimming.CharacterEllipsis,
            TextAlignment = align,
        };
        if (color is { } c) t.Foreground = Palette.B(c);
        return t;
    }

    public static TextBlock Caption(string text, bool wrap = false, TextAlignment align = TextAlignment.Left) =>
        T(text, 12, color: Palette.Text2, wrap: wrap, align: align);

    public static TextBlock Body(string text, bool wrap = false, Color? color = null, TextAlignment align = TextAlignment.Left) =>
        T(text, 14, color: color ?? Palette.Text, wrap: wrap, align: align);

    public static TextBlock Strong(string text, bool wrap = false, Color? color = null) => T(text, 14, FontWeights.SemiBold, color ?? Palette.Text, wrap);

    public static TextBlock Subtitle(string text, bool wrap = true, TextAlignment align = TextAlignment.Left) =>
        T(text, 20, FontWeights.SemiBold, Palette.Text, wrap, align);

    /// <summary>A small section label: Today, Focusing, Ready.</summary>
    public static TextBlock Label(string text, Color? color = null) => T(text, 12, FontWeights.SemiBold, color ?? Palette.Text2);

    /// <summary>The clock's face: Windows' display face, digits of one width.</summary>
    public static TextBlock ClockText(string text, double size, Color? color = null)
    {
        var t = T(text, size, FontWeights.SemiBold, color ?? Palette.Text, align: TextAlignment.Center);
        t.FontFamily = new FontFamily("Segoe UI Variable Display, Segoe UI");
        t.HorizontalAlignment = HorizontalAlignment.Center;
        return t;
    }

    public static FontIcon Icon(string glyph, double size = 14, Color? color = null)
    {
        var i = new FontIcon { Glyph = glyph, FontSize = size, FontFamily = Symbols };
        if (color is { } c) i.Foreground = Palette.B(c);
        return i;
    }

    public static StackPanel Col(double spacing, params UIElement?[] children)
    {
        var s = new StackPanel { Orientation = Orientation.Vertical, Spacing = spacing };
        foreach (var c in children) if (c != null) s.Children.Add(c);
        return s;
    }

    public static StackPanel Row(double spacing, params UIElement?[] children)
    {
        var s = new StackPanel { Orientation = Orientation.Horizontal, Spacing = spacing, VerticalAlignment = VerticalAlignment.Center };
        foreach (var c in children) if (c != null) s.Children.Add(c);
        return s;
    }

    /// <summary>Children side by side, each taking an equal share of the width.</summary>
    public static Grid Even(double spacing, params UIElement[] children)
    {
        var g = new Grid { ColumnSpacing = spacing };
        for (var i = 0; i < children.Length; i++)
        {
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            Grid.SetColumn((FrameworkElement)children[i], i);
            g.Children.Add(children[i]);
        }
        return g;
    }

    /// <summary>A row with something on the left and something on the right (and, if given, something centered).</summary>
    public static Grid Spread(UIElement left, UIElement? right, UIElement? middle = null)
    {
        var g = new Grid { ColumnSpacing = 8 };
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        g.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        ((FrameworkElement)left).VerticalAlignment = VerticalAlignment.Center;
        g.Children.Add(left);
        if (right != null)
        {
            Grid.SetColumn((FrameworkElement)right, 1);
            ((FrameworkElement)right).VerticalAlignment = VerticalAlignment.Center;
            g.Children.Add(right);
        }
        if (middle != null)
        {
            Grid.SetColumnSpan((FrameworkElement)middle, 2);
            ((FrameworkElement)middle).HorizontalAlignment = HorizontalAlignment.Center;
            ((FrameworkElement)middle).VerticalAlignment = VerticalAlignment.Center;
            g.Children.Add(middle);
        }
        return g;
    }

    /// <summary>A Windows button: the standard one, or with <c>accent</c> the accent-filled one (the one to press).</summary>
    public static Button Btn(object content, Action click, bool accent = false, bool stretch = false, string? name = null,
        Thickness? padding = null, double? height = null)
    {
        var b = new Button
        {
            Content = content,
            HorizontalAlignment = stretch ? HorizontalAlignment.Stretch : HorizontalAlignment.Left,
            HorizontalContentAlignment = HorizontalAlignment.Center,
        };
        if (accent) b.Style = (Style)Application.Current.Resources["AccentButtonStyle"];
        if (padding is { } p) b.Padding = p;
        if (height is { } h) b.Height = h;
        var label = name ?? content as string;
        if (label != null) Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(b, label);
        b.Click += (_, _) => click();
        return b;
    }

    /// <summary>A glyph-only button with no fill until the pointer is on it, as in a flyout's header.</summary>
    public static Button IconBtn(string glyph, Action click, string name, double size = 32)
    {
        var b = new Button
        {
            Content = Icon(glyph, 14), Width = size, Height = size, Padding = new Thickness(0),
            Background = Palette.B(Microsoft.UI.Colors.Transparent), BorderBrush = Palette.B(Microsoft.UI.Colors.Transparent),
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(b, name);
        ToolTipService.SetToolTip(b, name);
        b.Click += (_, _) => click();
        return b;
    }

    /// <summary>A glyph and a word, for a button's content: both take the button's text color.</summary>
    public static StackPanel Labelled(string glyph, string text) =>
        Row(8, Icon(glyph, 12), new TextBlock { Text = text, VerticalAlignment = VerticalAlignment.Center });

    public static HyperlinkButton Link(string text, Action click)
    {
        var l = new HyperlinkButton { Content = text, Padding = new Thickness(4, 2, 4, 2), MinWidth = 0, MinHeight = 0, FontSize = 12 };
        l.Click += (_, _) => click();
        return l;
    }

    /// <summary>A card on an acrylic or Mica window, as Windows groups things (quick settings, Settings' rows).</summary>
    public static Border Card(UIElement child, Thickness? padding = null) => new()
    {
        Child = child,
        CornerRadius = new CornerRadius(8),
        Background = Palette.B(Palette.Layer),
        BorderBrush = Palette.B(Palette.Stroke),
        BorderThickness = new Thickness(1),
        Padding = padding ?? new Thickness(12),
    };

    /// <summary>A card standing on its own over the desktop, like a notification.</summary>
    public static Border Surface(UIElement child, Thickness? padding = null) => new()
    {
        Child = child,
        CornerRadius = new CornerRadius(8),
        Background = Palette.B(Palette.Surface),
        BorderBrush = Palette.B(Palette.Stroke),
        BorderThickness = new Thickness(1),
        Padding = padding ?? new Thickness(16),
    };

    public static Rectangle Divider() => new() { Height = 1, Fill = Palette.B(Palette.Divider), HorizontalAlignment = HorizontalAlignment.Stretch };

    public static TextBox Field(string placeholder, string text, double size = 14, bool center = false) => new()
    {
        PlaceholderText = placeholder,
        Text = text,
        FontSize = size,
        TextAlignment = center ? TextAlignment.Center : TextAlignment.Left,
        HorizontalAlignment = HorizontalAlignment.Stretch,
    };

    /// <summary>Focus, Break and Long side by side, the chosen one filled with the accent, as a segmented control.</summary>
    public static ToggleButton Segment(object content, bool on, Action click, string name)
    {
        var b = new ToggleButton
        {
            Content = content, IsChecked = on, HorizontalAlignment = HorizontalAlignment.Stretch,
            HorizontalContentAlignment = HorizontalAlignment.Center, Padding = new Thickness(0, 5, 0, 6),
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(b, name);
        b.Click += (_, _) => click();
        return b;
    }

    /// <summary>The dashes under the title: one for each block of the day, the running one filling.</summary>
    public static Grid Dashes(int total, int done, double? current = null)
    {
        var n = Math.Max(total, done + (current == null ? 0 : 1));
        var g = new Grid { ColumnSpacing = 4, Height = 4 };
        for (var i = 0; i < n; i++)
        {
            g.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var track = new Grid();
            track.Children.Add(new Border { CornerRadius = new CornerRadius(2), Background = Palette.B(Palette.RingTrack) });
            double share = i < done ? 1 : i == done && current is { } c ? Math.Clamp(c, 0, 1) : 0;
            if (share > 0)
            {
                var fill = new Grid();
                fill.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(share, GridUnitType.Star) });
                fill.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(Math.Max(0.0001, 1 - share), GridUnitType.Star) });
                fill.Children.Add(new Border { CornerRadius = new CornerRadius(2), Background = Palette.B(Palette.Accent) });
                track.Children.Add(fill);
            }
            Grid.SetColumn(track, i);
            g.Children.Add(track);
        }
        return g;
    }

    /// <summary>A ring that empties: a circle's arc, clockwise from the top.</summary>
    public static Microsoft.UI.Xaml.Shapes.Path Arc(double diameter, double thickness, double fraction, Color color)
    {
        var p = new Microsoft.UI.Xaml.Shapes.Path
        {
            Stroke = Palette.B(color), StrokeThickness = thickness,
            StrokeStartLineCap = PenLineCap.Round, StrokeEndLineCap = PenLineCap.Round,
            Width = diameter, Height = diameter,
        };
        SetArc(p, diameter, thickness, fraction);
        return p;
    }

    public static void SetArc(Microsoft.UI.Xaml.Shapes.Path p, double diameter, double thickness, double fraction)
    {
        var r = (diameter - thickness) / 2;
        var c = diameter / 2;
        var f = Math.Clamp(fraction, 0.001, 0.9999);
        var a = -Math.PI / 2 + 2 * Math.PI * f;
        var figure = new PathFigure { StartPoint = new Point(c, c - r), IsClosed = false, IsFilled = false };
        figure.Segments.Add(new ArcSegment
        {
            Point = new Point(c + r * Math.Cos(a), c + r * Math.Sin(a)),
            Size = new Size(r, r),
            SweepDirection = SweepDirection.Clockwise,
            IsLargeArc = f > 0.5,
        });
        var geometry = new PathGeometry();
        geometry.Figures.Add(figure);
        p.Data = geometry;
    }

    /// <summary>A circle's outline.</summary>
    public static Ellipse Ring(double diameter, double thickness, Color color) => new()
    {
        Width = diameter, Height = diameter, Stroke = Palette.B(color), StrokeThickness = thickness,
    };

    static readonly Dictionary<string, BitmapImage> Pictures = [];

    /// <summary>A painting by the web's path (cats/miso-1.webp), as the app carries it, decoded once and kept.</summary>
    public static BitmapImage? Picture(string webPath)
    {
        if (Pictures.TryGetValue(webPath, out var b)) return b;
        var file = System.IO.Path.Join(AppContext.BaseDirectory, "art", Core.Art.File(webPath));
        if (!File.Exists(file)) return null;
        b = new BitmapImage(new Uri(file));
        Pictures[webPath] = b;
        return b;
    }

    /// <summary>Chataltul's own icon, as a notification shows the app's.</summary>
    public static Image? AppIcon(double size)
    {
        var file = System.IO.Path.Join(AppContext.BaseDirectory, "Assets", "Chataltul.ico");
        return File.Exists(file) ? new Image { Source = new BitmapImage(new Uri(file)), Width = size, Height = size } : null;
    }

    public static Image Painting(string webPath, double size) => new()
    {
        Source = Picture(webPath), Width = size, Height = size, Stretch = Stretch.Uniform,
    };

    public static LinearGradientBrush Vertical((Color Top, Color Bottom) c) => new()
    {
        StartPoint = new Point(0.5, 0), EndPoint = new Point(0.5, 1),
        GradientStops = { new GradientStop { Color = c.Top, Offset = 0 }, new GradientStop { Color = c.Bottom, Offset = 1 } },
    };
}
