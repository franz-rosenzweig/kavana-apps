namespace Chataltul.Core;

// The speed bump: while a block runs and the person opens an app (or a site) on their list, the cat walks
// over it. This file is the rules, with no screen in it, so the tests can run them. The Mac's SpeedBump.swift.
//
//  - Only while a block runs, isn't paused, and its kitten is still growing. A kitten that wandered off
//    doesn't come up again for that block.
//  - The card counts down 15 seconds. "Back to work" takes them back to the app they were in before.
//  - "Leave anyway" has to be held (HoldTracker). Held, the kitten wanders off right away.
//  - No pause, no snooze, no close. If the 15 seconds run out, the kitten wanders off and the block goes on.

/// <summary>What the bump needs to know of the running block.</summary>
public sealed record BumpBlock(string Id, bool Paused = false, string Kitten = "growing", string Cat = "miso", string CatName = "Miso", string Title = "Focus", long End = 0)
{
    /// <summary>A block the card may come up in.</summary>
    public bool Open => Kitten == "growing" && !Paused;
}

/// <summary>The card on the screen.</summary>
public sealed record BumpCard(Target Target, long ShownAt, string BlockId, string Cat, string CatName, string Title, long BlockEnd)
{
    /// <summary>Whole seconds left of the grace, 15 down to 0.</summary>
    public long SecondsLeft(long now, long graceMs) => Math.Max(0, (graceMs - (now - ShownAt) + 999) / 1000);

    /// <summary>1 at the start, 0 at the end, for the ring.</summary>
    public double Fraction(long now, long graceMs) => Math.Clamp((double)(graceMs - (now - ShownAt)) / graceMs, 0, 1);

    public string Line(long now, long graceMs)
    {
        var s = SecondsLeft(now, graceMs);
        return $"Come back within {s} {(s == 1 ? "second" : "seconds")}, or {CatName} wanders off.";
    }
}

public abstract record BumpAction
{
    public sealed record Show(BumpCard Card) : BumpAction;
    public sealed record Update(BumpCard Card) : BumpAction;
    public sealed record Hide : BumpAction;
    /// <summary>Send the engine's away op: the kitten left <c>At</c>, after <c>Grace</c> milliseconds.</summary>
    public sealed record Away(long At, long Grace) : BumpAction;
    /// <summary>Say "&lt;name&gt; wandered off. Your block keeps going." for a moment.</summary>
    public sealed record Wandered(string CatName) : BumpAction;
    /// <summary>Bring this app back to the front.</summary>
    public sealed record Activate(string? App) : BumpAction;
}

public sealed class SpeedBump(long graceMs = SpeedBump.DefaultGraceMs)
{
    public const long DefaultGraceMs = 15_000;
    public long GraceMs { get; } = graceMs;
    public BumpCard? Card { get; private set; }
    /// <summary>The block whose kitten already left because of the bump.</summary>
    string? suppressed;
    /// <summary>After "Back to work": the same target doesn't bump again until it has been left.</summary>
    Target? dismissed;
    /// <summary>The last app that wasn't on the list: where "Back to work" goes.</summary>
    public string? Previous { get; private set; }

    /// <summary>
    /// The front app changed (or was looked at again). <c>target</c> is what's on the list that it matches, if
    /// anything; <c>app</c> is the front app, which becomes "where they were" when it isn't on the list.
    /// </summary>
    public List<BumpAction> Front(Target? target, string? app, BumpBlock? block, long now)
    {
        if (target == null)
        {
            dismissed = null;
            if (app != null) Previous = app;
            return HideCard();
        }
        if (block is not { Open: true } b || b.Id == suppressed) return HideCard();
        if (dismissed != null && dismissed == target) return [];
        if (Card is { } c)
        {
            if (c.Target == target) return [];
            Card = c with { Target = target };
            return [new BumpAction.Update(Card)];
        }
        Card = new BumpCard(target, now, b.Id, b.Cat, b.CatName, b.Title, b.End);
        return [new BumpAction.Show(Card)];
    }

    /// <summary>Time passes: the grace may run out.</summary>
    public List<BumpAction> Tick(BumpBlock? block, long now)
    {
        if (Card is not { } c) return [];
        if (block is not { Open: true } b || b.Id != c.BlockId) return HideCard();
        if (now - c.ShownAt < GraceMs) return [];
        Card = null;
        suppressed = b.Id;
        return [new BumpAction.Hide(), new BumpAction.Away(c.ShownAt, GraceMs), new BumpAction.Wandered(c.CatName)];
    }

    /// <summary>"Back to work".</summary>
    public List<BumpAction> BackToWork()
    {
        if (Card is not { } c) return [];
        Card = null;
        dismissed = c.Target;
        return [new BumpAction.Hide(), new BumpAction.Activate(Previous)];
    }

    /// <summary>"Leave anyway", held long enough: the kitten wanders off now.</summary>
    public List<BumpAction> LeaveHeld(long now)
    {
        if (Card is not { } c) return [];
        Card = null;
        suppressed = c.BlockId;
        return [new BumpAction.Hide(), new BumpAction.Away(now, 0), new BumpAction.Wandered(c.CatName)];
    }

    List<BumpAction> HideCard()
    {
        if (Card == null) return [];
        Card = null;
        return [new BumpAction.Hide()];
    }
}

/// <summary>"Leave anyway" counts only when held for <c>HoldMs</c>. A plain tap only says "Hold to leave".</summary>
public sealed class HoldTracker(long holdMs = HoldTracker.DefaultHoldMs)
{
    public const long DefaultHoldMs = 2_000;
    public const long QuickTapMs = 400;
    public long HoldMs { get; } = holdMs;
    long? downAt;
    public bool Done { get; private set; }
    /// <summary>A plain tap was made: the button says "Hold to leave" from then on.</summary>
    public bool Hint { get; private set; }

    public bool Holding => downAt != null;

    public void Down(long now) { if (!Done) downAt = now; }

    public void Up(long now)
    {
        var d = downAt;
        downAt = null;
        if (d == null || Done) return;
        if (now - d < QuickTapMs) Hint = true;
    }

    /// <summary>0 to 1: how far the button has filled.</summary>
    public double Progress(long now) => downAt is { } d ? Math.Clamp((double)(now - d) / HoldMs, 0, 1) : 0;

    /// <summary>True once, when the hold has lasted long enough.</summary>
    public bool Check(long now)
    {
        if (downAt is not { } d || Done || now - d < HoldMs) return false;
        Done = true;
        downAt = null;
        return true;
    }
}
