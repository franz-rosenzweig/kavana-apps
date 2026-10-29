using System.Net;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace Chataltul.Core;

/// <summary>The signed-in account (the server's /api/me).</summary>
public sealed record Account(string Name, string? Display = null, string? Email = null, bool HasPassword = true)
{
    public static Account? From(JsonNode? me)
    {
        if (me is not JsonObject o) return null;
        var name = o["name"]?.GetValueKind() == JsonValueKind.String ? o["name"]!.GetValue<string>() : "";
        string? Opt(string k) => o[k]?.GetValueKind() == JsonValueKind.String ? o[k]!.GetValue<string>() : null;
        // accounts from before email sign-in all have a password
        var has = o["hasPassword"] is { } h && h.GetValueKind() is JsonValueKind.True or JsonValueKind.False ? h.GetValue<bool>()
            : o["password"] is { } p && p.GetValueKind() is JsonValueKind.True or JsonValueKind.False ? p.GetValue<bool>() : true;
        return new Account(name, Opt("display"), Opt("email"), has);
    }

    /// <summary>What to call the account in the app.</summary>
    public string Label => Email ?? Display ?? Name;
}

/// <summary>Where the session cookie is kept: Windows' Credential Manager for the real app, a plain file for a test copy.</summary>
public interface ISessionStore
{
    string? Cookie { get; set; }
}

public sealed class FileSession(string file) : ISessionStore
{
    public string? Cookie
    {
        get
        {
            try { var s = File.ReadAllText(file); return s.Length == 0 ? null : s; }
            catch (Exception) { return null; }
        }
        set
        {
            if (value != null) Files.WriteWhole(file, value);
            else try { File.Delete(file); } catch (Exception) { }
        }
    }
}

/// <summary>An answer that wasn't OK, with the server's word for why ("email", "no email") if it gave one.</summary>
public sealed class ApiError(int status, string? error, JsonObject? body) : Exception(error ?? $"HTTP {status}")
{
    public int Status { get; } = status;
    public string? Error { get; } = error;
    public JsonObject? Body { get; } = body;
}

/// <summary>
/// One call at a time to the web app's API: the same one the web page, the phone and the Mac use. The session is
/// the server's cookie, kept as a browser keeps it. The Mac's SyncClient.swift.
/// </summary>
public sealed class SyncClient
{
    public const string CookieName = "kavana";
    public Uri Base { get; }
    public ISessionStore Session { get; }
    static readonly HttpClient Http = new(new SocketsHttpHandler { UseCookies = false, AllowAutoRedirect = false })
    {
        Timeout = TimeSpan.FromSeconds(30),
    };

    public SyncClient(string server, ISessionStore session)
    {
        Base = new Uri(server.EndsWith('/') ? server : server + "/");
        Session = session;
    }

    /// <summary>Is this server one on this PC (a sandbox)? Tests that make accounts may only talk to one of these.</summary>
    public bool IsLocal => Base.Host is "127.0.0.1" or "localhost" or "[::1]" or "::1";

    /// <summary>
    /// A call. Throws ApiError for an answer that isn't OK, HttpRequestException (or a timeout) when the server can't be
    /// reached. <c>raw</c> is a body already written as JSON (the doc, which is too big to parse only to write again).
    /// </summary>
    public async Task<JsonObject?> Api(string method, string path, JsonObject? body = null, string? raw = null)
    {
        using var req = new HttpRequestMessage(new HttpMethod(method), new Uri(Base, path));
        req.Headers.Accept.Add(new MediaTypeWithQualityHeaderValue("application/json"));
        if (Session.Cookie is { } c) req.Headers.TryAddWithoutValidation("Cookie", $"{CookieName}={c}");
        if (method != "GET")
            req.Content = new StringContent(raw ?? (body ?? []).ToJsonString(), Encoding.UTF8, "application/json");
        using var res = await Http.SendAsync(req).ConfigureAwait(false);
        TakeCookie(res);
        var text = await res.Content.ReadAsStringAsync().ConfigureAwait(false);
        JsonObject? obj = null;
        if (res.StatusCode != HttpStatusCode.NoContent && text.Length > 0)
            try { obj = JsonNode.Parse(text) as JsonObject; } catch (JsonException) { }
        var status = (int)res.StatusCode;
        if (status is < 200 or >= 300)
            throw new ApiError(status, obj?["error"]?.GetValueKind() == JsonValueKind.String ? obj["error"]!.GetValue<string>() : null, obj);
        return obj;
    }

    /// <summary>"kavana=&lt;token&gt;; Path=/; Max-Age=…": a new session, a renewed one, or (empty, Max-Age=0) none.</summary>
    void TakeCookie(HttpResponseMessage r)
    {
        if (!r.Headers.TryGetValues("Set-Cookie", out var headers)) return;
        foreach (var h in headers)
            if (ReadCookie(h) is { } v) Session.Cookie = v.Length == 0 ? null : v;
    }

    /// <summary>The session from one Set-Cookie header: its value, "" for one that ends it, null when it's another cookie.</summary>
    public static string? ReadCookie(string header)
    {
        var h = header.TrimStart();
        if (!h.StartsWith(CookieName + "=", StringComparison.Ordinal)) return null;
        var semi = h.IndexOf(';');
        var value = (semi < 0 ? h : h[..semi])[(CookieName.Length + 1)..].Trim();
        var dead = h.Contains("max-age=0", StringComparison.OrdinalIgnoreCase);
        return dead ? "" : value;
    }

    /// <summary>A server's refusal in words for the person. <c>asking</c>: "code" or "password", what they were doing.</summary>
    public static string Words(ApiError e, string asking = "")
    {
        var m = (e.Error ?? "").ToLowerInvariant();
        return e.Status switch
        {
            429 => "Too many tries. Wait a few minutes.",
            401 when asking == "code" => "That code didn't work. Ask for a new one.",
            401 when asking == "password" => "That name or password didn't work.",
            400 when m == "email" => "That doesn't look like an email address.",
            404 when m == "no email" => "This server doesn't send email. Use a name and a password.",
            _ => e.Error is { Length: > 0 } w ? char.ToUpperInvariant(w[0]) + w[1..] + "." : $"The server had a problem ({e.Status}).",
        };
    }
}
