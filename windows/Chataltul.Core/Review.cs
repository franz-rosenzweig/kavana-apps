using System.Globalization;

namespace Chataltul.Core;

// The week in review: streaks with a pause, a few words about the week made on this PC, answers to a few questions
// about it, and the files that can be exported. No network, no model needed. The Mac's Review.swift.

/// <param name="Days">Days in a row ending today (or yesterday, which still counts until today ends).</param>
/// <param name="PausedDays">The days a pause carried the streak over.</param>
/// <param name="PausesLeft">Pauses left this week: one a week.</param>
public sealed record StreakInfo(int Days, List<DateTime> PausedDays, int PausesLeft);

public static class Streaks
{
    /// <summary>
    /// Days in a row, where one missed day a week is carried over by the week's pause (weeks start on Monday). The
    /// paused day doesn't add to the count, and a pause only bridges a single day: two missed days in a row end it.
    /// </summary>
    public static StreakInfo WithPause(IEnumerable<Hist> list, DateTime today)
    {
        var focused = list.Where(h => h.Actual > 0).Select(h => Format.Local(h.Start).Date).ToHashSet();
        static DateTime Week(DateTime d) => d.Date.AddDays(-Insights.MondayIndex(d));
        var d = today.Date;
        if (!focused.Contains(d)) d = d.AddDays(-1);   // today isn't over
        var used = new HashSet<DateTime>();
        var paused = new List<DateTime>();
        var n = 0;
        while (true)
        {
            if (focused.Contains(d)) { n++; d = d.AddDays(-1); continue; }
            var w = Week(d);
            if (!used.Contains(w) && focused.Contains(d.AddDays(-1))) { used.Add(w); paused.Add(d); d = d.AddDays(-1); continue; }
            break;
        }
        return new StreakInfo(n, paused, used.Contains(Week(today)) ? 0 : 1);
    }
}

/// <summary>What a period came to, for the few words about it.</summary>
public sealed class WeekFacts
{
    public int Minutes, Blocks;
    public (string Name, int Minutes)? BestDay;
    public string? TopTag;
    public List<(string Id, int Times)> Reasons = [];
    /// <summary>Most of the blocks with drifts began after 3 PM.</summary>
    public bool LateDrifts;
    public int GoalMinutes, DaysMet;
    public int? BestHour;
    public StreakInfo Streak = new(0, [], 1);
    public int Done, Partly, NotReally, Home, Wandered;
}

public static class Review
{
    public static readonly string[] Days = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"];

    public static WeekFacts Facts(IReadOnlyList<Hist> blocks, Period period, int goalMin, StreakInfo streak)
    {
        var f = new WeekFacts { Minutes = Insights.Minutes(blocks), Blocks = blocks.Count };
        var slots = Insights.Slots(blocks, period);
        if (period.Span == Span.Week && slots.Max() is var most && most > 0) f.BestDay = (Days[Array.IndexOf(slots, most)], most);
        f.TopTag = Insights.ByTag(blocks).Where(x => x.Tag != "Untagged").Select(x => x.Tag).FirstOrDefault();
        f.Reasons = blocks.SelectMany(b => b.Whys).GroupBy(w => w).Select(g => (g.Key, g.Count()))
            .OrderByDescending(x => x.Item2).ThenBy(x => x.Key, StringComparer.Ordinal).ToList();
        var drifted = blocks.Where(b => b.Drifts > 0).ToList();
        var late = drifted.Count(b => Format.Local(b.Start).Hour >= 15);
        f.LateDrifts = drifted.Count >= 2 && late * 10 >= drifted.Count * 6;
        f.GoalMinutes = goalMin;
        if (goalMin > 0 && period.Span == Span.Week) f.DaysMet = slots.Count(s => s >= goalMin);
        var h = Insights.ByHour(blocks);
        if (h.Max() > 0) f.BestHour = Array.IndexOf(h, h.Max());
        f.Streak = streak;
        (f.Done, f.Partly, f.NotReally) = Insights.Outcomes(blocks);
        f.Home = Insights.Home(blocks);
        f.Wandered = Insights.Wandered(blocks);
        return f;
    }

    static string Blocks(int n) => n == 1 ? "1 block" : $"{n} blocks";

    /// <summary>
    /// "A good week: 11 h 20 min over 14 blocks, best on Tuesday. Writing took most of it. When you drifted it was
    /// usually the web, mostly in blocks that began after 3 PM, so the afternoon blocks might want the speed bump on."
    /// </summary>
    public static string Summary(WeekFacts f)
    {
        if (f.Blocks == 0) return "No blocks yet this week. Start one, and what it came to shows up here.";
        var verdict = f.GoalMinutes > 0
            ? f.DaysMet >= 4 ? "A good week" : f.DaysMet >= 2 ? "An uneven week" : "A quiet week"
            : f.Minutes >= 600 ? "A good week" : f.Minutes >= 240 ? "A steady week" : "A quiet week";
        var s = $"{verdict}: {Format.FmtMin(f.Minutes)} over {Blocks(f.Blocks)}";
        if (f.BestDay is { } d) s += $", best on {d.Name}";
        s += ".";
        if (f.TopTag is { } t) s += $" {t} took most of it.";
        if (f.Reasons.Count > 0)
        {
            var r = f.Reasons[0].Id;
            var what = r == "web" ? "the web" : r == "phone" ? "the phone" : Core.Reasons.Label(r).ToLowerInvariant();
            s += $" When you drifted it was usually {what}";
            if (f.LateDrifts) s += ", mostly in blocks that began after 3 PM";
            if (r == "web") s += $", so {(f.LateDrifts ? "the afternoon blocks" : "your blocks")} might want the speed bump on";
            else if (r == "phone") s += ", which is out of Chataltul's reach, so maybe put it in another room";
            s += ".";
        }
        return s;
    }

