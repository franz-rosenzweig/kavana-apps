using System.IO.Compression;
using System.Text;

namespace Chataltul.Core;

/// <summary>A PNG file from pixels, for the tray icon's pictures that the tests and the self-test keep.</summary>
public static class Png
{
    /// <summary><c>rgba</c>: four bytes a pixel, rows from the top, straight alpha.</summary>
    public static byte[] Encode(int width, int height, byte[] rgba)
    {
        if (rgba.Length != width * height * 4) throw new ArgumentException("the pixels don't fill the picture");
        using var file = new MemoryStream();
        file.Write(new byte[] { 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A });
        var head = new byte[13];
        BigEndian(head, 0, (uint)width);
        BigEndian(head, 4, (uint)height);
        head[8] = 8;   // bits a channel
        head[9] = 6;   // RGBA
        Chunk(file, "IHDR", head);
        var raw = new byte[height * (width * 4 + 1)];
        for (var y = 0; y < height; y++)
            Buffer.BlockCopy(rgba, y * width * 4, raw, y * (width * 4 + 1) + 1, width * 4);   // filter 0 on each row
        using var packed = new MemoryStream();
        using (var z = new ZLibStream(packed, CompressionLevel.Optimal, leaveOpen: true)) z.Write(raw, 0, raw.Length);
        Chunk(file, "IDAT", packed.ToArray());
        Chunk(file, "IEND", []);
        return file.ToArray();
    }

    /// <summary>BGRA (what Windows' bitmaps hold) to RGBA.</summary>
    public static byte[] FromBgra(byte[] bgra)
    {
        var o = new byte[bgra.Length];
        for (var i = 0; i < bgra.Length; i += 4)
        {
            o[i] = bgra[i + 2]; o[i + 1] = bgra[i + 1]; o[i + 2] = bgra[i]; o[i + 3] = bgra[i + 3];
        }
        return o;
    }

    static void Chunk(Stream s, string type, byte[] data)
    {
        var len = new byte[4];
        BigEndian(len, 0, (uint)data.Length);
        s.Write(len, 0, 4);
        var body = new byte[4 + data.Length];
        Encoding.ASCII.GetBytes(type, 0, 4, body, 0);
        Buffer.BlockCopy(data, 0, body, 4, data.Length);
        s.Write(body, 0, body.Length);
        var crc = new byte[4];
        BigEndian(crc, 0, Crc(body));
        s.Write(crc, 0, 4);
    }

    static void BigEndian(byte[] b, int at, uint v)
    {
        b[at] = (byte)(v >> 24); b[at + 1] = (byte)(v >> 16); b[at + 2] = (byte)(v >> 8); b[at + 3] = (byte)v;
    }

    static readonly uint[] Table = MakeTable();

    static uint[] MakeTable()
    {
        var t = new uint[256];
        for (uint n = 0; n < 256; n++)
        {
            var c = n;
            for (var k = 0; k < 8; k++) c = (c & 1) != 0 ? 0xEDB88320 ^ (c >> 1) : c >> 1;
            t[n] = c;
        }
        return t;
    }

    static uint Crc(byte[] data)
    {
        var c = 0xFFFFFFFFu;
        foreach (var b in data) c = Table[(c ^ b) & 0xFF] ^ (c >> 8);
        return c ^ 0xFFFFFFFFu;
    }
}
