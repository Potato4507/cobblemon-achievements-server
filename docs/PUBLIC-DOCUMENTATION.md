# Cobbleverse Public Mod Guide

This guide covers the public server-side Cobblemon features players and staff may use.

## What The Mod Adds

- Defeat achievements for beating configured players.
- Type tags next to player names.
- Gym Leader tags.
- Elite 4 tags.
- Simple commands for staff to manage those tags.

## Achievement Basics

Some players can be configured as achievement targets. When another player defeats an active target in a Cobblemon battle, the winner earns that target's achievement.

Target players can control whether their own achievement is active.

```mcfunction
/ach status
/ach on
/ach off
```

Aliases:

```mcfunction
/ach enable
/ach disable
/ach me
```

## Player Type Tags

Type tags appear in the player list/nameplate.

Example display:

```text
[DRAGON] Steve
```

Staff commands:

```mcfunction
/b help
/b types
/b set <player> <type>
/b clear <player>
/b list
```

Examples:

```mcfunction
/b set Steve dragon
/b clear Steve
```

Command aliases:

```mcfunction
/badge ...
/typebadge ...
```

Valid types:

```text
Normal, Fire, Water, Electric, Grass, Ice, Fighting, Poison, Ground, Flying, Psychic, Bug, Rock, Ghost, Dragon, Dark, Steel, Fairy
```

## Gym Leader Tags

Gym Leader tags can be added with a type.

Example display:

```text
[GYM] [GROUND] Steve
```

Staff commands:

```mcfunction
/b gym <player> on [type]
/b gym <player> off
```

Examples:

```mcfunction
/b gym Steve on ground
/b gym Steve off
```

## Elite 4 Tags

Elite 4 tags display as:

```text
Elite 4 <type> <name>
```

Example:

```text
Elite 4 Dragon Steve
```

Staff commands:

```mcfunction
/e4 help
/e4 <player> <type> [achievement title]
/e4 title <player> <achievement title>
/e4 remove <player>
/e4 list
```

Examples:

```mcfunction
/e4 Steve dragon
/e4 Steve dragon Defeated Steve of the Dragon Elite Four
/e4 title Steve Defeated the Dragon Elite Four
/e4 remove Steve
```

Aliases:

```mcfunction
/elite4 ...
/elitefour ...
```

## Staff Achievement Setup

Staff can create or remove defeat achievements.

```mcfunction
/ach help
/ach <player> [title]
/ach add <player> [title]
/ach title <player> <title>
/ach remove <player>
/ach list
```

Examples:

```mcfunction
/ach Steve
/ach Steve Defeated the Ground Gym Leader
/ach title Steve Ground Gym Badge
/ach remove Steve
```

Notes:

- Titles can contain spaces.
- Quotes are optional.
- Achievement IDs are generated automatically.
- Offline player names are accepted and are migrated to UUIDs when the player is next seen.

## Quick Help

Use these in-game:

```mcfunction
/ach help
/b help
/e4 help
```

If a command does not appear, make sure you have the right permission level.
