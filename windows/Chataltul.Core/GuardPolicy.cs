using System.Text.Json;

namespace Chataltul.Core;

// What a list does at a given moment, and the cards for "Wait it out" and "Locked". No window in here, so the tests
// can run it. The speed bump itself (the cat, the 15 seconds, the hold) is in SpeedBump.cs. The Mac's GuardPolicy.swift.

/// <summary>What a list asks for right now.</summary>
/// <param name="Until">When it stops: the block's end, or the end of the window today. Null when there's no end to name.</param>
public sealed record Enforcement(GuardMode Mode, Enforcement.Reasons Reason, string ListName, long? Until)
{
    public enum Reasons
    {
        /// <summary>A block is running.</summary>
        Block,
        /// <summary>Outside a block, inside the list's window.</summary>
        Schedule,
        /// <summary>Outside a block, and the day's allowance is used up.</summary>
        AllowanceUsed,
    }
}

public static class GuardPolicy
{
    /// <summary>
    /// <c>blockEnd</c>: the running block's end, or null when none runs. <c>usedMs</c>: what the list's allowance has seen
    /// today. A speed bump is the cat's and its kitten's: outside a block it has neither, so it asks for the wait instead.
    /// </summary>
    public static Enforcement? For(GuardList list, DateTime now, long? blockEnd, long usedMs)
    {
        if (blockEnd is { } end) return new Enforcement(list.Mode, Enforcement.Reasons.Block, list.Name, end);
        if (!list.When.Covers(now)) return null;
        var allowed = list.AllowanceMin * 60_000L;
        if (allowed > 0 && usedMs < allowed) return null;   // still inside the allowance: free, and it is counted
        var mode = list.Mode == GuardMode.SpeedBump ? GuardMode.WaitItOut : list.Mode;
        long? until = list.When.End(now) is { } e ? Format.Ms(e) : null;
        return new Enforcement(mode, allowed > 0 ? Enforcement.Reasons.AllowanceUsed : Enforcement.Reasons.Schedule, list.Name, until);
    }

    /// <summary>Does this list count the time spent on it today (outside a block, inside its window, inside its allowance)?</summary>
    public static bool CountsUsage(GuardList list, DateTime now, long? blockEnd, long usedMs) =>
        blockEnd == null && list.AllowanceMin > 0 && list.When.Covers(now) && usedMs < list.AllowanceMin * 60_000L;

    /// <summary>A locked list during a block can't be got out of by stopping the block.</summary>
    public static bool LocksTheBlock(Blocklist b) => b.Lists.Any(l => l.Mode == GuardMode.Locked && (l.Count > 0 || l.Categories.Count > 0));
}

/// <summary>How much of its allowance each list has used today (usage.json). A new day starts at zero.</summary>
public sealed class DayUsage
{
    public string Day { get; set; } = Key(DateTime.Now);
    public Dictionary<string, long> Ms { get; set; } = [];

    public static string Key(DateTime d) => d.ToString("yyyy-MM-dd", System.Globalization.CultureInfo.InvariantCulture);

    public void Add(string listId, long ms, DateTime at)
    {
        Roll(at);
        Ms[listId] = Ms.GetValueOrDefault(listId) + ms;
    }

    public long Used(string listId, DateTime at)
    {
        Roll(at);
        return Ms.GetValueOrDefault(listId);
    }

    void Roll(DateTime at)
    {
        var k = Key(at);
        if (k != Day) { Day = k; Ms = []; }
    }

    public static DayUsage Read(string folder)
    {
        try { return JsonSerializer.Deserialize<DayUsage>(File.ReadAllText(Path.Join(folder, "usage.json")), J.Files) ?? new DayUsage(); }
        catch (Exception) { return new DayUsage(); }
    }

    public void Write(string folder)
    {
        try { Files.WriteWhole(Path.Join(folder, "usage.json"), JsonSerializer.Serialize(this, J.Files)); } catch (Exception) { }
    }
}

// ---------------------------------------------------------------- the cards

public enum OtherKind { WaitItOut, Locked, UsedUp }

