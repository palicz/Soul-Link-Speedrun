# Commit Message Examples

This file contains additional examples of commit messages from the project to help maintain consistency.

## Feature Commits

```
feat: Implement extreme difficulty feature for spider attacks (#39)
feat: Add static discharge feature and related settings (#38)
feat: Add swarm intelligence (#26)
feat: persist settings across server restarts (#37)
feat: Implement Manhunt mode with role selection and compass tracking (#35)
feat: Enhance command functionality and settings management (#30)
feat: Add bug report button in settings GUI with Discord invite link (#25)
feat: Add normalization for regeneration effect healing in SharedStatsHandler
feat: Refactor teleportToVanillaSpawn method to use world spawn point coordinates (#16)
feat: Enhance title display to include beta version information dinamically (#17)
feat: Add /reset command to manually reset the current run (#19)
feat: Implement shared jumping feature with deferred processing (#14)
feat: Introduce settings management and shared gameplay features (#13)
```

## Fix Commits

```
fix: Rebased to dev
fix: allow spectator interaction in info settings menu (#34)
fix: Iron golem one shot, and explosion damage (#27)
fix: Normalize world difficulty in settings GUI and update difficulty item representation (#24)
fix: clear Ender Dragon bossbar when starting a new run (#23)
fix: round damage display to nearest 0.5 hearts (#22)
fix: allow players with a Totem of Undying to survive lethal damage by letting the totem activate during damage processing.
fix: added methods to handle dimension change advancements.
fix: syntax error in settings.json
```

## Refactor Commits

```
refactor: Update difficulty handling to use RunDifficulty type across settings and GUI
refactor: Simplify welcome message by removing header and footer lines, and adding a settings tip for customization (#18)
Refactor/project choir (#20)
```

## Chore Commits

```
chore: Add plan.md to .gitignore for better file management
chore: update mod icon to latest (#31)
```

## CI/CD Commits

```
ci: Update release workflow to support prerelease tagging
CI: Add automated release workflow
```

## Code Commits

```
code: added coderabbitai configuration file
```

## Pattern Analysis

### Common Patterns

1. **Feature with scope**: `feat: Add <feature> to <component>`
2. **Feature with PR**: `feat: <description> (#<number>)`
3. **Fix with detail**: `fix: <issue description>`
4. **Settings-related**: Often includes "in settings GUI" or "in <component>"
5. **Normalization**: Uses "Normalize" for consistency fixes
6. **Enhancement**: Uses "Enhance" for improvements to existing features

### Description Style

- Imperative mood: "Add", "Fix", "Update", "Implement"
- Specific and descriptive
- Mentions affected components when relevant
- Includes technical details when important (e.g., "with deferred processing")
