@echo off
rem V25-S02/K-02 evidence tool: enable the isolated-tests profile INSIDE this batch file.
rem
rem Why this wrapper exists (measured, see raw/k02-after-hard-reject-*.log):
rem   passing -Pisolated-tests from a pwsh script to mvn.cmd made Maven receive
rem   "-" + "Pisolated-tests" as two arguments -> 'Unknown lifecycle phase "-"'.
rem   The same driver without that switch runs normally, so the defect is in the
rem   evidence driver's invocation layer, not in the code under test.
rem   Putting the switch on this batch file's own command line avoids the
rem   pwsh -> .cmd handoff entirely and is equivalent to running
rem   `mvn ... -Pisolated-tests` by hand.
rem ASCII only on purpose: cmd.exe parses .cmd under the console code page and
rem non-ASCII comment bytes were observed being executed as commands.
set MAVEN_ARGS=-Pisolated-tests
call "D:\apache-maven-3.9.14\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
