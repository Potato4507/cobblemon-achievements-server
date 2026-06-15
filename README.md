# Cobblemon Achievements Server

Server-side Fabric mod for Cobblemon 1.7.3 on Minecraft 1.21.1.

## Features

- OP-managed defeat achievements for specific players.
- Target players can toggle whether their achievement is active.
- Server-side Cobblemon battle victory checks.
- Snapshot export of every online player's party and PC Pokemon.
- Export to local JSON files, an HTTP endpoint, or GitHub Contents API.
- Signed remote manifest support for GitHub/IPFS-style data and safe staged update checks.
- Server-side player list/nameplate badges using vanilla scoreboard teams.
- Pokemon type badges and gym leader tags that work without a client-side install.
- Elite 4 tags with matching defeat achievements.
- Owner-only team import presets, including a built-in Ice team.
- Owner-only client bridge for server snapshots and dashboard team uploads.

## Requirements

- Minecraft server: `1.21.1`
- Fabric Loader: `0.19.2` or newer
- Fabric API: `0.116.11+1.21.1` or compatible
- Fabric Language Kotlin: `1.13.6+kotlin.2.2.20` or compatible
- Cobblemon Fabric: `1.7.3`
- Java: `21`

## Download And Install

Download the source from GitHub:

```powershell
git clone https://github.com/Potato4507/cobblemon-achievements-server.git
cd cobblemon-achievements-server
```

Build the server mod:

```powershell
.\gradlew.bat build
```

If Java is not on your PATH, set `JAVA_HOME` first:

```powershell
$env:JAVA_HOME = "C:\Path\To\Java21"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat build
```

Install the built jar:

```powershell
Copy-Item .\build\libs\cobblemon-achievements-server-0.1.9.jar "C:\Path\To\Your\Server\mods\"
```

Then restart the Minecraft server. Do not put this jar in every player's client mods folder; this is a server-side mod.

## Short Badge Commands

Use `/b help` in-game for the quick command guide.

```mcfunction
/b help
/b types
/b set <player> <type>
/b gym <player> on [type]
/b gym <player> off
/b clear <player>
/b list
```

Examples:

```mcfunction
/b set Steve ground
/b gym Steve on ground
/b clear Steve
```

Aliases:

```mcfunction
/badge ...
/typebadge ...
/cach badge ...
```

Valid badge types:

```text
Normal, Fire, Water, Electric, Grass, Ice, Fighting, Poison, Ground, Flying, Psychic, Bug, Rock, Ghost, Dragon, Dark, Steel, Fairy
```

Notes:

- Badge commands require OP permission.
- Badge commands now accept normal player names, even when that player is offline.
- Badges are saved in `config/cobblemon-achievements-server.json`.
- `playerBadgesEnabled` can turn all badge display on or off.
- `playerBadgesOverrideExistingTeams` defaults to `false`, so badges will not steal players from scoreboard teams created by another mod/plugin.
- Set `playerBadgesOverrideExistingTeams` to `true` only if you want this mod to force badge display over other scoreboard teams.

## Easy Achievement Commands

Use `/ach help` in-game for the quick achievement guide.

```mcfunction
/ach help
/ach <player> [title]
/ach add <player> [title]
/ach set <player> [title]
/ach title <player> <title>
/ach remove <player>
/ach list
/ach status
/ach on
/ach off
/ach enable
/ach disable
```

Examples:

```mcfunction
/ach Steve
/ach Steve Defeated the Ground Gym Leader
/ach add Steve
/ach add Steve Defeated the Ground Gym Leader
/ach title Steve "Defeated the Ground Gym Leader"
/ach remove Steve
```

Command notes:

- `/ach add <player> [title]` creates the achievement people earn by beating that player.
- `/ach <player> [title]` is the shortest OP setup form.
- Titles can have spaces. Quotes are optional; `/ach title Steve "Ground Gym Badge"` and `/ach title Steve Ground Gym Badge` both work.
- Extra spaces around titles are cleaned up automatically.
- You usually do not need to set an achievement ID. The mod makes one automatically.
- `/ach add`, `/ach remove`, and `/ach list` require OP.
- `/ach on`, `/ach off`, `/ach enable`, and `/ach disable` let a configured target player control whether people can earn their achievement.
- Achievement commands accept normal player names. If the player is offline, the target migrates to their UUID when they next battle.
- Advanced ID override: `/ach id <player> <id> [title]`.

## Elite 4 Commands

Use `/e4 help` in-game for the quick Elite 4 guide.

```mcfunction
/e4 help
/e4 <player> <type> [title]
/e4 title <player> <title>
/e4 remove <player>
/e4 list
```

