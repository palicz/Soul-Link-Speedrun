---
name: git-commit-generator
description: Generates conventional commit messages by analyzing git diffs and learning from project commit history. Use when the user asks for help writing commit messages, reviewing staged changes, or needs commit message suggestions based on code changes.
---

# Git Commit Message Generator

Generates conventional commit messages by analyzing git diffs and matching the project's commit message style.

## Workflow

When generating a commit message:

1. **Analyze the changes**: Run `git diff --cached` or `git diff` to see what changed
2. **Identify change type**: Determine if changes are features, fixes, refactors, chores, etc.
3. **Match project style**: Follow the conventional commit format used in this project
4. **Generate message**: Create a concise, descriptive commit message

## Commit Message Format

This project uses **Conventional Commits** format:

```
<type>: <description>

[optional body]

[optional footer with PR number]
```

### Commit Types Used in This Project

- `feat:` - New features or functionality
- `fix:` - Bug fixes
- `refactor:` - Code restructuring without changing behavior
- `chore:` - Maintenance tasks, dependencies, config changes
- `ci:` or `CI:` - CI/CD pipeline changes
- `code:` - General code changes

### Style Guidelines

1. **Type prefix**: Always lowercase (e.g., `feat:`, `fix:`, not `Feat:` or `Fix:`)
2. **Description**: Start with capital letter, use imperative mood ("Add feature" not "Added feature")
3. **Length**: Keep description concise but descriptive
4. **PR numbers**: Include in footer when applicable: `(#39)`
5. **Scope**: Optional scope in parentheses: `feat(settings): Add new option`

### Examples from Project History

**Feature additions:**
```
feat: Implement extreme difficulty feature for spider attacks (#39)
feat: Add static discharge feature and related settings (#38)
feat: Add swarm intelligence (#26)
feat: persist settings across server restarts (#37)
feat: Implement Manhunt mode with role selection and compass tracking (#35)
```

**Bug fixes:**
```
fix: Rebased to dev
fix: allow spectator interaction in info settings menu (#34)
fix: Iron golem one shot, and explosion damage (#27)
fix: clear Ender Dragon bossbar when starting a new run (#23)
fix: round damage display to nearest 0.5 hearts (#22)
```

**Refactoring:**
```
refactor: Update difficulty handling to use RunDifficulty type across settings and GUI
refactor: Simplify welcome message by removing header and footer lines, and adding a settings tip for customization (#18)
```

**Maintenance:**
```
chore: Add plan.md to .gitignore for better file management
chore: update mod icon to latest (#31)
```

## Generating Commit Messages

### Step 1: Analyze Changes

Run:
```bash
git diff --cached
```

Or for unstaged changes:
```bash
git diff
```

### Step 2: Identify Change Type

- **New files/features** → `feat:`
- **Bug fixes** → `fix:`
- **Code restructuring** → `refactor:`
- **Config/build changes** → `chore:`
- **CI/CD changes** → `ci:`

### Step 3: Write Description

- Use imperative mood ("Add", "Fix", "Update", not "Added", "Fixed", "Updated")
- Be specific about what changed
- Focus on the "what" and "why", not implementation details
- Keep it under 72 characters for the subject line

### Step 4: Format Message

Follow the pattern:
```
<type>: <description>
```

If the change addresses an issue or PR, add:
```
<type>: <description> (#<number>)
```

## Common Patterns

**Adding new functionality:**
```
feat: Add <feature name> with <key capability>
```

**Fixing bugs:**
```
fix: <describe the issue that was fixed>
```

**Refactoring:**
```
refactor: <describe what was restructured>
```

**Settings/Configuration:**
```
feat: Add <setting name> option to <location>
fix: Normalize <setting> in <location>
```

## Best Practices

1. **One logical change per commit**: If multiple unrelated changes, suggest separate commits
2. **Be descriptive**: "fix: Iron golem one shot" is better than "fix: bug"
3. **Match existing style**: Look at recent commits to maintain consistency
4. **Include context**: Mention affected components when relevant (e.g., "in settings GUI")
5. **Use present tense**: "Add feature" not "Added feature"

## Additional Resources

- For more commit message examples and pattern analysis, see [examples.md](examples.md)
