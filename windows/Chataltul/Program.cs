using Microsoft.UI.Dispatching;

namespace Chataltul;

/// <summary>
/// The start: one Chataltul at a time. A second start (from the Start menu while it already runs in the
/// notification area) only asks the first to open its popover, and leaves.
/// </summary>
public static class Program
{
    const string MutexName = @"Local\xyz.tiresia.chataltul";

    [STAThread]
    static int Main(string[] args)
    {
        var options = Options.Parse(args);
        Mutex? one = null;
        if (!options.SelfTest)
        {
            one = new Mutex(true, MutexName, out var first);
            if (!first)
            {
                MessageWindow.WakeTheOther();
                return 0;
            }
        }
        App.Options = options;
        WinRT.ComWrappersSupport.InitializeComWrappers();
        Microsoft.UI.Xaml.Application.Start(p =>
        {
            SynchronizationContext.SetSynchronizationContext(new DispatcherQueueSynchronizationContext(DispatcherQueue.GetForCurrentThread()));
            new App();
        });
        GC.KeepAlive(one);
        return 0;
    }
}
