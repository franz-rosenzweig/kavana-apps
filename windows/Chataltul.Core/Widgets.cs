namespace Chataltul.Core;

/// <summary>What the Window widget shows: ready to start, a block and its states, how it ended, a break.</summary>
public enum WMode { Ready, Focusing, Checkin, Paused, Home, Stopped, Gone, Extra, Break }

/// <summary>A widget that can be put on the desktop: its id, its name and words in the gallery, the sizes it comes in.</summary>
public sealed record WidgetKind(string Id, string Title, string About, string[] Sizes);

/// <summary>
/// The widgets' rules, with no window: which there are, how big, what state a block is in, and the numbers the
/// playful ones draw from the history. The Android app's widget/Snap.kt.
/// </summary>
public static class Widgets
{
    public static readonly WidgetKind[] All =
    [
        new("window", "The Window", "Your cat in its round window: start a block, see the time, answer a check-in, say how it went.", ["medium", "small", "wide"]),
        new("quickstart", "Quick start", "15, 25, 50 or a stopwatch, in one click.", ["wide"]),
        new("today", "Today", "Your goal as a ring, your streak, the day's missions and a reward to collect.", ["medium"]),
        new("later", "Later", "Your list: tick things off, start a block with one, or park a thought.", ["tall"]),
        new("pet", "Pet the cat", "Your cat on its sill. Click it and it hops; click again and it purrs. After ten at night it's asleep.", ["small", "medium", "wide"]),
        new("clock", "A cat with a day", "The time, the date and what your cat is up to.", ["medium"]),
        new("streak", "Streak", "Days in a row, with a paw for each day of the week.", ["small"]),
        new("goal", "Daily goal", "Today's focus against your goal.", ["small"]),
        new("missions", "Missions", "The day's missions, and Collect when a reward is waiting.", ["medium"]),
        new("constellation", "Constellation", "The month as a night sky: a star for every block that came home.", ["medium"]),
        new("weather", "This week, as weather", "The last seven days as little skies, each with the cat that came home.", ["medium"]),
        new("cafe", "Your café", "The café of today, in 3D.", ["large"]),
    ];

    public static WidgetKind? Kind(string id) => All.FirstOrDefault(k => k.Id == id);

    /// <summary>A size's width and height in DIPs.</summary>
    public static (double W, double H) SizeOf(string size) => size switch
    {
        "small" => (172, 172),
        "wide" => (360, 84),
        "tall" => (360, 236),
        "large" => (360, 360),
        _ => (360, 172),
    };

    public static string SizeName(string size) => size switch
    {
        "small" => "Small", "wide" => "Wide strip", "tall" => "Tall", "large" => "Large", _ => "Medium",
    };

    public static WMode ModeOf(Block? active, Block? awaiting, Brk? onBreak, bool catsOn)
    {
        if (awaiting != null) return awaiting.Kitten == "home" ? WMode.Home : WMode.Stopped;
        if (active is { } b)
        {
            if (b.Paused != null) return WMode.Paused;
            if (b.Pending != null) return WMode.Checkin;
            if (b.Over != null) return WMode.Extra;
            if (catsOn && b.Kitten == "gone") return WMode.Gone;
            return WMode.Focusing;
        }
        return onBreak != null ? WMode.Break : WMode.Ready;
    }

    /// <summary>"GOOD MORNING", by the hour.</summary>
    public static string Greeting(int hour) => hour switch
    {
        >= 5 and <= 11 => "GOOD MORNING", >= 12 and <= 16 => "GOOD AFTERNOON", >= 17 and <= 21 => "GOOD EVENING", _ => "NIGHT OWL",
    };

    /// <summary>After ten at night and before six in the morning, the cats are asleep.</summary>
    public static bool IsNight(int hour) => hour >= 22 || hour < 6;

    /// <summary>What the cat is up to, for the clock: by the block, else by the hour.</summary>
    public static string CatLine(WMode mode, string name, int hour) => mode switch
    {
        WMode.Focusing or WMode.Extra => $"{name} is growing. Stay with it.",
        WMode.Paused => $"Taking a breather. {name} waits.",
        WMode.Checkin => $"{name} wants to know: still on it?",
        WMode.Home => $"{name} came home.",
        WMode.Stopped or WMode.Gone => $"{name} wandered off.",
        _ when IsNight(hour) => $"{name} is asleep on the cushion. Shh.",
        _ when hour is >= 5 and <= 11 => $"Good morning. {name} is waiting by the window.",
        _ when hour is >= 12 and <= 16 => $"{name} is on the cushion, waiting for you.",
        _ => $"The café is quiet. {name} is curled up.",
    };

    /// <summary>"2 h 10" for 130 minutes, "45 min" for 45.</summary>
    public static string Hours(int m) => m >= 60 ? $"{m / 60} h {m % 60:00}" : $"{m} min";

    /// <summary>One day for the playful widgets: its minutes, the cat of its longest block home, and the hour that started (-1 if none).</summary>
    public sealed record Day(DateTime Date, int Minutes, string? Cat, int Hour);

    /// <summary>The last <c>count</c> days, oldest first, today last: what came home each day.</summary>
    public static List<Day> Days(IEnumerable<Hist> history, DateTime today, int count)
    {
        var home = history.Where(h => h.Kitten == "home").ToList();
        var out_ = new List<Day>();
        for (var k = count - 1; k >= 0; k--)
        {
            var d = today.Date.AddDays(-k);
            var of = home.Where(h => Format.Local(h.Start).Date == d).ToList();
            var top = of.OrderByDescending(h => h.Actual).FirstOrDefault();
            out_.Add(new Day(d, of.Sum(h => h.Actual), top?.Cat, top != null ? Format.Local(top.Start).Hour : -1));
        }
        return out_;
    }

    /// <summary>This month's blocks that came home, a star each (the last 200), as (day of the month from 0, its minutes, the hour).</summary>
    public static List<(int Day, int Minutes, int Hour)> Stars(IEnumerable<Hist> history, DateTime now)
    {
        var first = new DateTime(now.Year, now.Month, 1);
        return history.Where(h => h.Kitten == "home" && Format.Local(h.Start) >= first)
            .OrderBy(h => h.Start).TakeLast(200)
            .Select(h => (Format.Local(h.Start).Day - 1, h.Actual, Format.Local(h.Start).Hour)).ToList();
    }

    /// <summary>Which days of this week (Monday first) had a block that came home: the paws under the streak.</summary>
    public static bool[] WeekPaws(IEnumerable<Hist> history, DateTime today)
    {
        var monday = today.Date.AddDays(-Insights.MondayIndex(today));
        var paws = new bool[7];
        foreach (var h in history.Where(h => h.Kitten == "home"))
        {
            var d = Format.Local(h.Start).Date;
            if (d >= monday && d < monday.AddDays(7)) paws[(d - monday).Days] = true;
        }
        return paws;
    }

    /// <summary>A star's place in the sky, the same each time for the same block: x and y from 0 to 1.</summary>
    public static (double X, double Y) StarAt(int day, int minutes, int hour, int days)
    {
        var x = (day + 0.5) / Math.Max(1, days);
        var h = (uint)(day * 7919 + minutes * 104729 + hour * 31);
        h ^= h << 13; h ^= h >> 17; h ^= h << 5;
        var jitter = (h % 1000) / 1000.0;
        var y = 0.12 + 0.76 * ((hour / 24.0) * 0.6 + jitter * 0.4);
        return (Math.Clamp(x + (jitter - 0.5) * 0.6 / Math.Max(1, days), 0.03, 0.97), Math.Clamp(y, 0.08, 0.92));
    }
}
