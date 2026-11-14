# Agent skills

Droide uses one `SKILL.md` catalog for the built-in agent, the chat composer,
Settings, and external CLI agents connected to the Droide IDE MCP bridge. Existing
project/plugin skills remain supported. No migration or startup scan is required.

## User workflow

Open **Settings → Skills**, or enter `/skills` in chat. The existing User/Workspace
selector chooses the destination. User skills are private to this installation and
available across projects; workspace skills are saved in
`.droide/skills/<name>/SKILL.md`. A workspace skill overrides a personal skill with
the same name. Each scope in Settings can still manage its own copy.

Create a skill using the editable Markdown form, or import `SKILL.md` / a ZIP with
one skill folder. ZIP imports retain relative references, scripts, and other assets.
Imports do not execute scripts. Existing skills open for inspection; managed skills
can be edited or removed. Removing a skill deletes only its entry point, preserving
supporting files. Plugin skills and skills in other project conventions are read-only
in this form: edit the owning source or create a separately named copy.

```markdown
---
name: review-code
description: Review changes for correctness and maintainability
user-invocable: true
disable-model-invocation: false
---
Read the changed files and relevant callers. Identify reproducible issues.
Read references/checklist.md when reviewing a database migration.
```

New names contain 1–64 lowercase letters/digits with single hyphens. A description
and nonempty body are required. Legacy case/underscore names remain readable, with a 256-character discovery limit. Unknown
frontmatter does not grant permissions. `user-invocable: false` omits the skill from
composer suggestions. `disable-model-invocation: true` omits it from automatic model
discovery; an explicit user selection can still load it. These are discovery controls,
not filesystem access controls.

Typing `/` or `@` offers named skills alongside existing shortcuts. Selecting a
skill inserts `/skill/review-code` or `@skill/review-code`, leaves the draft editable,
and does not send it. Plugin skill identifiers include the plugin namespace, for
example `@skill/reviewer:review-code`. `/skills` itself is local navigation; commands
with attachments continue through the normal chat flow. The selected identifier
instructs the agent to load the skill before using it; it does not insert every skill
body into the conversation or grant tool permissions.

## Catalog and loading

The precedence is `.droide/skills`, `.agents/skills`, `.github/skills`,
`.copilot/skills`, `.agent/skills`, `.claude/skills`, `.opencode/skills`, personal
skills, then integrity-checked enabled plugin contributions. Plugin names are
namespaced. Personal storage is `filesDir/agent-skills`, outside each project.

Discovery reads frontmatter on demand. It opens no MCP processes and performs no
network requests or recursive workspace search. Standard roots inspect at most 512
immediate entries and accept up to 100 skill directories; the effective catalog is
limited to 160 unique skills. Tool descriptions expose at most 40 summaries, and
`operation=list` searches/pages that bounded catalog. Composer suggestions are loaded
once when entering a completion query, not for every keystroke. Settings loads only
when opened or refreshed. The legacy 200 KB reader limit remains; newly written
frontmatter is limited to 8 KiB. Full bodies/resources are read only when selected.

## Agent tool contract

Built-in agents use `skill`; connected external agents use `droide_skill`:

| Operation | Purpose |
| --- | --- |
| `list` | Search `query`; page metadata with `offset` |
| `load` | Read `name`; returns source, bounded instructions, current SHA-256 |
| `list_resources` | List supporting paths inside the selected skill folder |
| `read_resource` | Read a relative UTF-8 resource on demand |
| `copy_resource` | Copy an asset to a new ordinary project `destination` |
| `save` | Save full Markdown `content` to `scope=workspace` or `user` |
| `import` | Publish a complete skill folder from a project-relative `path` |

Reads use the effective combined catalog by default. Explicit `scope=user` or
`scope=workspace` selects that library, including when another scope overrides its
name. This lets an agent read and update the correct personal copy.

Load/resource text pages use UTF-16 offsets without splitting surrogate pairs. A
continuation requires `expected_sha256` from the first page so changed text cannot be
silently combined. Updating a skill also requires its current full-document SHA-256;
creating without one refuses to overwrite an existing entry. Built-in mutations require
Build mode. Both bridges apply the normal skill/read/edit permission gates; no skill
metadata changes those gates. External CLI operation modes remain owned by the CLI;
Droide's bridge separately checks workspace permissions and its live owner.

