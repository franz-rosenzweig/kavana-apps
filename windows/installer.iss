; Chataltul for Windows: an installer for one person, which needs no administrator. It copies what dotnet
; publish made (build\Chataltul) to %LOCALAPPDATA%\Programs\Chataltul, puts Chataltul in the Start menu, and
; starts it. Made by windows\build.ps1 and the Windows workflow:
;
;   iscc /DVersion=0.1.0 windows\installer.iss
;
; Uninstalling takes away the app and its start at sign-in, and leaves the doc and the settings in
; %LOCALAPPDATA%\Chataltul.

#ifndef Version
  #define Version "0.0.0"
#endif
#ifndef Source
  #define Source "..\build\Chataltul"
#endif

[Setup]
AppId={{3C9A1F64-58B2-4D7E-A1C3-9E2F7B5D0A48}
AppName=Chataltul
AppVersion={#Version}
AppVerName=Chataltul {#Version}
AppPublisher=franz-rosenzweig
AppPublisherURL=https://github.com/franz-rosenzweig/kavana-apps
AppSupportURL=https://github.com/franz-rosenzweig/kavana-apps/blob/main/windows/README.md
DefaultDirName={localappdata}\Programs\Chataltul
DisableDirPage=yes
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
MinVersion=10.0.17763
OutputDir=..\build\out
OutputBaseFilename=Chataltul-{#Version}-Setup
Compression=lzma2/max
SolidCompression=yes
WizardStyle=modern
SetupIconFile={#Source}\Assets\Chataltul.ico
UninstallDisplayIcon={app}\Chataltul.exe
UninstallDisplayName=Chataltul
; a running Chataltul is asked to quit (it does, on the message Windows sends at sign-out) and started again after
CloseApplications=force
RestartApplications=no

[Tasks]
Name: startup; Description: "Open Chataltul when I sign in to Windows"; GroupDescription: "When to start it:"

[InstallDelete]
; an update leaves none of the old version's files behind
Type: filesandordirs; Name: "{app}\js"
Type: filesandordirs; Name: "{app}\art"

[Files]
Source: "{#Source}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{autoprograms}\Chataltul"; Filename: "{app}\Chataltul.exe"

[Registry]
; the same value Settings › General › Open Chataltul when I sign in writes
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: string; ValueName: "Chataltul"; ValueData: """{app}\Chataltul.exe"" --login"; Flags: uninsdeletevalue; Tasks: startup
Root: HKCU; Subkey: "Software\Microsoft\Windows\CurrentVersion\Run"; ValueType: none; ValueName: "Chataltul"; Flags: uninsdeletevalue dontcreatekey

[Run]
Filename: "{app}\Chataltul.exe"; Description: "{cm:LaunchProgram,Chataltul}"; Flags: nowait postinstall skipifsilent
