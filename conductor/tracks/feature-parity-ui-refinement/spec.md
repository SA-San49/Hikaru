# Track: Feature Parity & UI Refinement

## Specification
Replicate key features from Komikku into Hikaru to improve UX fluidity and control, focusing on the Migration and Notes components.

### 1. Migration Source Selection
- **Multi-Selection UI**:
    - Add checkboxes to source items in `MigrateSourceScreen`.
    - Implement a bulk selection toolbar (Select All, Select None, Match Enabled, Pinned).
    - Match Komikku's selection ordering.
- **Wording Updates**:
    - Change "Migration" to "Migrating" where applicable during active flows.
- **Search & Bottom Sheet**:
    - Ensure search query correctly filters results in the migration bottom sheet.
- **Settings**:
    - Improve the settings sheet accessibility within the migration flow.

### 2. Enhanced Notes Feature
- **UI Reordering**:
    - Swap Title and "Edit Notes" (Notes editor at top, Title below it).
- **Rich Text Support**:
    - Add Bold, Italics, Underline, Bullet points, and Numbers (Reference Komikku implementation).
- **Character Constraints**:
    - 250-character limit (excluding whitespace).

## Implementation Plan

### Phase 1: Migration Enhancements
- [ ] Update `MigrateSourceScreenModel` to support `selectedSources` (Set of IDs).
- [ ] Implement bulk selection logic (Select All, None, Enabled, Pinned).
- [ ] Modify `MigrateSourceScreen.kt` and `MigrateSourceList` to show checkboxes and the toolbar.
- [ ] Fix bottom sheet search synchronization.
- [ ] Refine wording ("Migration" -> "Migrating").

### Phase 2: Notes Refinement
- [ ] Update `AnimeNotesScreen` layout (reorder Title/Editor).
- [ ] Enhance `AnimeNotesTextArea` with full rich text support.
- [ ] Implement character count logic (excluding spaces).

### Phase 3: Validation
- [ ] Verify selection logic with various source configurations.
- [ ] Test rich text rendering and persistence.
- [ ] Build and verify no regressions in Browse/Library.
