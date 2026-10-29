using System.Runtime.InteropServices;

namespace Chataltul;

/// <summary>
/// The tasks on Chataltul's taskbar button and Start menu entry (a right-click): Start 25, Start 50, Park a thought
/// and Open the café, as the phone's long press on the app's icon has them. Each starts Chataltul with --do, which a
/// running Chataltul is handed (MessageWindow.WakeTheOther). Windows keeps the list, so it is written once a start.
/// </summary>
static class JumpList
{
    public static readonly (string Command, string Title, string Description)[] Tasks =
    [
        ("start:25", "Start 25", "A 25-minute block"),
        ("start:50", "Start 50", "A 50-minute block"),
        ("park", "Park a thought", "Put something in Later without leaving what you're doing"),
        ("cafe", "Open the café", "Today, your café and your history"),
    ];

    /// <summary>Writes the list. Nothing in it is needed, so a failure is only noted.</summary>
    public static void Write()
    {
        if (App.Options.SelfTest || Environment.ProcessPath is not { } exe) return;
        try
        {
            var list = (ICustomDestinationList)Activator.CreateInstance(Type.GetTypeFromCLSID(CLSID_DestinationList)!)!;
            var iidArray = typeof(IObjectArray).GUID;
            list.BeginList(out _, ref iidArray, out _);
            var tasks = (IObjectCollection)Activator.CreateInstance(Type.GetTypeFromCLSID(CLSID_EnumerableObjectCollection)!)!;
            foreach (var (command, title, description) in Tasks)
            {
                var link = (IShellLinkW)Activator.CreateInstance(Type.GetTypeFromCLSID(CLSID_ShellLink)!)!;
                link.SetPath(exe);
                link.SetArguments($"--do {command}");
                link.SetDescription(description);
                link.SetIconLocation(exe, 0);
                var props = (IPropertyStore)link;
                var key = new PROPERTYKEY { fmtid = new Guid("F29F85E0-4FF9-1068-AB91-08002B27B3D9"), pid = 2 };   // PKEY_Title
                var value = new PROPVARIANT { vt = 31, pointer = Marshal.StringToCoTaskMemUni(title) };       // VT_LPWSTR
                try
                {
                    props.SetValue(ref key, ref value);
                    props.Commit();
                }
                finally { Marshal.FreeCoTaskMem(value.pointer); }
                tasks.AddObject(link);
            }
            list.AddUserTasks((IObjectArray)tasks);
            list.CommitList();
        }
        catch (Exception e) { Log.Write($"the jump list: {e.Message}"); }
    }

    static readonly Guid CLSID_DestinationList = new("77F10CF0-3DB5-4966-B520-B7C54FD35ED6");
    static readonly Guid CLSID_EnumerableObjectCollection = new("2D3468C1-36A7-43B6-AC24-D3F02FD9607A");
    static readonly Guid CLSID_ShellLink = new("00021401-0000-0000-C000-000000000046");

    [ComImport, Guid("6332DEBF-87B5-4670-90C0-5E57B408A49E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface ICustomDestinationList
    {
        void SetAppID([MarshalAs(UnmanagedType.LPWStr)] string id);
        void BeginList(out uint minSlots, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out object removed);
        void AppendCategory([MarshalAs(UnmanagedType.LPWStr)] string category, [MarshalAs(UnmanagedType.Interface)] IObjectArray items);
        void AppendKnownCategory(int category);
        void AddUserTasks([MarshalAs(UnmanagedType.Interface)] IObjectArray tasks);
        void CommitList();
        void GetRemovedDestinations(ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out object removed);
        void DeleteList([MarshalAs(UnmanagedType.LPWStr)] string? id);
        void AbortList();
    }

    [ComImport, Guid("92CA9DCD-5622-4BBA-A805-5E9F541BD8C9"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IObjectArray
    {
        void GetCount(out uint count);
        void GetAt(uint index, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out object item);
    }

    [ComImport, Guid("5632B1A4-E38A-400A-928A-D4CD63230295"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IObjectCollection
    {
        // IObjectArray's two first, as the vtable has them
        void GetCount(out uint count);
        void GetAt(uint index, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out object item);
        void AddObject([MarshalAs(UnmanagedType.Interface)] object item);
        void AddFromArray([MarshalAs(UnmanagedType.Interface)] IObjectArray source);
        void RemoveObjectAt(uint index);
        void Clear();
    }

    [ComImport, Guid("000214F9-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IShellLinkW
    {
        void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder file, int max, IntPtr findData, uint flags);
        void GetIDList(out IntPtr idList);
        void SetIDList(IntPtr idList);
        void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder name, int max);
        void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string name);
        void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder dir, int max);
        void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string dir);
        void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder args, int max);
        void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string args);
        void GetHotkey(out short hotkey);
        void SetHotkey(short hotkey);
        void GetShowCmd(out int show);
        void SetShowCmd(int show);
        void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] System.Text.StringBuilder path, int max, out int index);
        void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string path, int index);
        void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string path, uint reserved);
        void Resolve(IntPtr hwnd, uint flags);
        void SetPath([MarshalAs(UnmanagedType.LPWStr)] string file);
    }

    [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    interface IPropertyStore
    {
        void GetCount(out uint count);
        void GetAt(uint index, out PROPERTYKEY key);
        void GetValue(ref PROPERTYKEY key, out PROPVARIANT value);
        void SetValue(ref PROPERTYKEY key, ref PROPVARIANT value);
        void Commit();
    }

    [StructLayout(LayoutKind.Sequential)]
    struct PROPERTYKEY { public Guid fmtid; public uint pid; }

    /// <summary>A PROPVARIANT holding a string: its type, three reserved words, then the pointer (16 bytes on 32-bit, 24 on 64-bit).</summary>
    [StructLayout(LayoutKind.Sequential)]
    struct PROPVARIANT { public ushort vt, r1, r2, r3; public IntPtr pointer; public IntPtr extra; }
}
