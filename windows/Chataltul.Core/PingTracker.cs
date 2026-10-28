namespace Chataltul.Core;

/// <summary>
/// The engine lists the pings still to come (E.pings). A ping whose time came leaves the list, and it's then
/// due: this finds those between one look and the next, the way the Android app's Repo.after() does.
/// </summary>
public sealed class PingTracker
{
    List<Ping> before = [];

    /// <summary>
    /// The next list of pings; returns the ones that came due since the last, oldest first. Several of a kind
    /// at once (the PC was asleep) become the last of them, and an old check-in or nudge is no use any more.
    /// The end of a block is always told.
    /// </summary>
    public List<Ping> Update(List<Ping> now, long time)
    {
        var still = now.Select(p => p.Key).ToHashSet();
        var due = before.Where(p => p.At <= time + 1500 && !still.Contains(p.Key)).ToList();
        before = now;
        var lastOfKind = new Dictionary<string, Ping>();
        foreach (var p in due) lastOfKind[p.Kind] = p;
        return lastOfKind.Values
            .Where(p => p.Kind == "end" || time - p.At < 10 * 60_000)
            .OrderBy(p => p.At)
            .ToList();
    }

    /// <summary>The soonest ping still to come, for the clock that wakes the engine.</summary>
    public long? Next => before.Count == 0 ? null : before.Min(p => p.At);
}
