# 2026-10-25 Codex checkpoint

Full pre-edit snapshot: /Users/1poppe/Documents/Codex/Backups/clash-before-codex-2026-10-25_13-41-56. Verified 32561 material files (9.45 GiB), key code and APK SHA256; work cloned and phone sandbox files/layout bank archived.

User confirmed home returns after clouds; Claude transition code unchanged. User now does all gameplay testing. Do not operate the phone or run UI scenarios without a new request.

Changes: compact equipment level picker; native index=UI level-1, display+1, maximum=max-1; repair prior invalid max index on next equipment write; strict avatar parser and validation before atomic write. Scenery ownership JSON key vbgs confirmed at ELF f07d20; 67 HOME entries and full scenery asset table closure added. Captured alliance_games removed on startup.

Changes after user switched to manual testing have NOT been installed. Check coordination/MANUAL_TESTS.md. Scenery, cart disappearance, and equipment fix await user verification. Remaining tasks recorded in STATUS.json.

User clarified workflow: Codex builds AND installs over USB; user tests gameplay. Latest base+scenery split installed successfully on 2026-10-25. Do not launch UI tests.

## Sandbox UI v2 (2026-10-25)

New category dashboard replaces gem-plus routing; unit lists have name search and exact level picker. Upgrade lists selected using complete known-ID/range checks, with ambiguous lists rejected. Four groups found in the real saved avatar: 51 troop upgrades, 8 heroes, 12 pets, 17 spells. Hero Hall embedded heroLevel snapshots updated with avatar to avoid conflicting defense levels.

Attack preparation now includes heroFlags. Selected-slot hero/guardian assignments, defensive aim angles, firing/weapon modes and all coordinates are moved together; missing positions remain unplaced instead of inheriting another layout. Hero flag positions and defense directions were omitted in the previous code.

Packaged missing standalone image textures: air_wave_tex.png, air_wave_shadow.png, range.png, range_dashed.png, rangeSolid.png, plus remaining top-level image files. Original logic values unchanged. Shortened the artificial post-ready cloud delay from360 to30 frames; native readiness gates preserved.

Actual Java methods exercised by tests/verify_save_transforms.py on saved-avatar and JSON-container fixtures; passed. Visual/native combat correctness still awaits user tests. Pending challenge modifiers and full native GUI integration are not implemented.

UI v2 base-only USB installation succeeded. Base SHA256 0f802c0ffeb4919909a5fb7ed16dad580cac126b39f7b0751ca4cc199ed5dd95. App not launched or tested by Codex. User manual test steps in coordination/MANUAL_TESTS.md.

## Attack-mode type regression fix
User screenshot: attack_mode5=false cannot convert Boolean to int. normalizeAttackLayout now copies the JSON Object, retaining boolean/numeric type, with typed defaults. Regression fixtures added to actual Java method harness for true/false/numeric firing and weapon modes; checks pass. No gameplay test by Codex.

Boolean attack-mode fix installed successfully over USB (base-only). User should reopen sandbox and retry the same selected attack layout.

## Building/guardian editor + picker clipping fix

Widened level picker, centred bounded-height scroll container, numeric field wrap-content/font padding instead of fixed110px; keyboard resize. Table-derived building/guardian metadata, selection chip and searchable building list. Merged types are never converted/deleted; minimum UI level1 retains all coordinates and instance IDs. Owned guardian levels update only their upgrade index. Native selected object path from original Lua callbacks: global1837688 ->+20 ->+c8 ->+8; immutable validated IDs sent to Java via atomic snapshot. Native selection path needs user confirmation.

Actual Java save-transform harness passed: levels isolated, cross-village ID matching by ID+data, merged type/count/layout preservation, guardian metadata preservation. Base8480d0e3272b0723393fcc86bb41c8c2027c78d94b94a131f0fccafbcbd1410e compiled and signed. USB installation failed because ADB has zero devices. User asked to reconnect; installation pending. No phone UI tests performed.

Phone reconnected via USB. Building/guardian editor build8480d0e installed successfully, base-only, preserving split and saves. No game launch/UI test by Codex.

User stopped full cloud upload and requested a minimal local Android installation folder instead. Completed /Users/1poppe/Documents/Codex/Clash-Sandbox-Install (about2.72GB): current signed base/split, original-package prerequisite, freshly captured sandbox saves/layout bank, minimal macOS ADB, one-click zsh installer (no Python needed on Mac), optional Python installer, Russian instructions and SHA256. Installer control flow tested with fake ADB only; no phone UI interaction. Development tools/source/intermediates excluded from this minimal folder. Google Drive root was created privately with two small instruction/script files; no game/archive chunks uploaded (256MiB attempts rejected before action by100MiB connector limit). Do not resume cloud upload unless user requests it. Full workspace snapshots/generated archives remain unchanged outside the install folder.

## Single APK packaging (2026-10-25)
- Deliverable: /Users/1poppe/Documents/Codex/Clash-Sandbox-OneAPK/CoC-Sandbox.apk (1926378948 bytes). SHA256 9268075847f58d4f61e40f0ff3bc3daafa9474320fbea3f5271bb7b1c1759ae3.
- make_single_apk.py merges base with assets-only split, permits identical duplicates only, signs with existing certificate; all 9103 members verified byte-for-byte. Signature and 16 KiB native alignment checks passed.
- InstallSupport seeds bundled save/home/avatar/layout-bank only on fresh installation, preserves existing state, shows dependency guidance before protected JNI initialization.
- Official com.supercell.clashofclans remains required. Not a completely independent fresh-phone runtime. No device UI/gameplay test or device installation of merged package performed.
- Previous two-APK installation kit untouched. Cloud upload paused by user. Generated compressed cloud intermediates removed to recover disk; original workspace and uncompressed full staging snapshot preserved.
