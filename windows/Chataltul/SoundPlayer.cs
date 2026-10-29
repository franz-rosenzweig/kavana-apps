using Chataltul.Core;
using Microsoft.UI.Dispatching;
using Windows.Media.Core;
using Windows.Media.Playback;

namespace Chataltul;

/// <summary>
/// Background sounds, up to two at once, while a block runs. The recordings are the web's own
/// (&lt;server&gt;/audio/&lt;file&gt;.mp3): each is downloaded the first time it is picked and kept in the app's folder,
/// then looped. Brown and white noise are made on the PC. They fade in and out, and a kitten that comes home meows.
/// <c>muted</c> (the self-test) does everything but play: nothing is heard on the machine that runs it. The Mac's
/// SoundPlayer.swift, with Windows' own media player.
/// </summary>
sealed class SoundPlayer(Store store, bool muted)
{
    sealed class Voice(string id, MediaPlayer? player, double vol)
    {
        public string Id { get; } = id;
        public MediaPlayer? Player { get; } = player;
        public double Vol { get; set; } = vol;
        public DispatcherQueueTimer? Fade;
    }

    readonly Dictionary<string, Voice> voices = [];
    readonly HashSet<string> downloading = [];
    readonly Dictionary<string, DateTime> failedAt = [];
    static readonly HttpClient Http = new() { Timeout = TimeSpan.FromMinutes(2) };
    MediaPlayer? meowPlayer;
    UiState? last;

    /// <summary>Where the recordings are kept.</summary>
    public string Cache => Path.Join(store.Folder, "audio");

    /// <summary>The sounds that are playing now (for the self-test and the Sound widget).</summary>
    public List<string> Playing => [.. voices.Keys.Order()];

    /// <summary>Sounds are for a running block: they play while it runs and stop on a pause, a stop or the end.</summary>
    public void Update(UiState st)
    {
        last = st;
        var playing = st.View.Active is { Paused: null };
        var want = playing ? store.PlayableMix : new List<MixEntry>();
        foreach (var id in voices.Keys.Where(id => want.All(m => m.Id != id)).ToList()) Stop(id);
        foreach (var m in want)
        {
            if (voices.TryGetValue(m.Id, out var v)) { v.Vol = m.Vol * 0.8; FadeTo(v, v.Vol, 0.2); continue; }
            Start(m);
        }
    }

    void Refresh() { if (last != null) Update(store.State); }

    public void StopAll()
    {
        foreach (var id in voices.Keys.ToList()) Stop(id, 0.05);
    }

    // ---------------------------------------------------------------- one voice

    void Start(MixEntry m)
    {
        if (store.Sounds.Kind(m.Id) is not { } kind) return;
        if (kind.File is not { } file) { Begin(m, Noise(kind.Id)); return; }
        var path = Path.Join(Cache, $"{file}.mp3");
        if (File.Exists(path)) { Begin(m, path); return; }
        _ = Download(file, path);
    }

    void Begin(MixEntry m, string? path)
    {
        if (voices.ContainsKey(m.Id) || path == null) return;
        MediaPlayer? p = null;
        if (!muted)
        {
            try
            {
                p = new MediaPlayer { IsLoopingEnabled = true, Volume = 0, Source = MediaSource.CreateFromUri(new Uri(path)) };
                p.Play();
            }
            catch (Exception e) { Log.Write($"sound {m.Id}: {e.Message}"); p = null; }
        }
        var v = new Voice(m.Id, p, m.Vol * 0.8);
        voices[m.Id] = v;
        FadeTo(v, v.Vol, 2);
    }

    void Stop(string id, double over = 1.2)
    {
        if (!voices.Remove(id, out var v)) return;
        FadeTo(v, 0, over, () =>
        {
            try { v.Player?.Pause(); v.Player?.Dispose(); } catch (Exception) { }
        });
    }

