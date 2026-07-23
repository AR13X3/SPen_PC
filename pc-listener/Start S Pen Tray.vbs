' Launches the S Pen Pointer tray app with no visible console window.
' Double-click this file to start it. To auto-start at login, put a shortcut
' to this file in your Startup folder (Win+R -> shell:startup).
Set fso = CreateObject("Scripting.FileSystemObject")
Set shell = CreateObject("WScript.Shell")
shell.CurrentDirectory = fso.GetParentFolderName(WScript.ScriptFullName)
shell.Run "python tray_app.py", 0, False
