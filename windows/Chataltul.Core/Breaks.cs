namespace Chataltul.Core;

/// <summary>The cycle presets of the Breaks settings: how long to focus and how long to rest. The Mac's Breaks.swift.</summary>
public enum CyclePreset { Pomodoro, FiftyTwo, Deep, Flow }

public static class Cycles
{
    public static string Title(CyclePreset p) => p switch
    {
        CyclePreset.Pomodoro => "Pomodoro", CyclePreset.FiftyTwo => "52 / 17", CyclePreset.Deep => "Deep work", _ => "Flowtime",
    };

    public static string Summary(CyclePreset p) => p switch
    {
        CyclePreset.Pomodoro => "25 on, 5 off", CyclePreset.FiftyTwo => "52 on, 17 off", CyclePreset.Deep => "90 on, 20 off", _ => "a stopwatch, break by feel",
    };

    /// <summary>Minutes to focus and to break; Flowtime has no set length.</summary>
    public static (int Focus, int Rest)? Minutes(CyclePreset p) => p switch
    {
        CyclePreset.Pomodoro => (25, 5), CyclePreset.FiftyTwo => (52, 17), CyclePreset.Deep => (90, 20), _ => null,
    };

    public static bool Stopwatch(CyclePreset p) => p == CyclePreset.Flow;

    /// <summary>Flowtime: the break after a stretch is a fifth of it, between 5 and 25 minutes.</summary>
    public static int FlowBreak(int focusedMinutes) => Math.Clamp((int)Math.Round(focusedMinutes / 5.0, MidpointRounding.AwayFromZero), 5, 25);
}

/// <summary>How breaks start.</summary>
public enum BreakStart
{
    /// <summary>The popover offers the break when a block is filed.</summary>
    Offer,
    /// <summary>It starts when a block ends and the kitten is home.</summary>
    Auto,
}

/// <summary>How breaks are started and how firm they are.</summary>
public sealed class BreakSettings
{
    public static readonly string[] DefaultIdeas = ["Stand up and stretch", "Get some water", "Look out of a window", "Ten slow breaths", "A mindful minute", "Walk to the kitchen and back"];

    /// <summary>The break covers every screen; off, it is only the popover's.</summary>
    public bool FullScreen { get; set; } = true;
    /// <summary>No Postpone and no Skip.</summary>
    public bool Strict { get; set; }
    public bool EyeBreaks { get; set; } = true;
    /// <summary>Eye breaks and check-ins hold during calls and full-screen video.</summary>
    public bool WaitDuringCalls { get; set; } = true;
    public BreakStart Start { get; set; } = BreakStart.Offer;
    public int PostponeMin { get; set; } = 5;
    public int EyeEveryMin { get; set; } = 20;
    public int EyeSeconds { get; set; } = 20;
    public List<string> Ideas { get; set; } = [.. DefaultIdeas];

    /// <summary>The idea for a break (the first), and the others as ideas to turn to.</summary>
    public (string Main, List<string> Others) IdeaFor(int n)
    {
        if (Ideas.Count == 0) return ("Take a breath.", new List<string>());
        var i = (n % Ideas.Count + Ideas.Count) % Ideas.Count;
        var rest = Ideas.ToList();
        var main = rest[i];
        rest.RemoveAt(i);
        return (main, rest.Take(3).ToList());
    }
}

/// <summary>
/// "Look at something far away for 20 seconds, every 20 minutes of a block." It waits while the PC is busy with a
/// call or full-screen video, and being away from the PC for 20 seconds counts as one.
/// </summary>
public sealed class EyeBreakTimer(int everyMin = 20, int seconds = 20)
{
    public long EveryMs { get; } = everyMin * 60_000L;
    public int Seconds { get; } = seconds;
    /// <summary>When the last eye break (or the start of the block) was.</summary>
    public long? Since { get; private set; }
    public long? Showing { get; private set; }

    /// <summary>A block started: the first one comes after a full interval.</summary>
    public void BlockStarted(long now) { Since = now; Showing = null; }
    public void BlockEnded() { Since = null; Showing = null; }

    public long? NextAt => Since is { } s ? s + EveryMs : null;

    /// <summary>Is it time, and may it show? True once, then the card counts as showing until Finish.</summary>
    public bool Check(long now, bool running, bool busy)
    {
        if (!running || Showing != null || Since is not { } s || now - s < EveryMs || busy) return false;
        Showing = now;
        return true;
    }

    /// <summary>The card went (its 20 seconds, or a Skip): the next one is a full interval away.</summary>
    public void Finish(long now) { Showing = null; Since = now; }

    /// <summary>The person was away from the PC this long: that was an eye break.</summary>
    public bool AwayFor(long ms, long now)
    {
        if (Since == null || ms < Seconds * 1000L) return false;
        Since = now;
        return true;
    }
}
