@echo off
rem V25-S02/K-04 evidence tool: wrap mysql-stub.ps1 so it can be passed as -MysqlExe.
rem Forwards all arguments and the exit code. Establishes no database connection.
rem NOTE ON ARGUMENT FORWARDING - measured, three failed variants before this one:
rem   a) pwsh -File stub.ps1 %*  -> run-demo passes -e and -N -B, which pwsh tried to bind as
rem      script parameters: "parameter name 'e' is ambiguous". Stub exited 1 without logging.
rem   b) pwsh -Command "& stub.ps1 @args" -- %*  -> PowerShell parses the mysql SQL in the
rem      command text ("unrecognized token").
rem   c) %* round-trips through the mysql options (they contain = and ;), so no re-quoting of
rem      any kind works reliably.
rem   d) therefore the argv handoff goes through FILE, never through a command line: this .cmd
rem      writes the arguments to a per-process temp file and the script reads them back.
rem      Nothing is re-parsed, so --port=3307, -e, and SQL text with ; survive verbatim.
rem      cmd's `for` tokenises unquoted on = and , so the script joins the lines with spaces
rem      before matching; the S52 SQL has no spaces, so no SQL text is broken up.
rem ASCII ONLY on purpose: cmd.exe parses .cmd under the console code page and non-ASCII
rem comment bytes were observed being executed as commands.
setlocal EnableDelayedExpansion
set "K04_STUB_ARGV=%TEMP%\k04-stub-argv-%RANDOM%-%RANDOM%.txt"
(for %%A in (%*) do @echo(%%~A)>"%K04_STUB_ARGV%"
pwsh -NoProfile -File "%~dp0mysql-stub.ps1"
set "RC=%ERRORLEVEL%"
del "%K04_STUB_ARGV%" >nul 2>&1
set "K04_STUB_ARGV="
endlocal & exit /b %RC%