    void FadeTo(Voice v, double target, double seconds, Action? done = null)
    {
        v.Fade?.Stop();
        var p = v.Player;
        if (p == null) { done?.Invoke(); return; }
        var from = p.Volume;
        var steps = Math.Max(1, (int)(seconds * 30));
        var i = 0;
        v.Fade = Ui.Every(TimeSpan.FromSeconds(seconds / steps), () =>
        {
            i++;
            try { p.Volume = from + (target - from) * i / steps; } catch (Exception) { }
            if (i < steps) return;
            v.Fade?.Stop();
            v.Fade = null;
            done?.Invoke();
        });
    }

    // ---------------------------------------------------------------- recordings

    async Task Download(string file, string path)
    {
        if (downloading.Contains(file)) return;
        if (failedAt.TryGetValue(file, out var f) && DateTime.Now - f < TimeSpan.FromSeconds(60)) return;
        downloading.Add(file);
        try
        {
            var server = store.Settings.Server.EndsWith('/') ? store.Settings.Server : store.Settings.Server + "/";
            var bytes = await Http.GetByteArrayAsync(new Uri(new Uri(server), $"audio/{file}.mp3"));
            if (bytes.Length <= 10_000) throw new InvalidDataException("too small to be a recording");
            Directory.CreateDirectory(Cache);
            Files.WriteBytesWhole(path, bytes);
        }
        catch (Exception e)
        {
            failedAt[file] = DateTime.Now;
            Log.Write($"sound {file}: {e.Message}");
        }
        downloading.Remove(file);
        Refresh();
    }

    // ---------------------------------------------------------------- noise and the meow

    /// <summary>Brown or white noise as a WAV file of half a minute, made once and looped.</summary>
    string? Noise(string id)
    {
        var path = Path.Join(Cache, $"noise-{id}.wav");
        if (File.Exists(path)) return path;
        try
        {
            Directory.CreateDirectory(Cache);
            const int rate = 44_100, seconds = 30;
            var samples = new short[rate * seconds];
            var r = new Random(id == "brown" ? 7 : 11);
            double brown = 0;
            for (var i = 0; i < samples.Length; i++)
            {
                var w = r.NextDouble() * 2 - 1;
                double s;
                if (id == "brown") { brown = (brown + 0.02 * w) / 1.02; s = brown * 3.2; } else s = w * 0.5;
                samples[i] = (short)Math.Clamp(s * short.MaxValue, short.MinValue, short.MaxValue);
            }
            Files.WriteBytesWhole(path, Wav(samples, rate));
            return path;
        }
        catch (Exception e) { Log.Write($"noise {id}: {e.Message}"); return null; }
    }

    static byte[] Wav(short[] samples, int rate)
    {
        using var m = new MemoryStream();
        using var w = new BinaryWriter(m);
        var data = samples.Length * 2;
        w.Write("RIFF"u8); w.Write(36 + data); w.Write("WAVE"u8);
        w.Write("fmt "u8); w.Write(16); w.Write((short)1); w.Write((short)1); w.Write(rate); w.Write(rate * 2); w.Write((short)2); w.Write((short)16);
        w.Write("data"u8); w.Write(data);
        foreach (var s in samples) w.Write(s);
        w.Flush();
        return m.ToArray();
    }

    /// <summary>A kitten that comes home says hello (one of the web's three meows, carried in the app).</summary>
    public void Meow(bool home)
    {
        if (!home || muted || store.Sounds.Meows.Count == 0) return;
        var name = store.Sounds.Meows[Random.Shared.Next(store.Sounds.Meows.Count)];
        var file = Path.Join(AppContext.BaseDirectory, "audio", $"{name}.mp3");
        if (!File.Exists(file)) return;
        try
        {
            meowPlayer?.Dispose();
            meowPlayer = new MediaPlayer { Volume = 0.7, Source = MediaSource.CreateFromUri(new Uri(file)) };
            meowPlayer.Play();
        }
        catch (Exception e) { Log.Write($"meow: {e.Message}"); }
    }
}
