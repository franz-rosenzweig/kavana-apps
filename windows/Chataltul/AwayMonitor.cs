using Chataltul.Core;
using Microsoft.Windows.AppNotifications;
using Microsoft.Windows.AppNotifications.Builder;

namespace Chataltul;

/// <summary>
/// Watches for the PC being left alone in the middle of a block: no key or click for the set time, or the
/// screen locked, the display off or the PC asleep. The rules are AwayTracker's; this feeds it.
/// </summary>
sealed class AwayMonitor
{
    readonly Store store;
    readonly AwayTracker tracker;
    /// <summary>A way to fake the idle time, for the self-test (the real idle time of whoever runs it must not leak in).</summary>
    public Func<long>? IdleOverride { get; set; }

    public AwayMonitor(Store store, MessageWindow window)
    {
        this.store = store;
        tracker = new AwayTracker(store.Settings.AwayAfterMin * 60_000L);
        window.Offline += off =>
        {
            if (off) Offline();
            else tracker.OnlineNow();
        };
    }

    public long IdleMs() => IdleOverride?.Invoke() ?? Native.IdleMs();

    public void Offline()
    {
        if (!store.Running) return;
        tracker.OfflineNow(Clock.Now, IdleMs());
    }

    public void Online() => tracker.OnlineNow();

    /// <summary>Every couple of seconds while a block runs.</summary>
    public void Sample(long now)
    {
        tracker.ThresholdMs = Math.Max(1, store.Settings.AwayAfterMin) * 60_000L;
        if (tracker.Sample(IdleMs(), now, store.Running) is not { } back) return;
        switch (store.Settings.AwayAction)
        {
            case AwayAction.Count: break;
            case AwayAction.TakeOff when store.State.CanTakeOff:
                store.Act("takeOff", new() { ["from"] = back.From, ["to"] = back.To });
                break;
            default:
                store.SetAwayPrompt(back);
                break;
        }
    }
}

/// <summary>
/// Check-ins and the end of a block as Windows notifications: the way they reach the person when the cat isn't
/// on screen (the cat is off, or nobody answered it). Yes and Drifted are on the notification. The Mac schedules
/// its notifications ahead; a tray app is running anyway, so these are shown when the ping comes due.
/// </summary>
sealed class Notifier
{
    readonly bool on;
    /// <summary>Yes or Drifted pressed on a notification: the block, the check-in's number, the answer.</summary>
    public event Action<string, int, string>? Answer;
    public event Action? Open;
    /// <summary>What would have been shown, for the self-test (which shows none).</summary>
    public readonly List<string> Posted = [];

    public Notifier(bool enabled)
    {
        if (!enabled) return;
        try
        {
            var m = AppNotificationManager.Default;
            m.NotificationInvoked += (_, e) =>
            {
                var a = e.Arguments;
                Ui.Post(() =>
                {
                    if (a.TryGetValue("answer", out var answer) && a.TryGetValue("block", out var block) && a.TryGetValue("n", out var n) && int.TryParse(n, out var num))
                        Answer?.Invoke(block, num, answer);
                    else Open?.Invoke();
                });
            };
            m.Register();
            on = true;
        }
        catch (Exception e) { Log.Write($"notifications aren't there: {e.Message}"); }
    }

    /// <summary>A notification now. A check-in's has Yes and Drifted.</summary>
    public void Post(string title, string body, string? block = null, int? n = null)
    {
        Posted.Add(title);
        if (!on) return;
        try
        {
            var b = new AppNotificationBuilder().AddText(title).AddText(body).SetGroup("chataltul");
            if (block != null && n is { } num)
            {
                b.SetTag($"checkin-{block}-{num}");
                b.AddButton(new AppNotificationButton("Yes").AddArgument("answer", "yes").AddArgument("block", block).AddArgument("n", num.ToString()));
                b.AddButton(new AppNotificationButton("Drifted").AddArgument("answer", "drifted").AddArgument("block", block).AddArgument("n", num.ToString()));
            }
            AppNotificationManager.Default.Show(b.BuildNotification());
        }
        catch (Exception e) { Log.Write($"notification: {e.Message}"); }
    }

    /// <summary>Once the check-in has been answered elsewhere, its notification goes.</summary>
    public void ClearAll()
    {
        if (!on) return;
        try { _ = AppNotificationManager.Default.RemoveAllAsync(); }
        catch (Exception e) { Log.Write($"notifications: {e.Message}"); }
    }

    public void Unregister()
    {
        if (!on) return;
        try { AppNotificationManager.Default.Unregister(); } catch (Exception) { }
    }
}