Examples:

```mcfunction
/e4 Steve ice
/e4 Steve ice Defeated Steve of the Ice Elite Four
/e4 title Steve Defeated the Ice Elite Four
/e4 remove Steve
```

Notes:

- `/e4 <player> <type> [title]` creates both the Elite 4 player-list tag and the defeat achievement.
- The visible player-list/nameplate prefix is `Elite 4 <type> <name>`, for example `Elite 4 Dragon Steve`.
- Titles can have spaces; quotes are optional.
- Elite 4 setup commands require OP permission.

## Owner Team Import

Use `/team help` in-game for the quick import guide.

```mcfunction
/team ice
/team import <preset-or-json-path>
/team list
```

Examples:

```mcfunction
/team ice
/team import ice
/team import D:\teams\my-team.json
```

Notes:

- Team import is owner-only and is locked to the built-in owner UUID.
- The built-in preset is `ice`.
- Pokemon import into your party first, then overflow to your PC if the party is full.
- JSON imports accept the same optimizer-style `team` array used by the built-in preset.

## Owner Hub Client Bridge

Version `0.1.7` adds a private bridge for your client-side coach mod and the local Owner Hub dashboard.

What it does:

- `/cach snapshot toclient` can send a full online player snapshot to your client.
- The client dashboard can request that snapshot without typing the command in-game.
- The client dashboard can upload a team JSON/import into the server mod.
- Team uploads are owner-only and still check the configured owner UUID on the server.

The matching client-side coach mod saves snapshots under:

```text
<Modrinth profile game dir>\cobblemon-owner-hub\snapshots\latest.json
```

The outside-Minecraft dashboard runs locally on your PC and sends small command files to the client mod. Minecraft still needs to be open and connected to the server for live snapshot requests and team uploads.

## Other Commands

```mcfunction
/cach help
/cach reload
/cach snapshot now
/cach snapshot toclient
/cach owner pokemon <pokemon-properties>
/cach owner item <item> [count]
/cach remote status
/cach remote refresh
/cach target add <player> [achievementId] [title]
/cach target remove <player>
/cach target list
```

- `/cach target ...` is the older advanced achievement command path.
- `/cach snapshot now` exports the current server snapshot to `<server game dir>/cobblemon-achievements-snapshots/latest.json` and a timestamped JSON file in that same folder.
- `/cach snapshot toclient` sends the full server snapshot to the configured owner's client mod.
- `/cach owner pokemon <pokemon-properties>` gives you a Pokemon and uses Cobblemon Pokemon autocomplete. Alias: `/cach owner mon`.
- `/cach owner item <item> [count]` gives you an item and autocompletes registered item IDs. Alias: `/cach owner giveitem`.
- Item names can be full IDs like `cobblemon:relic_coin`; plain names like `relic_coin` try Minecraft, then Cobblemon, then a unique matching mod item path.
- `/cach remote status` and `/cach remote refresh` require owner access.
- `/cach remote refresh` runs in the background so a slow GitHub check will not freeze the server tick thread.

## Remote Manifest

- Publish `remote/manifest.signed.json` to GitHub raw, IPFS, or another static file host.
- The default GitHub raw manifest URL is already configured:
  `https://raw.githubusercontent.com/Potato4507/cobblemon-achievements-server/main/remote/manifest.signed.json`
- Put the new jar URL in `downloadUrl`, or put base64 chunk URLs in `downloadBase64Chunks`.
- The mod verifies the Ed25519 manifest signature, requires HTTPS URLs, checks `downloadSha256`, validates the downloaded jar metadata, and enforces size limits before staging an update.
- The mod does **not** overwrite the running server jar automatically. Verified updates are staged under `<server game dir>/cobblemon-achievements-remote/updates/`.
- On Modrinth hosting, stop the server, replace the old `cobblemon-achievements-server-*.jar` in `mods`, then start the server again. The staged folder includes `INSTALL-README.txt`, `install-staged-update.ps1`, and `install-staged-update.sh` for manual installs.
- Add fallback URLs to `remoteManifestUrls` in `config/cobblemon-achievements-server.json`.
- `remoteUpdateDownloadEnabled` now means "download and stage verified updates"; it does not mean live auto-install.
- The unsigned payload lives at `remote/manifest.payload.json`.
- Sign it with `tools/sign-manifest.mjs`.
- Keep `local-secrets/manifest-private-key.pem` private. It is ignored by git.

Signing example:

```powershell
$env:COBBLE_ACHIEVEMENTS_MANIFEST_PRIVATE_KEY_PEM = Get-Content -Raw local-secrets/manifest-private-key.pem
node tools/sign-manifest.mjs
```
