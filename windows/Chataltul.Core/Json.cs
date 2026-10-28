using System.Text.Json;
using System.Text.Json.Serialization;

namespace Chataltul.Core;

/// <summary>
/// Reading what the engine hands back: every field has a default, so a field the engine adds or drops
/// never stops the app (the Mac's JSONValue.swift and its decoders do the same).
/// </summary>
public static class J
{
    public static JsonElement? Get(this JsonElement e, string name) =>
        e.ValueKind == JsonValueKind.Object && e.TryGetProperty(name, out var v) && v.ValueKind != JsonValueKind.Null && v.ValueKind != JsonValueKind.Undefined ? v : null;

    public static string Str(this JsonElement e, string name, string fallback = "") =>
        e.Get(name) is { ValueKind: JsonValueKind.String } v ? v.GetString() ?? fallback : fallback;

    public static string? OptStr(this JsonElement e, string name) =>
        e.Get(name) is { ValueKind: JsonValueKind.String } v ? v.GetString() : null;

    public static long Long(this JsonElement e, string name, long fallback = 0) => e.OptLong(name) ?? fallback;

    public static long? OptLong(this JsonElement e, string name)
    {
        if (e.Get(name) is not { ValueKind: JsonValueKind.Number } v) return null;
        if (v.TryGetInt64(out var l)) return l;
        return (long)Math.Round(v.GetDouble());
    }

    public static int Int(this JsonElement e, string name, int fallback = 0) => (int)(e.OptLong(name) ?? fallback);

    public static int? OptInt(this JsonElement e, string name) => e.OptLong(name) is { } l ? (int)l : null;

    public static double Double(this JsonElement e, string name, double fallback = 0) =>
        e.Get(name) is { ValueKind: JsonValueKind.Number } v ? v.GetDouble() : fallback;

    public static bool Bool(this JsonElement e, string name, bool fallback = false) =>
        e.Get(name) is { } v ? v.ValueKind switch { JsonValueKind.True => true, JsonValueKind.False => false, _ => fallback } : fallback;

    public static List<T> Items<T>(this JsonElement e, string name, Func<JsonElement, T> read) =>
        e.Get(name) is { ValueKind: JsonValueKind.Array } a ? a.EnumerateArray().Select(read).ToList() : [];

    public static List<string> Strings(this JsonElement e, string name) =>
        e.Get(name) is { ValueKind: JsonValueKind.Array } a
            ? a.EnumerateArray().Where(x => x.ValueKind == JsonValueKind.String).Select(x => x.GetString() ?? "").ToList()
            : [];

    public static Dictionary<string, int> IntMap(this JsonElement e, string name)
    {
        var d = new Dictionary<string, int>();
        if (e.Get(name) is not { ValueKind: JsonValueKind.Object } o) return d;
        foreach (var p in o.EnumerateObject())
            if (p.Value.ValueKind == JsonValueKind.Number) d[p.Name] = p.Value.TryGetInt32(out var i) ? i : (int)Math.Round(p.Value.GetDouble());
        return d;
    }

    public static Dictionary<string, string> StrMap(this JsonElement e, string name)
    {
        var d = new Dictionary<string, string>();
        if (e.Get(name) is not { ValueKind: JsonValueKind.Object } o) return d;
        foreach (var p in o.EnumerateObject())
            if (p.Value.ValueKind == JsonValueKind.String) d[p.Name] = p.Value.GetString() ?? "";
        return d;
    }

    /// <summary>The settings files: camelCase keys, enums by name, and keys this version doesn't know left alone.</summary>
    public static readonly JsonSerializerOptions Files = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        PropertyNameCaseInsensitive = true,
        WriteIndented = true,
        Converters = { new JsonStringEnumConverter(JsonNamingPolicy.CamelCase) },
    };

    /// <summary>An op for the engine: the fields as given, written as JSON.</summary>
    public static string Op(string op, IReadOnlyDictionary<string, object?>? fields)
    {
        var o = new Dictionary<string, object?>();
        if (fields != null) foreach (var (k, v) in fields) o[k] = v;
        o["op"] = op;
        return JsonSerializer.Serialize(o);
    }
}
