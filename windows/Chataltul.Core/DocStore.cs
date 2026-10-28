using System.Text;

namespace Chataltul.Core;

/// <summary>The doc: one JSON file in the app's folder, the same shape the web keeps in the browser.</summary>
public sealed class DocStore
{
    public string File { get; }

    public DocStore(string folder)
    {
        Directory.CreateDirectory(folder);
        File = Path.Join(folder, "doc.json");
    }

    public string? Read()
    {
        try
        {
            var s = System.IO.File.ReadAllText(File, Encoding.UTF8);
            return s.Length == 0 ? null : s;
        }
        catch (FileNotFoundException) { return null; }
        catch (DirectoryNotFoundException) { return null; }
    }

    /// <summary>Written whole and swapped in, so a crash in the middle never leaves half a doc.</summary>
    public void Write(string json) => Files.WriteWhole(File, json);

    /// <summary>A doc that doesn't load is kept aside, never written over.</summary>
    public string? SetAside()
    {
        var to = $"{File}.bad-{Format.NowMs()}";
        try { System.IO.File.Copy(File, to); return to; }
        catch (Exception) { return null; }
    }
}

public static class Files
{
    /// <summary>A file written beside itself and moved into place in one step.</summary>
    public static void WriteWhole(string path, string text)
    {
        var temp = $"{path}.{Environment.ProcessId}.tmp";
        System.IO.File.WriteAllText(temp, text, new UTF8Encoding(false));
        System.IO.File.Move(temp, path, overwrite: true);
    }
}