    /// <summary>A few questions answered from the numbers (Windows has no model on the PC to ask).</summary>
    public static string Answer(string question, WeekFacts f)
    {
        var q = question.ToLowerInvariant();
        bool Has(params string[] words) => words.Any(q.Contains);
        if (f.Blocks == 0) return "There's nothing to go on yet: no blocks in this period.";
        if (Has("best day", "most productive", "which day", "busiest"))
            return f.BestDay is { } d ? $"Your best day was {d.Name}, with {Format.FmtMin(d.Minutes)}." : "There's no best day to name in this period.";
        // drifting before "when" (when did I drift?), and "hours" is the total, not the best hour
        if (Has("distract", "drift", "pulled", "away from"))
        {
            if (f.Reasons.Count == 0) return "No drifts with a reason were noted in this period.";
            return "What pulled you away: " + string.Join(", ", f.Reasons.Take(3).Select(r => $"{Core.Reasons.Label(r.Id).ToLowerInvariant()} {Format.Plural(r.Times, "time")}")) + ".";
        }
        if ((Has("when", "what time") || Has("hour") && !Has("hours")) && f.BestHour is { } h) return $"You focus most around {Insights.HourName(h)}.";
        if (Has("how much", "how long", "total", "hours", "time did i")) return $"{Format.FmtMin(f.Minutes)} over {Blocks(f.Blocks)}.";
        if (Has("tag", "most of", "writing", "reading", "work", "study") && f.TopTag is { } t) return $"{t} took most of it.";
        if (Has("streak", "in a row"))
            return $"{Format.Plural(f.Streak.Days, "day")} in a row" + (f.Streak.PausesLeft > 0 ? ", with a pause left this week." : ", and this week's pause is used.");
        if (Has("cat", "came home", "wander")) return $"{Format.Plural(f.Home, "cat")} came home and {f.Wandered} wandered off.";
        if (Has("how did", "go", "outcome", "done")) return $"{f.Done} done, {f.Partly} partly, {f.NotReally} not really.";
        return "I can answer from the numbers about your best day, when you focus, how much you did, what pulled you away, your tags, your streak and how the blocks went.";
    }
}

// ---------------------------------------------------------------- export

public static class Export
{
    /// <summary>One CSV field: quoted when it has a comma, a quote or a line break.</summary>
    public static string Field(string s) =>
        s.IndexOfAny([',', '"', '\n', '\r']) < 0 ? s : "\"" + s.Replace("\"", "\"\"") + "\"";

    public static string Csv(IEnumerable<IEnumerable<string>> rows) => string.Join("\n", rows.Select(r => string.Join(",", r.Select(Field)))) + "\n";

    public static string BlocksCsv(IEnumerable<Hist> blocks, Func<string, string> catName)
    {
        var rows = new List<string[]> { new[] { "start", "minutes", "title", "tag", "how it went", "drifts", "what pulled away", "cat", "the kitten", "note" } };
        foreach (var b in blocks.OrderBy(b => b.Start))
            rows.Add([
                DateTimeOffset.FromUnixTimeMilliseconds(b.Start).ToLocalTime().ToString("yyyy-MM-dd'T'HH:mm:sszzz", CultureInfo.InvariantCulture),
                b.Actual.ToString(CultureInfo.InvariantCulture), b.Title, b.Tag ?? "", b.Stopped ? "stopped early" : b.Outcome ?? "",
                b.Drifts.ToString(CultureInfo.InvariantCulture), string.Join("; ", b.Whys.Select(Core.Reasons.Label)), catName(b.Cat),
                b.Kitten == "home" ? "came home" : b.Kitten == "gone" ? "wandered off" : "", b.Note,
            ]);
        return Csv(rows);
    }

    public static string WentCsv(IEnumerable<WentRow> rows) =>
        Csv(new[] { new[] { "app", "minutes", "share" } }.Concat(rows.Select(r => new[] { r.Label, r.Minutes.ToString(CultureInfo.InvariantCulture), $"{Math.Round(r.Share * 100, MidpointRounding.AwayFromZero):0}%" })));

    public static string ReviewMarkdown(string title, string range, string summary, WeekFacts f, IReadOnlyList<WentRow> went)
    {
        var l = new List<string>
        {
            $"# {title}", "", $"_{range}_", "", summary, "", $"- Focused: {Format.FmtMin(f.Minutes)} over {f.Blocks} blocks",
            $"- Cats came home: {f.Home}, wandered off: {f.Wandered}", $"- How the blocks went: {f.Done} done, {f.Partly} partly, {f.NotReally} not really",
            $"- Days in a row: {f.Streak.Days}" + (f.Streak.PausedDays.Count == 0 ? "" : " (a pause carried it over a day)"),
        };
        if (f.Reasons.Count > 0) l.AddRange(new[] { "", "## What pulled you away", "" }.Concat(f.Reasons.Select(r => $"- {Core.Reasons.Label(r.Id)}: {r.Times}")));
        if (went.Count > 0) l.AddRange(new[] { "", "## Where the blocks went", "", "_Kept only on this PC._", "" }.Concat(went.Select(r => $"- {r.Label}: {r.Minutes} min ({Math.Round(r.Share * 100, MidpointRounding.AwayFromZero):0}%)")));
        return string.Join("\n", l) + "\n";
    }
}
