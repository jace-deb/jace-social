; Windows installer for the Jace Social desktop app (Inno Setup 6).
; Installs per user (no admin needed), like Discord.
#define AppVersion GetEnv("APP_VERSION")
#define SourceDir GetEnv("APP_SOURCE")

[Setup]
AppId={{6C3E2B7A-5F0E-4B4E-9A51-JACESOCIAL01}
AppName=Jace Social
AppVersion={#AppVersion}
AppPublisher=jace.deb
AppPublisherURL=https://jace-deb.github.io/jace-social/
DefaultDirName={localappdata}\Programs\Jace Social
DefaultGroupName=Jace Social
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputBaseFilename=JaceSocial-{#AppVersion}-windows-x64-setup
OutputDir=..\dist
SetupIconFile=..\assets\icon.ico
UninstallDisplayIcon={app}\JaceSocial.exe
Compression=lzma2/max
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
WizardStyle=modern
CloseApplications=force

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"
Name: "startup"; Description: "Start Jace Social when I sign in to Windows (in the tray)"; GroupDescription: "Startup:"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Jace Social"; Filename: "{app}\JaceSocial.exe"
Name: "{userdesktop}\Jace Social"; Filename: "{app}\JaceSocial.exe"; Tasks: desktopicon
Name: "{userstartup}\Jace Social"; Filename: "{app}\JaceSocial.exe"; Parameters: "--hidden"; Tasks: startup

[Run]
Filename: "{app}\JaceSocial.exe"; Description: "Open Jace Social"; Flags: nowait postinstall skipifsilent
