using System.Runtime.InteropServices;
using System.Text.Json;
using System.Text.Json.Nodes;
using Chataltul.Core;

namespace Chataltul;

/// <summary>The session cookie in Windows' Credential Manager, as the Mac keeps it in the Keychain: only this Windows account can read it.</summary>
sealed class CredentialSession(string target) : ISessionStore
{
    public string? Cookie
    {
        get
        {
            if (!CredRead(target, 1, 0, out var p)) return null;
            try
            {
                var c = Marshal.PtrToStructure<CREDENTIAL>(p);
                if (c.CredentialBlobSize == 0 || c.CredentialBlob == IntPtr.Zero) return null;
                var s = Marshal.PtrToStringUni(c.CredentialBlob, (int)c.CredentialBlobSize / 2);
                return s.Length == 0 ? null : s;
            }
            finally { CredFree(p); }
        }
        set
        {
            if (value == null) { CredDelete(target, 1, 0); return; }
            var blob = Marshal.StringToCoTaskMemUni(value);
            try
            {
                var c = new CREDENTIAL
                {
                    Type = 1, TargetName = target, UserName = "session", Persist = 2,   // CRED_TYPE_GENERIC, CRED_PERSIST_LOCAL_MACHINE
                    CredentialBlob = blob, CredentialBlobSize = (uint)(value.Length * 2),
                };
                if (!CredWrite(ref c, 0)) Log.Write($"the session didn't save ({Marshal.GetLastPInvokeError()})");
            }
            finally { Marshal.FreeCoTaskMem(blob); }
        }
    }

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    struct CREDENTIAL
    {
        public uint Flags, Type;
        public string TargetName;
        public string? Comment;
        public long LastWritten;
        public uint CredentialBlobSize;
        public IntPtr CredentialBlob;
        public uint Persist, AttributeCount;
        public IntPtr Attributes;
        public string? TargetAlias;
        public string UserName;
    }

