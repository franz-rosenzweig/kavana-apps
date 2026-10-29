using System.Text.Json;
using Jint;
using Jint.Native;
using Jint.Native.Object;

namespace Chataltul.Core;

/// <summary>
/// Chataltul's engine: the web app's own lines.js, cats.js, engine.js and news.js, and the Mac's bridge.js
/// (mac/Chataltul/Resources/js, copied from the web by mac/tools/sync-web.sh and never edited), run in Jint.
/// One engine holds the doc between calls. Jint is not thread-safe: the app calls this from its one UI
/// thread. The Mac's is EngineHost.swift, in JavaScriptCore.
/// </summary>
public sealed class EngineHost
{
    public sealed class Failure(string message) : Exception(message);

    /// <summary>cats.js comes before engine.js: the engine names a cat from it when it writes a ping.</summary>
    public static readonly string[] Files = ["lines.js", "cats.js", "engine.js", "news.js", "bridge.js"];

    readonly Engine engine = new();
    readonly ObjectInstance kb;

    /// <summary><c>js</c> is the folder with the five files.</summary>
    public EngineHost(string js)
    {
        foreach (var f in Files)
        {
            var path = Path.Join(js, f);
            if (!File.Exists(path)) throw new Failure($"missing {f}");
            try { engine.Execute(File.ReadAllText(path)); }
            catch (Exception e) { throw new Failure($"{f}: {e.Message}"); }
        }
        var v = engine.GetValue("KB");
        if (!v.IsObject()) throw new Failure("the bridge didn't load");
        kb = v.AsObject();
    }

    string Call(string fn, object?[] args)
    {
        try { return engine.Invoke(kb.Get(fn), kb, args).ToString(); }
        catch (Exception e) { throw new Failure($"KB.{fn}: {e.Message}"); }
    }

    /// <summary>The doc from the app's file (a JSON string), or a new one for null.</summary>
    public void Load(string? json) => Call("load", [json]);

    /// <summary>The doc as JSON, for the file.</summary>
    public string Dump() => Call("dump", []);

    /// <summary>Runs a piece of JavaScript beside the engine and gives back what it returns, as a string (the tests time the engine's parts with it).</summary>
    public string Evaluate(string js)
    {
        try { return engine.Evaluate(js).ToString(); }
        catch (Exception e) { throw new Failure($"evaluate: {e.Message}"); }
    }

    /// <summary>A minute of <c>ms</c> milliseconds, for tests that can't wait.</summary>
    public void Minute(int ms) => Call("minute", [ms]);

    /// <summary>One change (or just a tick) at <c>now</c>. Returns everything the screens need.</summary>
    public Out Run(string op, IReadOnlyDictionary<string, object?>? fields = null, long? now = null) =>
        RunJson(J.Op(op, fields), now);

    /// <summary>The same, from an op already written as JSON.</summary>
    public Out RunJson(string json, long? now = null)
    {
        var raw = Call("run", [json, (double)(now ?? Format.NowMs())]);
        try
        {
            using var d = JsonDocument.Parse(raw);
            return new Out(d.RootElement);
        }
        catch (JsonException e) { throw new Failure($"the engine's answer didn't read: {e.Message}"); }
    }
}
