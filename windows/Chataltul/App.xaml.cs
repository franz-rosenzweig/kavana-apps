using Microsoft.UI.Xaml;

namespace Chataltul;

/// <summary>
/// Chataltul for Windows: a kitten in the notification area, a timer, check-ins, and a cat that walks over
/// the apps and sites that distract you. The web's engine keeps the rules (Store); Controller puts the
/// pieces together. The Mac's is mac/Chataltul/App.
/// </summary>
public partial class App : Application
{
    public static Options Options { get; set; } = Options.Parse([]);

    /// <summary>The doc, the settings and the log: %LOCALAPPDATA%\Chataltul, or --data for the self-test.</summary>
    public static string DataDir { get; private set; } = "";

    public static string Version { get; } =
        typeof(App).Assembly.GetName().Version is { } v ? $"{v.Major}.{v.Minor}.{v.Build}" : "0";

    internal static Controller? Controller { get; private set; }

    public App()
    {
        InitializeComponent();
        UnhandledException += (_, e) =>
        {
            Log.Write($"unhandled: {e.Exception}");
            e.Handled = true;
        };
        AppDomain.CurrentDomain.UnhandledException += (_, e) => Log.Write($"unhandled (domain): {e.ExceptionObject}");
        TaskScheduler.UnobservedTaskException += (_, e) => { Log.Write($"unobserved: {e.Exception}"); e.SetObserved(); };
    }

    protected override void OnLaunched(LaunchActivatedEventArgs args)
    {
        // a tray app: closing its windows doesn't end it, only Quit does
        DispatcherShutdownMode = DispatcherShutdownMode.OnExplicitShutdown;
        DataDir = Options.Data ?? Path.Join(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Chataltul");
        Directory.CreateDirectory(DataDir);
        Log.Write($"Chataltul {App.Version} starting{(Options.SelfTest ? " (self-test)" : "")}");
        if (Options.SelfTest)
        {
            Floating.Offscreen = !Options.OnScreen;
            CatCorner.Still = true;
            CafeView.Still = true;
            Palette.Force = false;
        }
        Store store;
        try { store = new Store(DataDir, Path.Join(AppContext.BaseDirectory, "js")); }
        catch (Exception e)
        {
            Log.Write($"can't start: {e}");
            if (Options.SelfTest) SelfTest.FailEarly(e.ToString());
            else Native.MessageBox(IntPtr.Zero, $"Chataltul can't start.\n\n{e.Message}", "Chataltul", 0x10);
            Exit();
            return;
        }
        Controller = new Controller(store);
        Controller.Start(firstShow: !Options.Login && !Options.SelfTest);
        if (Options.SelfTest) _ = SelfTest.Run(Controller);
        if (Options.QuitAfter is { } seconds)
        {
            var t = Microsoft.UI.Dispatching.DispatcherQueue.GetForCurrentThread().CreateTimer();
            t.Interval = TimeSpan.FromSeconds(seconds);
            t.IsRepeating = false;
            t.Tick += (_, _) => { Log.Write("LAUNCH ok"); Controller.Quit(force: true); };
            t.Start();
        }
    }
}
