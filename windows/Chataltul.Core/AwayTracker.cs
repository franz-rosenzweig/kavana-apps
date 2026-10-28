namespace Chataltul.Core;

/// <summary>The PC was left alone from <c>From</c> to <c>To</c> in the middle of a block.</summary>
public sealed record AwayReturn(long From, long To)
{
    public long Ms => To - From;
    public int Minutes => Math.Max(1, (int)Math.Round(Ms / 60000.0, MidpointRounding.AwayFromZero));
}

/// <summary>
/// "You were away from the PC": more than two minutes without a key or a click in the middle of a block, or
/// the PC asleep or locked that long. Being away is not drifting: nothing happens to the kitten, the person is
/// only asked whether those minutes count. Pure: the app feeds it the idle time and the sleep, wake, lock and
/// unlock moments. The Mac's AwayTracker.swift, unchanged.
/// </summary>
public sealed class AwayTracker(long thresholdMs = 120_000)
{
    public long ThresholdMs { get; set; } = thresholdMs;
    long? since;
    bool offline;

    public bool IsAway => since != null;

    /// <summary>The PC went to sleep or the screen locked.</summary>
    public void OfflineNow(long now, long idleMs)
    {
        since ??= now - Math.Max(0, idleMs);
        offline = true;
    }

    /// <summary>It woke or unlocked. The return is noticed with the next sample that shows activity.</summary>
    public void OnlineNow() => offline = false;

    /// <summary>
    /// A look at the idle time (since the last key or click, in ms). <c>running</c>: a block is running and not
    /// paused. Returns the away span when the person is back and it counts.
    /// </summary>
    public AwayReturn? Sample(long idleMs, long now, bool running)
    {
        if (offline) return null;
        if (idleMs >= ThresholdMs)
        {
            since ??= now - idleMs;
            return null;
        }
        if (since is not { } s) return null;
        since = null;
        var to = now - Math.Max(0, idleMs);
        if (!running || to - s < ThresholdMs) return null;
        return new AwayReturn(s, to);
    }
}
