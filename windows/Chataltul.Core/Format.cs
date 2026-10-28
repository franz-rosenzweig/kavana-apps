using System.Globalization;

namespace Chataltul.Core;

/// <summary>The words and numbers the screens share (the Mac's Format.swift, web/app.js's fmtMin and fmtClock).</summary>
public static class Format
{
    /// <summary>The time now, in milliseconds since 1970 (what the engine counts in).</summary>
    public static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    /// <summary>A block's name where one has to be shown: its title, which is optional, else its tag, else "Focus".</summary>
    public static string TitleOf(string intention, string? tag) =>
        !string.IsNullOrEmpty(intention) ? intention : !string.IsNullOrEmpty(tag) ? tag : "Focus";

    /// <summary>"25 min", "1 h 5 min".</summary>
    public static string FmtMin(int m) => m < 60 ? $"{m} min" : $"{m / 60} h" + (m % 60 != 0 ? $" {m % 60} min" : "");

    /// <summary>"04:59", "1:02:03".</summary>
    public static string FmtClock(long ms)
    {
        var s = Math.Max(0, (ms + 999) / 1000);
        long h = s / 3600, m = s % 3600 / 60, sec = s % 60;
        return h > 0 ? $"{h}:{m:00}:{sec:00}" : $"{m:00}:{sec:00}";
    }

    /// <summary>Whole minutes left, never under one.</summary>
    public static int MinLeft(long ms) => Math.Max(1, (int)Math.Round(ms / 60000.0, MidpointRounding.AwayFromZero));

    /// <summary>"3:58 PM", in this PC's own clock style.</summary>
    public static string ClockTime(long ms) =>
        DateTimeOffset.FromUnixTimeMilliseconds(ms).ToLocalTime().ToString("t", CultureInfo.CurrentCulture);

    public static DateTime Local(long ms) => DateTimeOffset.FromUnixTimeMilliseconds(ms).ToLocalTime().DateTime;

    public static long Ms(DateTime local) => new DateTimeOffset(local).ToUnixTimeMilliseconds();

    public static bool IsToday(long ms, long now) => Local(ms).Date == Local(now).Date;

    /// <summary>"1 cat", "2 cats".</summary>
    public static string Plural(int n, string one, string? many = null) => $"{n} {(n == 1 ? one : many ?? one + "s")}";
}

/// <summary>What pulled the person away, asked after Drifted: the engine's ids (E.REASONS) and their words.</summary>
public static class Reasons
{
    public static readonly string[] Ids = ["phone", "messages", "email", "web", "tired", "hungry", "thoughts", "people", "other"];

    /// <summary>The few shown on the cat's card, quick to tap.</summary>
    public static readonly string[] Quick = ["phone", "web", "messages", "thoughts", "tired"];

    public static string Label(string id) => id switch
    {
        "phone" => "Phone", "messages" => "Messages", "email" => "Email", "web" => "The web", "thoughts" => "Thoughts",
        "people" => "People", "tired" => "Tired", "hungry" => "Hunger", "other" => "Other",
        _ => id.Length > 0 ? char.ToUpperInvariant(id[0]) + id[1..] : id,
    };
}
