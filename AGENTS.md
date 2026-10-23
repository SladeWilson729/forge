# AGENTS.md: Forge Arena (fork of Card-Forge/forge)

## What this repo is
A fork of the Forge MTG rules engine. We add ONE new Maven module, `forge-arena-server`,
which runs Forge headless as a game server for a Unity client (separate repo, not here).
The full plan lives outside this repo: `C:\rea\ForgeArena\2026-10-23_ForgeArena_ProjectPlan.md`.

## Hard rules
- Never modify existing Forge modules (`forge-core`, `forge-game`, `forge-ai`, `forge-gui`,
  `forge-gui-desktop`, `forge-gui-mobile*`, `forge-gui-android`, `forge-gui-ios`,
  `forge-installer`, `forge-lda`, `adventure-editor`). All our code goes in
  `forge-arena-server/`. This keeps monthly upstream merges clean.
- The only existing file you may edit is the root `pom.xml`, and only its `<modules>` list.
  If a task seems to need any other existing file changed, stop and explain why first.
- Read the real Forge source before using a class or method. Never guess an API.
- No reflection into Forge internals. No new third-party dependencies unless the task names them.
- Work on branch `arena`. Commit messages are a single line. Never push; the owner reviews first.
- If a checklist step fails twice, stop and report instead of improvising.

## Environment
- Windows 11, Windows PowerShell 5.1, Temurin JDK 17, Maven 3.9.16.
- Put `--%` right after `mvn` in every Maven command, or PowerShell splits `-D` arguments at the dots:
  `mvn --% -pl forge-arena-server -am test -Dtest=SomeTest -Dsurefire.failIfNoSpecifiedTests=false`
- `JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8` is set for the user; a "Picked up JAVA_TOOL_OPTIONS" line is expected.
- Anything that runs Forge needs the VM options in `forge-arena-server/jvm.options`
  (the `--add-opens` list, `-Dfile.encoding=UTF-8`, `-Xmx4096m`, `-Djava.awt.headless=true`).

## Useful references in this repo
- Headless boot: `forge-gui-desktop/src/test/java/forge/net/HeadlessGuiDesktop.java`, `TestUtils.java`
- In-process bridge template: `forge-gui-desktop/src/test/java/forge/net/embedded/ProtocolGuiGameInProcessTest.java`
- Protocol catalog: `forge-gui/src/main/java/forge/gamemodes/net/ProtocolMethod.java`
- Yield and auto-pass: `docs/Advanced-Yield-Options.md`
- Network play: `docs/Network-Play.md`, `docs/Development/Network-Testing.md`

## Report format at the end of every task
1. Files created or changed (with the root `pom.xml` diff if touched).
2. The exact output of each "Done when" command.
3. Any Forge API that differed from the task instructions, and what you did instead.
