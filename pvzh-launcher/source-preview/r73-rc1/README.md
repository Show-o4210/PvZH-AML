# R73 RC1 Source Preview

PvZH AM is an unofficial community Mod project for the international edition of *Plants vs. Zombies Heroes*. Electronic Arts Inc. owns the original game's code, materials and related trademarks. EA and PopCap have given no endorsement and bear no responsibility for the project's content or operation. [EA Plants vs. Zombies Mod Policy](https://help.ea.com/en/articles/plants-vs-zombies/mod-policy/)

PvZH AM 是面向国际版《植物大战僵尸：英雄》的非官方社区 Mod 项目。原游戏代码、素材及相关商标归 Electronic Arts Inc. 所有。EA 与 PopCap 未为本项目背书，也不对其内容或运行负责。 [EA《植物大战僵尸》Mod 政策](https://help.ea.com/en/articles/plants-vs-zombies/mod-policy/)

## Included source

The seven files below match the source compiled into the R73 RC1 development build. This first batch compiles with the JDK alone.

| File | Purpose |
| --- | --- |
| [`NativeArtworkPng.java`](src/com/pvzh/sharecode/NativeArtworkPng.java) | PNG and RGBA encoding and decoding |
| [`NativeTintColor.java`](src/com/pvzh/sharecode/NativeTintColor.java) | RGBA recoloring that preserves alpha |
| [`NativeTintParameters.java`](src/com/pvzh/sharecode/NativeTintParameters.java) | Color input validation and preview state |
| [`NativeArtworkParameters.java`](src/com/pvzh/sharecode/NativeArtworkParameters.java) | Artwork scale and placement parameters |
| [`NativeArtworkDocumentRequests.java`](src/com/pvzh/sharecode/NativeArtworkDocumentRequests.java) | Local PNG import and export request tracking |
| [`NativeManualTaxonomyLabel.java`](src/com/pvzh/sharecode/NativeManualTaxonomyLabel.java) | Display labels for editor selections |
| [`NativeMarkdown.java`](src/com/pvzh/sharecode/NativeMarkdown.java) | Markdown text and formatting parser |

## Offline modules extracted from R73 RC1

These standalone adaptations use files and JSON supplied by the caller. ZIP inspection and Unity artwork reading are read-only. Card and level edits return JSON copies.

| Entry point | What it supports |
| --- | --- |
| [`OfflineModArchive`](src/com/pvzh/sharecode/OfflineModArchive.java) | Checks ZIP paths, limits, CRCs and SHA-256, selects a root or nested `mod.json`, recognizes legacy ZIPs, and verifies batch export indexes and embedded ZIP hashes. |
| [`NativeManualCardCodec`](src/com/pvzh/sharecode/NativeManualCardCodec.java), [`NativeCsv`](src/com/pvzh/sharecode/NativeCsv.java) | Checks card JSON and GUIDs, edits existing cost, attack and health fields, and changes selected localization rows while keeping other CSV records verbatim. |
| [`NativeManualEffectValueMap`](src/com/pvzh/sharecode/NativeManualEffectValueMap.java), [`NativeManualTaxonomy`](src/com/pvzh/sharecode/NativeManualTaxonomy.java) | Edits the damage and healing value mappings while preserving unfamiliar entries, and checks optional custom subtype author data. |
| [`NativeManualLevelDraft`](src/com/pvzh/sharecode/NativeManualLevelDraft.java), [`OfflineLevelEdits`](src/com/pvzh/sharecode/OfflineLevelEdits.java) | Builds draft changes, validates scalar fields, terrain and block tables, and moves or removes existing events by original index and SHA-256. Adding or updating event payloads, deck binding and scene compilation require the full campaign context. |
| [`OfflineUnityArtwork`](src/com/pvzh/sharecode/OfflineUnityArtwork.java) | Reads uncompressed or LZ4 UnityFS 7/8 bundles with one little-endian SerializedFile v22, validates object references, and reads supported unrotated Sprite meshes and encoded single-mip RGBA32 and ETC2_RGBA8 textures. |

For single Mod ZIPs, `files[].source` names an actual ZIP entry and `files[].logical` names a logical resource family. The reader recognizes schema 1, older manifests with no explicit schema, and no-manifest archives. Mapping takes explicit overrides first, then manifest declarations, then a unique inferred family from the caller's active list. The launcher resolves actual resource destinations from its active game manifest. Batch exports have a separate root `manifest.json` with `format: pvzh-mod-batch`, schema 1, and per-ZIP paths, sizes and SHA-256 values. Verify the container with `verifyBatch` and inspect each original ZIP as a separate Mod.

### Compile and verify

Use JDK 9 or later and supply `org.json:json:20240303` and `org.lz4:lz4-java:1.8.0` from your build environment. Run from this directory with a new scratch directory for generated files. The example uses PowerShell on Windows.

```powershell
$previewJob = New-Item -ItemType Directory (Join-Path ([IO.Path]::GetTempPath()) ('pvzh-preview-' + [guid]::NewGuid()))
$previewDeps = 'C:/dependencies/json-20240303.jar;C:/dependencies/lz4-java-1.8.0.jar'
$previewSources = Get-ChildItem src/com/pvzh/sharecode -Filter '*.java' | Select-Object -ExpandProperty FullName
javac --release 8 -encoding UTF-8 -cp $previewDeps -d $previewJob.FullName @previewSources tests/OfflinePreviewTest.java
if ($LASTEXITCODE -ne 0) { throw 'Compilation failed' }
java -Xmx256m -cp "$($previewJob.FullName);$previewDeps" com.pvzh.sharecode.OfflinePreviewTest $previewJob.FullName
```

The tests create synthetic ZIPs, cards, levels, type trees and meshes locally. They cover legacy and declared ZIPs, byte preservation, invalid archive rejection, detached edits, stale event checks, both UnityFS compression modes, inline and streamed textures, and the Sprite fallback. A local comparison against the R73 RC1 reader matched 218 native objects and 64 encoded textures across two representative bundles. Game integration is outside this preview.

### Source provenance

`NativeArtworkTree`, `NativeArtworkSpriteMesh` and `NativeArtworkTypeStrings` preserve the R73 RC1 source text. Git normalizes their line endings. The 112 common type strings were checked against the type data shipped with UnityPy 1.25.0. [UnityPy 1.25.0](https://pypi.org/project/UnityPy/1.25.0/) · [Bundled UnityPy MIT notice](UNITYPY-LICENSE.txt)

The remaining additions extract R73 logic from `NativeManualArchive`, `LauncherStore`, `NativeModBatch`, `NativeManualCardCodec`, `NativeCardEditorOps`, `NativeManualEffectValueMap`, `NativeManualTaxonomy`, `NativeCsv`, `NativeJson`, `NativeManualSkillShare`, `NativeLevelCompiler`, `NativeCampaignCardMap`, `NativeManualLevelDraft` and `NativeArtworkBundle`. The preview substitutes standalone diagnostics and keeps the applicable parsing, validation and editing paths. It additionally rejects explicit unsupported Mod schemas and validates all archive entries, including unrecognized ones.

## Source terms

The AM Java files in this preview are available for public review. The team has yet to choose general terms for reuse, modification or redistribution of AM source code. Rights remain with the respective holders, subject to applicable law and the platform terms. The UnityPy notice applies to the TypeTree common-string material derived from UnityPy. [Repository license scopes](../../../LICENSES.md)