The shared bridge works in external chat and in Droide terminals configured with
Droide MCP. It does not change each vendor's global configuration or assume native
support for Droide tags. MCP initialization/tool descriptions explain selected skill
identifiers and the shared catalog. Native project directories remain usable by CLIs
that support the corresponding convention. Clients outside Droide, or clients without
this MCP connection, do not automatically see the personal library.

The existing bridge retains its **4096 UTF-8 byte request limit**. Inline external
saves allow up to 2000 characters within that total; for larger skills use `import`
after the folder is present in the Droide editor workspace. A CLI working copy can
differ from that workspace until synchronization. No larger native socket payload,
background synchronization scan, or silent credential/configuration copy was added.
For an older live CLI connection, reconnect if its cached tool catalog lacks
`droide_skill`. A protocol/tool test is not proof of every publisher client's UI.

## Storage and failure handling

Workspace writes reuse `FileRepository` mutation admission, guarded publication,
existing dirty-document protection, and synchronization hooks. Personal writes use
that repository mechanism under app-private storage. Compare-and-write checks prevent
stale forms/tool updates from replacing a newer file. Failed saves retain the open
form. Imports stage outside discovery, then publish the entire directory using the
existing move transaction. Interrupted staging is retained outside the catalog; it
must never be advertised as an installed skill.

Imports accept at most 64 files, 128 entries, 1 MB per resource, and 4 MB total, with
bounded nesting for folder imports. They reject traversal, absolute paths, duplicate
file paths, conflicting/multiple skill roots, invalid UTF-8 Markdown and oversized
entries. ZIP data is materialized only as ordinary, non-executable files. Directory
imports/resource reads reject symlinks and special files, observe parent/file identities,
and recheck them before returning bytes. Private repository state and retained publication
stages are excluded from resource listing and cannot be read/imported as resources. Legacy in-project SKILL.md aliases remain
readable, but cannot expand their resource root to the whole project or become editable
managed entries. Copying a resource refuses an existing destination.

## VS Code source references

Reviewed local snapshot: `e646868227c3e3a9e3f24277c8c7220be42c0be7` of
[`microsoft/vscode`](https://github.com/microsoft/vscode).

- `src/vs/workbench/contrib/chat/common/promptSyntax/service/promptsService.ts`:
  `IAgentSkill`, source metadata and invocation controls.
- `src/vs/workbench/contrib/chat/common/promptSyntax/config/promptFileLocations.ts`:
  standard project/personal roots, `SKILL.md`, skill-name validation.
- `src/vs/workbench/contrib/chat/common/promptSyntax/computeAutomaticInstructions.ts`:
  metadata discovery and loading instructions on demand.
- `src/vs/workbench/contrib/chat/browser/promptSyntax/skillActions.ts` and
  `newPromptFileActions.ts`: management, storage selection and creation.
- `src/vs/platform/agentHost/node/agentHostSkillCompletionProvider.ts`:
  effective session skills and namespaced slash completion.
- `src/vs/workbench/contrib/chat/browser/agentSessions/agentHost/syncedCustomizationBundler.ts`:
  preserving each skill folder and its resources across agent boundaries.

Droide adapts these patterns to its Android storage and existing MCP bridge. It does
not install VSIX extensions, duplicate vendor configuration, or claim complete YAML /
VS Code agent-host feature parity. Scope remains local skills and installed plugins;
there is no remote marketplace or automatic repository installation in this change.

## Verification

Focused host tests cover legacy discovery, plugin integrity, scoped storage, guarded
writes, resource confinement, archive rejection/cancellation, invocation flags,
version-bound continuation, built-in agent tool loading, and real MCP/CLI dispatch.
Compose workflow tests exercise narrow-screen Settings creation/conflict handling and
slash/mention selection without accidental submission. See the checkpoint validation
record for actual test results. These checks do not replace an APK build and device /
authenticated external-provider acceptance testing.


## Bundled media skills

Droide now ships read-only `documents`, `audio`, and `image-generation` skills. They
appear in Settings, model discovery and `/skill/` or `@skill/` completion without
creating files or installing a runtime. Their instruction bodies load only on demand.
Project/personal/plugin sources retain precedence, including invocation flags; local
same-name overrides never fall through to a builtin to bypass a disabled flag. These
builtins have no resource files and update with Droide. See [media tools](MEDIA_AUTHORING.md)
for supported operations, optional runtime dependencies and provider limitations.