    [DllImport("advapi32.dll", EntryPoint = "CredReadW", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool CredRead(string target, uint type, uint flags, out IntPtr credential);
    [DllImport("advapi32.dll", EntryPoint = "CredWriteW", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool CredWrite(ref CREDENTIAL credential, uint flags);
    [DllImport("advapi32.dll", EntryPoint = "CredDeleteW", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern bool CredDelete(string target, uint type, uint flags);
    [DllImport("advapi32.dll")]
    static extern void CredFree(IntPtr buffer);
}

/// <summary>
/// Signing in, and keeping the doc in step with the web app's server: the same API and the same rules as the phone
/// and the Mac (web/data.js). Without an account nothing leaves the PC. Signed in, each change goes up a moment
/// later, and what other devices did comes back when the app opens and every 30 seconds, merged by the engine
/// (E.merge) exactly as the web merges it. Everything here runs on the UI thread; only the calls wait elsewhere.
/// The Mac's SyncController.swift.
/// </summary>
sealed class SyncController
{
    readonly Store store;
    SyncClient client;
    int rev;
    bool syncing, again;
    CancellationTokenSource? pending;
    Microsoft.UI.Dispatching.DispatcherQueueTimer? poll;
    readonly string stateFile;

    public Account? Account { get; private set; }
    /// <summary>"too big" while the server won't take this PC's copy, even folded.</summary>
    public string? Trouble { get; private set; }
    /// <summary>Whether the server sends email (GET /api/config); null until asked.</summary>
    public bool? EmailOn { get; private set; }
    public bool Busy { get; private set; }
    /// <summary>The address a sign-in code went to.</summary>
    public string? CodeSentTo { get; set; }
    /// <summary>What went wrong, in words for the person.</summary>
    public string? Message { get; set; }
    public DateTime? LastSync { get; private set; }
    /// <summary>Anything above changed, for Settings.</summary>
    public event Action? Changed;

    public SyncController(Store store, ISessionStore session)
    {
        this.store = store;
        stateFile = Path.Join(store.Folder, "sync.json");
        client = new SyncClient(store.Settings.Server, session);
        try
        {
            if (File.Exists(stateFile) && JsonNode.Parse(File.ReadAllText(stateFile)) is JsonObject s)
            {
                rev = s["rev"]?.GetValue<int>() ?? 0;
                Account = Account.From(s["account"]);
            }
        }
        catch (Exception e) { Log.Write($"sync.json doesn't read: {e.Message}"); }
        // a session without an account on record (or the other way round) is no sign-in
        if (Account != null && client.Session.Cookie == null) { Account = null; rev = 0; Save(); }
    }

    public bool SignedIn => Account != null;
    public string Server => client.Base.ToString();
    public int Rev => rev;

    void Save()
    {
        var o = new JsonObject { ["rev"] = rev };
        if (Account is { } a)
            o["account"] = new JsonObject { ["name"] = a.Name, ["display"] = a.Display, ["email"] = a.Email, ["hasPassword"] = a.HasPassword };
        try { Files.WriteWhole(stateFile, o.ToJsonString()); } catch (Exception e) { Log.Write($"sync.json didn't save: {e.Message}"); }
    }

    void Tell() => Changed?.Invoke();

    /// <summary>The server may have been changed in Settings (the self-test does).</summary>
    void RefreshClient()
    {
        if (store.Settings.Server != client.Base.ToString() && store.Settings.Server.TrimEnd('/') != client.Base.ToString().TrimEnd('/'))
            client = new SyncClient(store.Settings.Server, client.Session);
    }

    // ---------------------------------------------------------------- when to sync

    /// <summary>The app opened: who this is now (a password changed elsewhere signs this PC out), then what's new.</summary>
    public void Opened()
    {
        poll?.Stop();
        poll = Ui.Every(TimeSpan.FromSeconds(30), Pull);
        if (Account == null) return;
        _ = Run(async () => { await RefreshMe(); await DoPull(); });
    }

    /// <summary>After a change: send it up in a moment (several changes in a row go as one).</summary>
    public void Queue()
    {
        if (Account == null) return;
        pending?.Cancel();
        var cts = pending = new CancellationTokenSource();
        Ui.After(TimeSpan.FromMilliseconds(700), () => { if (!cts.IsCancellationRequested) _ = Push(); });
    }

    public void Pull()
    {
        if (Account == null) return;
        _ = DoPull();
    }

    // ---------------------------------------------------------------- the sync

    public async Task Push()
    {
        if (Account == null) return;
        RefreshClient();
        if (syncing) { again = true; return; }
        syncing = true;
        var squeezed = false;
        try
        {
            for (var i = 0; i < 4; i++)
            {
                try
                {
                    var doc = store.DocJson();
                    var r = await client.Api("POST", "api/sync", raw: $"{{\"rev\":{rev},\"doc\":{doc}}}");
                    rev = Int(r?["rev"]) ?? rev;
                    Save();
                    Trouble = null;
                    LastSync = DateTime.Now;
                    break;
                }
                catch (ApiError e)
                {
                    // another device synced first: theirs merged in, and this goes up again
                    if (e.Status == 409 && e.Body is { } b) { Merge(b["doc"], Int(b["rev"]) ?? rev); continue; }
                    if (e.Status == 413) { if (Squeeze(squeezed)) { squeezed = true; continue; } break; }
                    if (e.Status == 401) Forget();
                    break;
                }
                catch (Exception)
                {
                    // offline, or a doc past what the server reads at all (it cuts the connection instead of answering)
                    var big = store.Act("size")?.Result is { } res && res.ValueKind == JsonValueKind.Object && res.Bool("big");
                    if (big && Squeeze(squeezed)) { squeezed = true; continue; }
                    break;
                }
            }
        }
        finally
        {
            syncing = false;
            Tell();
            if (again) { again = false; Queue(); }
        }
    }

    bool Squeeze(bool already)
    {
        if (already) { Trouble = "too big"; return false; }
        store.Act("squeeze");
        return true;
    }

    async Task DoPull()
    {
        if (Account == null || syncing) return;
        RefreshClient();
        try
        {
            var r = await client.Api("GET", $"api/sync?rev={rev}");
            if (r == null) return;   // nothing new is a 204
            if (Merge(r["doc"], Int(r["rev"]) ?? rev)) Queue();
            LastSync = DateTime.Now;
            Tell();
        }
        catch (ApiError e) { if (e.Status == 401) Forget(); }
        catch (Exception) { }
    }

    /// <summary>The server's copy merged into this PC's; true if the result has something the server's doesn't.</summary>
    bool Merge(JsonNode? theirs, int newRev, bool adopt = false)
    {
        if (theirs == null) return false;
        var json = $"{{\"op\":\"merge\",\"adopt\":{(adopt ? "true" : "false")},\"doc\":{theirs.ToJsonString()}}}";
        var o = store.ActJson(json, "merge");
        if (o == null || o.Error != null) return false;
        rev = newRev;
        Save();
        return o.Result is { ValueKind: JsonValueKind.Object } res && res.Bool("mineToo");
    }

    static int? Int(JsonNode? n) =>
        n is JsonValue v && v.TryGetValue(out double d) ? (int)d : null;

    // ---------------------------------------------------------------- signing in and out

    /// <summary>Asked once a server (a second try only after the server changes): the Account page asks each time it's built.</summary>
    string? configAsked;

    public void LoadConfig()
    {
        RefreshClient();
        if (EmailOn != null || configAsked == Server) return;
        configAsked = Server;
        _ = Run(async () =>
        {
            try
            {
                var r = await client.Api("GET", "api/config");
                EmailOn = r?["email"] is JsonValue v && v.TryGetValue(out bool on) && on;
            }
            catch (Exception e)
            {
                Message = "Can't reach the server.";
                Log.Write($"config: {e.Message}");
            }
        }, quietly: true);
    }

    /// <summary>A 6-digit code to this address. It signs in, or makes the account if there isn't one.</summary>
    public void SendCode(string email)
    {
        var e = email.Trim();
        if (!e.Contains('@')) { Message = "That doesn't look like an email address."; Tell(); return; }
        _ = Run(async () =>
        {
            await client.Api("POST", "api/email/code", new JsonObject { ["email"] = e });
            CodeSentTo = e;
        });
    }

    public void SignIn(string email, string code)
    {
        var e = email.Trim();
        var c = code.Trim();
        _ = Run(async () =>
        {
            var r = await client.Api("POST", "api/email/signin", new JsonObject { ["email"] = e, ["code"] = c });
            var created = r?["created"] is JsonValue v && v.TryGetValue(out bool b) && b;
            await Joined(r, existing: !created);
            CodeSentTo = null;
        }, asking: "code");
    }

    /// <summary><c>kind</c> "signup" or "login": a name or an email, and the password.</summary>
    public void SignIn(string kind, string name, string password) => _ = Run(async () =>
    {
        var r = await client.Api("POST", kind == "signup" ? "api/signup" : "api/login", new JsonObject { ["name"] = name, ["password"] = password });
        await Joined(r, existing: kind == "login");
    }, asking: "password");

    /// <summary>After any sign-in this PC's history comes along into the account.</summary>
    async Task Joined(JsonObject? me, bool existing)
    {
        SetAccount(me);
        try
        {
            if (await client.Api("GET", "api/sync?rev=-1") is { } r) Merge(r["doc"], Int(r["rev"]) ?? 0, adopt: existing);
        }
        catch (Exception e) { Log.Write($"the first pull: {e.Message}"); }
        await Push();
    }

    void SetAccount(JsonObject? me)
    {
        if (Account.From(me) is not { } a) return;
        Account = a;
        Save();
        Tell();
    }

    async Task RefreshMe()
    {
        if (Account == null) return;
        try { if (await client.Api("GET", "api/me") is { } me) SetAccount(me); }
        catch (ApiError e) { if (e.Status == 401) Forget(); }
        catch (Exception) { }
    }

    /// <summary>A password for the account (one that signed in by email has none). <c>current</c> is asked for when it has one.</summary>
    public void SetPassword(string password, string? current) => _ = Run(async () =>
    {
        var body = new JsonObject { ["password"] = password };
        if (!string.IsNullOrEmpty(current)) body["current"] = current;
        SetAccount(await client.Api("POST", "api/account/password", body));
        Message = "The password is set.";
    }, asking: "password");

    /// <summary>The account's history leaves this PC on sign out (it's safe on the server).</summary>
    public void SignOut() => _ = Run(async () =>
    {
        await Push();
        try { await client.Api("POST", "api/logout"); } catch (Exception) { }
        Forget();
        store.Act("empty");
    });

    /// <summary>For deleting an account without a password: a code to its email, which stands in for it.</summary>
    public void SendDeleteCode() => _ = Run(async () =>
    {
        var r = await client.Api("POST", "api/account/code");
        CodeSentTo = r?["sent"]?.GetValue<string>() ?? Account?.Email;
    });

    /// <summary>Deletes the server's copy; this PC keeps its own. <c>proof</c> is ("password", …) or ("code", …).</summary>
    public void DeleteAccount(string kind, string proof) => _ = Run(async () =>
    {
        await client.Api("POST", "api/account/delete", new JsonObject { [kind] = proof });
        CodeSentTo = null;
        Forget();
    }, asking: kind);

    /// <summary>Signed out here, or elsewhere: a password changed on another device, or the account deleted.</summary>
    void Forget()
    {
        pending?.Cancel();
        client.Session.Cookie = null;
        Account = null;
        rev = 0;
        Trouble = null;
        CodeSentTo = null;
        Save();
        Tell();
    }

    // ---------------------------------------------------------------- plumbing

    /// <summary><c>asking</c>: what the person was doing, so a refusal can be put in the right words ("code" or "password").</summary>
    async Task Run(Func<Task> body, string asking = "", bool quietly = false)
    {
        RefreshClient();
        if (!quietly) { Busy = true; Message = null; Tell(); }
        // a call can fail before its first await: the page that started it is never rebuilt inside its own build
        await Task.Yield();
        try { await body(); }
        catch (ApiError e) { Message = SyncClient.Words(e, asking); }
        catch (Exception e)
        {
            Message = "Can't reach the server.";
            Log.Write($"sync: {e.Message}");
        }
        Busy = false;
        Tell();
    }
}
