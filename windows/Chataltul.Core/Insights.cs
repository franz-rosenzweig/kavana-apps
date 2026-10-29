namespace Chataltul.Core;

public enum Span { Day, Week, Month, Year }

/// <summary>A day, week, month or year, as the app window's Today shows it, in milliseconds (from inclusive, to exclusive).</summary>
public sealed record Period(Span Span, long From, long To)
{
    public DateTime Start => Format.Local(From);
    /// <summary>The last moment in it, for "1 Sep to 30 Sep".</summary>
    public DateTime Last => Format.Local(To - 1);
}

/// <summary>What the blocks of a period add up to: the numbers, bars and lists in Today. The Mac's Insights.swift.</summary>
public static class Insights
{
    /// <summary>The period that has <c>date</c> in it, in this PC's time. Weeks start on Monday.</summary>
    public static Period PeriodOf(Span span, DateTime date)
    {
        var d = date.Date;
        DateTime a, b;
        switch (span)
        {
            case Span.Day: a = d; b = a.AddDays(1); break;
            case Span.Week: a = d.AddDays(-MondayIndex(d)); b = a.AddDays(7); break;
            case Span.Month: a = new DateTime(d.Year, d.Month, 1); b = a.AddMonths(1); break;
            default: a = new DateTime(d.Year, 1, 1); b = a.AddYears(1); break;
        }
        return new Period(span, Format.Ms(DateTime.SpecifyKind(a, DateTimeKind.Local)), Format.Ms(DateTime.SpecifyKind(b, DateTimeKind.Local)));
    }

    /// <summary>The period before this one (for "+6 h on September").</summary>
    public static Period Before(Period p) => PeriodOf(p.Span, p.Start.AddSeconds(-1));

    /// <summary>Monday is 0, Sunday is 6.</summary>
    public static int MondayIndex(DateTime d) => ((int)d.DayOfWeek + 6) % 7;

    public static List<Hist> In(IEnumerable<Hist> list, Period p) => list.Where(b => b.Start >= p.From && b.Start < p.To).ToList();

    public static int Minutes(IEnumerable<Hist> list) => list.Sum(b => b.Actual);

    /// <summary>Cats that came home, cats that wandered off.</summary>
    public static int Home(IEnumerable<Hist> list) => list.Count(b => b.Kitten == "home");
    public static int Wandered(IEnumerable<Hist> list) => list.Count(b => b.Kitten == "gone");

    public static (int Done, int Partly, int NotReally) Outcomes(IEnumerable<Hist> list)
    {
        var l = list.ToList();
        return (l.Count(b => b.Outcome == "done"), l.Count(b => b.Outcome == "partly"), l.Count(b => b.Outcome == "notreally"));
    }

    public static int Drifts(IEnumerable<Hist> list) => list.Sum(b => b.Drifts);

    /// <summary>How many slots the period has: hours of a day, days of a week or month, months of a year.</summary>
    public static int SlotCount(Period p) => p.Span switch
    {
        Span.Day => 24,
        Span.Week => 7,
        Span.Month => DateTime.DaysInMonth(p.Start.Year, p.Start.Month),
        _ => 12,
    };

    static int SlotOf(Span span, DateTime d) => span switch
    {
        Span.Day => d.Hour,
        Span.Week => MondayIndex(d),
        Span.Month => d.Day - 1,
        _ => d.Month - 1,
    };

    /// <summary>Minutes in each slot of the period, minute by minute, so a block over the hour splits.</summary>
    public static int[] Slots(IEnumerable<Hist> list, Period p)
    {
        var out_ = new int[SlotCount(p)];
        foreach (var b in list)
            for (var i = 0; i < Math.Max(0, b.Actual); i++)
            {
                var t = b.Start + i * 60_000L;
                if (t < p.From || t >= p.To) continue;
                var k = SlotOf(p.Span, Format.Local(t));
                if (k >= 0 && k < out_.Length) out_[k]++;
            }
        return out_;
    }

    /// <summary>Minutes in each slot, split by tag (no tag is ""): for stacked bars.</summary>
    public static Dictionary<string, int>[] SlotsByTag(IEnumerable<Hist> list, Period p)
    {
        var out_ = Enumerable.Range(0, SlotCount(p)).Select(_ => new Dictionary<string, int>()).ToArray();
        foreach (var b in list)
            for (var i = 0; i < Math.Max(0, b.Actual); i++)
            {
                var t = b.Start + i * 60_000L;
                if (t < p.From || t >= p.To) continue;
                var k = SlotOf(p.Span, Format.Local(t));
                if (k < 0 || k >= out_.Length) continue;
                var tag = b.Tag ?? "";
                out_[k][tag] = out_[k].GetValueOrDefault(tag) + 1;
            }
        return out_;
    }

    /// <summary>Minutes by tag, biggest first (no tag is "Untagged").</summary>
    public static List<(string Tag, int Minutes)> ByTag(IEnumerable<Hist> list) =>
        list.GroupBy(b => b.Tag ?? "")
            .Select(g => (Tag: g.Key.Length == 0 ? "Untagged" : g.Key, Minutes: g.Sum(b => b.Actual)))
            .OrderByDescending(x => x.Minutes).ThenBy(x => x.Tag, StringComparer.Ordinal).ToList();

    /// <summary>Minutes in each hour of the day (0 to 23), over all the blocks.</summary>
    public static int[] ByHour(IEnumerable<Hist> list)
    {
        var h = new int[24];
        foreach (var b in list)
            for (var i = 0; i < Math.Max(0, b.Actual); i++) h[Format.Local(b.Start + i * 60_000L).Hour]++;
        return h;
    }

    /// <summary>Days in a row ending today (or yesterday, which still counts until today ends).</summary>
    public static int Streak(IEnumerable<Hist> list, DateTime today)
    {
        var days = list.Select(b => Format.Local(b.Start).Date).ToHashSet();
        var d = today.Date;
        if (!days.Contains(d)) d = d.AddDays(-1);
        var n = 0;
        while (days.Contains(d)) { n++; d = d.AddDays(-1); }
        return n;
    }

    /// <summary>"+6 h" against the period before, or null when that had nothing.</summary>
    public static string? Compare(int now, int before)
    {
        if (before <= 0) return null;
        var d = now - before;
        return (d >= 0 ? "+" : "−") + Format.FmtMin(Math.Abs(d));
    }

    /// <summary>"8 AM", "12 PM".</summary>
    public static string HourName(int h) => h == 0 ? "12 AM" : h < 12 ? $"{h} AM" : h == 12 ? "12 PM" : $"{h - 12} PM";

    /// <summary>"1st", "22nd".</summary>
    public static string Ordinal(int n) =>
        n + (n % 100 is >= 11 and <= 13 ? "th" : (n % 10) switch { 1 => "st", 2 => "nd", 3 => "rd", _ => "th" });
}