/// <summary>"Wait it out", "Locked" and "Used up": the cat over something on a list, with no way to hurry it.</summary>
/// <param name="WaitMs">Wait it out: how long the wait is.</param>
/// <param name="Until">Locked and used up: when it opens again (null if not known).</param>
public sealed record OtherCard(OtherKind Kind, Target Target, string ListName, long ShownAt, long WaitMs, long? Until, string Cat, string CatName)
{
    public int SecondsLeft(long now) => (int)Math.Max(0, (WaitMs - (now - ShownAt) + 999) / 1000);
    public double Fraction(long now) => Math.Clamp((double)(WaitMs - (now - ShownAt)) / Math.Max(1, WaitMs), 0, 1);

    public string Title => Kind switch
    {
        OtherKind.WaitItOut => "Wait it out.",
        OtherKind.Locked => Until is { } u ? $"Locked until {Format.ClockTime(u)}." : "Locked.",
        _ => "That's today's time.",
    };

    public string Line(long now) => Kind switch
    {
        OtherKind.WaitItOut => $"{Target.Label} opens in {Format.Plural(SecondsLeft(now), "second")}. Most urges pass by then.",
        OtherKind.Locked => $"{Target.Label} is on your {ListName} list, and it won't open until your block ends.",
        _ => $"You've used the time {Target.Label} gets outside your blocks." + (Until is { } u ? $" It opens again after {Format.ClockTime(u)}." : ""),
    };
}

public abstract record OtherAction
{
    public sealed record Show(OtherCard Card) : OtherAction;
    public sealed record Update(OtherCard Card) : OtherAction;
    public sealed record Hide : OtherAction;
    /// <summary>Back to the app the person was in, by its program file.</summary>
    public sealed record Activate(string? Exe) : OtherAction;
}

/// <summary>
/// Wait it out, locked and used up. A visit that waited its turn is let through until it is left for a few seconds;
/// a locked or used-up one stays covered for as long as it is in front.
/// </summary>
public sealed class OtherGuard(long waitMs = OtherGuard.WaitMs)
{
    public const long WaitMs = 30_000, RejoinMs = 5_000;
    public OtherCard? Card { get; private set; }
    /// <summary>The visit that has waited and may stay.</summary>
    Target? openFor;
    long? leftAt;
    Target? dismissed;
    public string? Previous { get; private set; }

    /// <summary>The front changed. <c>hit</c> is the listed thing in front, <c>enforcement</c> what its list asks for now.</summary>
    public List<OtherAction> Front(GuardHit? hit, Enforcement? enforcement, string? exe, string cat, string catName, long now)
    {
        if (hit == null || enforcement is not { } e || e.Mode == GuardMode.SpeedBump)
        {
            dismissed = null;
            if (hit == null && exe != null) Previous = exe;
            if (openFor != null && leftAt == null) leftAt = now;
            return HideCard();
        }
        var target = hit.Target;
        if (leftAt is { } l && now - l >= RejoinMs) openFor = null;
        leftAt = null;
        if (openFor == target) return HideCard();
        if (dismissed is { } d && d == target) return [];
        // an allowance that is used up is a hard stop for the day, whatever the mode
        var kind = e.Reason == Enforcement.Reasons.AllowanceUsed ? OtherKind.UsedUp : e.Mode == GuardMode.WaitItOut ? OtherKind.WaitItOut : OtherKind.Locked;
        if (Card is { } c)
        {
            if (c.Target == target && c.Kind == kind) return [];
            Card = c with { Target = target, Kind = kind, Until = e.Until, ListName = e.ListName };
            return [new OtherAction.Update(Card)];
        }
        Card = new OtherCard(kind, target, e.ListName, now, waitMs, e.Until, cat, catName);
        return [new OtherAction.Show(Card)];
    }

    /// <summary>Time passes: a wait may be over, and a visit that was left may be forgotten.</summary>
    public List<OtherAction> Tick(long now, bool stillGuarded = true)
    {
        if (leftAt is { } l && now - l >= RejoinMs) { openFor = null; leftAt = null; }
        if (Card is not { } c) return [];
        if (!stillGuarded) return HideCard();
        if (c.Kind == OtherKind.WaitItOut && now - c.ShownAt >= c.WaitMs)
        {
            Card = null;
            openFor = c.Target;
            return [new OtherAction.Hide()];
        }
        return [];
    }

    public List<OtherAction> BackToWork()
    {
        if (Card is not { } c) return [];
        Card = null;
        dismissed = c.Target;
        return [new OtherAction.Hide(), new OtherAction.Activate(Previous)];
    }

    List<OtherAction> HideCard()
    {
        if (Card == null) return [];
        Card = null;
        return [new OtherAction.Hide()];
    }
}
