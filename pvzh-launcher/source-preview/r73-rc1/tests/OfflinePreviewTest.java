package com.pvzh.sharecode;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.jpountz.lz4.LZ4Factory;
import org.json.JSONArray;
import org.json.JSONObject;
import static com.pvzh.sharecode.NativeJson.*;

/** Repeatable tests using synthetic files and rows, with no game installation. */
public final class OfflinePreviewTest {
    private interface Check { void run() throws Exception; }

    private static void expect(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejects(String message, Check check) throws Exception {
        try { check.run(); }
        catch (IOException expected) {
            expect(expected.getMessage().contains(message), expected.toString());
            return;
        }
        throw new AssertionError("Expected rejection: " + message);
    }

    private static byte[] utf8(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static byte[] zip(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                ZipEntry item = new ZipEntry(entry.getKey());
                CRC32 crc = new CRC32();
                crc.update(entry.getValue());
                item.setMethod(ZipEntry.STORED);
                item.setSize(entry.getValue().length);
                item.setCrc(crc.getValue());
                out.putNextEntry(item);
                out.write(entry.getValue());
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String hash(byte[] bytes) { return OfflineModArchive.hex(OfflineModArchive.digest().digest(bytes)); }

    private static void archive(Path folder) throws Exception {
        String card = "author/cards/card_data_99";
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(card, utf8("synthetic unique bytes"));
        entries.put("author/loc/cn_9", utf8("example"));
        entries.put("manifest.json", utf8("unrelated author note"));
        Path file = folder.resolve("fixture.zip");
        byte[] legacy = zip(entries);
        Files.write(file, legacy);
        OfflineModArchive.Report report = OfflineModArchive.inspect(file, hash(legacy));
        expect(report.manifestPath == null && report.id.equals("zip-" + hash(legacy).substring(0, 16)), "legacy identity");
        expect(report.mappings(Arrays.asList("cards/card_data", "loc/cn"), Collections.emptyMap()).size() == 2, "legacy logical mapping");
        expect(Arrays.equals(Files.readAllBytes(file), legacy), "read-only inspection");
        rejects("SHA-256", () -> OfflineModArchive.inspect(file, "invalid"));

        JSONObject manifest = obj("schema", 1, "id", "fixture", "future_author", obj("flag", true),
                "files", arr(obj("source", card, "logical", "cards/card_data")));
        entries.put("author/mod.json", utf8(manifest.toString()));
        entries.put("author/nested/mod.json", utf8("{}"));
        byte[] modern = zip(entries);
        Files.write(file, modern);
        report = OfflineModArchive.inspect(file, null);
        expect(report.manifestPath.equals("author/mod.json") && report.warnings.size() == 1, "nested manifest priority");
        expect(report.manifest().getJSONObject("future_author").getBoolean("flag"), "unknown manifest data retained");
        expect(!report.mappings(Collections.singletonList("cards/card_data"), Collections.singletonMap(card, "")).containsKey(card), "explicit ignore overrides manifest");
        Files.write(file, zip(Collections.singletonMap("author/../invalid", utf8(""))));
        rejects("Invalid ZIP path", () -> OfflineModArchive.inspect(file, null));
        Files.write(file, zip(Collections.singletonMap("mod.json", utf8("{\"schema\":2}"))));
        rejects("Unsupported Mod schema", () -> OfflineModArchive.inspect(file, null));
        Files.write(file, zip(Collections.singletonMap("mod.json", utf8("{\"schema\":1,\"schema\":1}"))));
        rejects("duplicate key", () -> OfflineModArchive.inspect(file, null));

        byte[] corrupt = legacy.clone();
        int dataStart = 30 + utf8(card).length;
        corrupt[dataStart] ^= 1;
        Files.write(file, corrupt);
        rejects("CRC mismatch", () -> OfflineModArchive.inspect(file, null));
        Map<String, byte[]> duplicates = new LinkedHashMap<>();
        duplicates.put("item-a", utf8("a"));
        duplicates.put("item-b", utf8("b"));
        byte[] duplicate = zip(duplicates);
        byte[] second = utf8("item-b");
        for (int i = 0; i <= duplicate.length - second.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(duplicate, i, i + second.length), second)) duplicate[i + 5] = 'a';
        }
        Files.write(file, duplicate);
        rejects("Duplicate ZIP path", () -> OfflineModArchive.inspect(file, null));

        Map<String, byte[]> batch = new LinkedHashMap<>();
        JSONObject batchItem = obj("id", "fixture", "path", "mods/fixture.zip", "bytes", modern.length, "sha256", hash(modern));
        JSONObject index = obj("format", "pvzh-mod-batch", "schema", 1, "items", arr(batchItem));
        batch.put("manifest.json", utf8(index.toString()));
        batch.put("README.txt", utf8("synthetic instructions"));
        batch.put("mods/fixture.zip", modern);
        Files.write(file, zip(batch));
        expect(OfflineModArchive.verifyBatch(file, null).getJSONArray("items").length() == 1, "batch index and checksums");
        rejects("Use verifyBatch", () -> OfflineModArchive.inspect(file, null));
        batchItem.put("sha256", hash(legacy));
        batch.put("manifest.json", utf8(index.toString()));
        Files.write(file, zip(batch));
        rejects("different SHA-256", () -> OfflineModArchive.verifyBatch(file, null));
    }

    private static JSONObject componentRow(String type, JSONObject data) throws Exception {
        return obj("$type", "Fixture." + type, "$data", data);
    }

    private static void cards() throws Exception {
        JSONArray components = arr(componentRow("Card", obj("Guid", 900001)),
                componentRow("Attack", obj("AttackValue", obj("BaseValue", 2, "extra", 9007199254740993L))),
                componentRow("Unknown", obj("data", arr(1, "future"))));
        JSONObject original = obj("prefabName", "synthetic", "entity", obj("components", components), "unknown", obj("v", 4));
        JSONObject cards = NativeManualCardCodec.phantom(obj("cards", obj("900001", original)).toString());
        JSONObject result = NativeManualCardCodec.withStats(900001, cards.getJSONObject("900001"), obj("attack", 5));
        JSONObject attack = component(result.getJSONObject("entity"), "Attack", false).getJSONObject("$data").getJSONObject("AttackValue");
        expect(attack.getInt("BaseValue") == 5 && result.getInt("displayAttack") == 5, "stat and display update");
        expect(attack.getLong("extra") == 9007199254740993L, "unknown stat data preserved exactly");
        expect(equal(original.get("unknown"), result.get("unknown")) && equal(components.get(2), result.getJSONObject("entity").getJSONArray("components").get(2)), "unknown card data retained");
        expect(!original.has("displayAttack") && components.getJSONObject(1).getJSONObject("$data").getJSONObject("AttackValue").getInt("BaseValue") == 2, "source card unchanged");
        rejects("Missing component", () -> NativeManualCardCodec.withStats(900001, original, obj("health", 3)));
        rejects("GUID differs", () -> NativeManualCardCodec.validate(900002, original));
        rejects("duplicate key", () -> NativeManualCardCodec.parseObject("{\"x\":1,\"x\":2}"));
        expect(NativeManualCardCodec.parseObject("{\"x\":1.234567890123456789}").get("x").toString().equals("1.234567890123456789"), "unknown decimal keeps its precision");
        expect(!equal(9007199254740993L, 9007199254740992L), "large integer comparison");

        String csv = "name,first\r\nkeep,\"line 1\r\nline 2\"\r\n";
        String patched = NativeCsv.patch(csv, Collections.singletonMap("name", "updated, value"));
        expect(patched.endsWith("keep,\"line 1\r\nline 2\"\r\n"), "untouched CSV record preserved");
        expect(NativeCsv.values(patched).get("name").equals("updated, value"), "CSV reread");
        rejects("Duplicate localization key", () -> NativeCsv.patch("name,a\nname,b\n", Collections.singletonMap("name", "c")));
        rejects("two fields", () -> NativeCsv.patch("name,a,extra\n", Collections.singletonMap("name", "b")));
        JSONObject node = obj("$type", "PvZCards.Engine.Components.EffectValueDescriptor", "$data", obj("DestToSourceMap", obj("Future", "Preserve")));
        JSONObject map = NativeManualEffectValueMap.preset(node, "DamageToHeal");
        expect(map.getString("Future").equals("Preserve") && map.getString("HealAmount").equals("DamageAmount"), "effect value map preserves unknown rows");
        rejects("Preserve unknown mapping", () -> NativeManualEffectValueMap.checked(node, obj()));
        JSONObject subtypes = NativeManualTaxonomy.parse("{\"schema\":1,\"subtypes\":[{\"id\":900,\"display_name\":\"Preview\",\"cn_name\":\"Example\",\"extra\":3}]}");
        expect(subtypes.getJSONArray("subtypes").getJSONObject(0).getInt("extra") == 3, "optional author data retained");
        rejects("Duplicate subtype", () -> NativeManualTaxonomy.parse("{\"schema\":1,\"subtypes\":[{\"id\":900,\"display_name\":\"A\",\"cn_name\":\"a\"},{\"id\":900,\"display_name\":\"B\",\"cn_name\":\"b\"}]}"));
    }

    private static void levels() throws Exception {
        JSONObject source = obj("Id", "synthetic", "Status", 0, "SkipHeroSelect", false, "unrelated", obj("marker", 42),
                "PlayerConfig", obj("Parameters", obj("SuperBlockTable", obj("TableEntries", arr(obj("RngWeight", 100, "ChargeAmount", 1, "extra", true))))));
        JSONObject event = obj("x", 1, "y", 2);
        JSONObject other = obj("label", "keep", "unrecognized", arr(42));
        source.put("GameEvents", arr(event, other));
        source.put("BoardConfig", obj("LaneEntries", arr(obj("LaneType", 1, "unchanged", "lane note"))));
        String sha = OfflineLevelEdits.eventHash(event);
        expect(sha.equals(OfflineLevelEdits.eventHash(obj("y", 2, "x", 1))), "canonical event fingerprint");
        JSONObject row = obj("index", 0, "expected_sha256", sha, "kind", "message", "values", obj("round", 2));
        NativeManualLevelDraft draft = new NativeManualLevelDraft(source, "mapping", arr(row));
        expect(!draft.dirty(), "clean draft");
        draft.field("Status", 2);
        draft.events().get(0).set("turn", 3);
        JSONObject operations = draft.row().getJSONArray("operations").getJSONObject(0);
        expect(operations.getString("expected_sha256").equals(sha) && operations.getJSONObject("values").getInt("turn") == 3, "guarded event draft");
        expect(draft.changeCount() == 2, "draft change count");
        rejects("full campaign context", () -> OfflineLevelEdits.apply(source, draft.row()));
        NativeManualLevelDraft scalars = new NativeManualLevelDraft(source, "mapping", arr());
        scalars.field("SkipHeroSelect", true);
        scalars.field("Status", 2);
        JSONObject patch = scalars.row();
        patch.getJSONArray("operations").put(obj("op", "block_table", "side", "player", "entries", arr(obj("weight", 70, "charge", 1), obj("weight", 30, "charge", 3))));
        patch.getJSONArray("operations").put(obj("op", "lane_type", "lane_number", 1, "value", 2));
        patch.getJSONArray("operations").put(obj("op", "event_move", "index", 0, "expected_sha256", sha, "position", 1));
        JSONObject applied = OfflineLevelEdits.apply(source, patch, "mapping");
        JSONArray table = applied.getJSONObject("PlayerConfig").getJSONObject("Parameters").getJSONObject("SuperBlockTable").getJSONArray("TableEntries");
        expect(applied.getBoolean("SkipHeroSelect") && applied.getInt("Status") == 2 && table.getJSONObject(1).getBoolean("extra"), "validated scalar and table edits");
        expect(source.getInt("Status") == 0 && equal(source.get("unrelated"), applied.get("unrelated")), "detached level with unknown data intact");
        JSONObject lane = applied.getJSONObject("BoardConfig").getJSONArray("LaneEntries").getJSONObject(0);
        expect(lane.getInt("LaneType") == 2 && lane.getString("unchanged").equals("lane note"), "lane terrain only");
        expect(equal(applied.getJSONArray("GameEvents").get(0), other) && equal(source.getJSONArray("GameEvents").get(0), event), "event move preserves opaque fields and source order");
        patch.getJSONArray("operations").put(obj("op", "event_remove", "index", 0, "expected_sha256", sha));
        expect(OfflineLevelEdits.apply(source, patch).getJSONArray("GameEvents").length() == 1, "event removal uses original index after moving");
        patch.getJSONArray("operations").getJSONObject(3).put("expected_sha256", "stale");
        rejects("no longer matches", () -> OfflineLevelEdits.apply(source, patch));
        patch.getJSONArray("operations").getJSONObject(3).put("expected_sha256", sha);
        rejects("different card mapping", () -> OfflineLevelEdits.apply(source, patch, "different"));
        patch.getJSONObject("changes").put("Status", 4);
        rejects("range", () -> OfflineLevelEdits.apply(source, patch));
        patch.getJSONObject("changes").put("Status", 2);
        patch.getJSONArray("operations").getJSONObject(0).getJSONArray("entries").getJSONObject(1).put("weight", 20);
        rejects("100", () -> OfflineLevelEdits.apply(source, patch));
    }

    private static void unity() throws Exception {
        byte[] bundle = unityBundle(false);
        OfflineUnityArtwork reader = new OfflineUnityArtwork(bundle);
        expect(reader.unityVersion().equals("2022.3.0f1") && reader.entries().containsKey("CAB-synthetic"), "UnityFS directory");
        expect(reader.objectTypes().get(1L) == 142 && reader.objectBytes(1).length == 16, "v22 object table and raw bytes");
        byte[] detached = reader.objectBytes(1);
        detached[0] = 1;
        expect(reader.objectBytes(1)[0] == 0, "raw object is a detached copy");
        expect(new OfflineUnityArtwork(unityBundle(true)).objectTypes().equals(reader.objectTypes()), "LZ4 metadata and body");
        for (long id : new long[]{2, 3}) {
            OfflineUnityArtwork.EncodedTexture texture = reader.textureEncoded(id);
            expect(texture.width == 1 && texture.height == 1 && texture.format == 4, "texture dimensions and format");
            expect(Arrays.equals(texture.bytes(), new byte[]{1, 2, 3, 4}), "inline and streamed texture bytes");
        }
        rejects("Sprite object", () -> reader.spriteMesh(1, 8, 8));
        rejects("长度不一致", () -> new OfflineUnityArtwork(Arrays.copyOf(bundle, bundle.length - 1)));
        expect(NativeArtworkTypeStrings.value(1200).equals("EntityId"), "common-string data");
        sprite();
    }

    private static byte[] unityBundle(boolean compressed) throws IOException {
        byte[] treeStrings = utf8("AssetBundle\0Base\0m_PreloadTable\0m_Container\0m_MainAsset\0preloadIndex\0preloadSize\0");
        ByteBuffer meta = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
        meta.put(utf8("2022.3.0f1\0")).putInt(0).put((byte) 1).putInt(2);
        meta.putInt(142).put((byte) 0).putShort((short) -1).put(new byte[16]);
        meta.putInt(10).putInt(treeStrings.length);
        treeNode(meta, 0, 0, 12, -1);
        treeNode(meta, 1, 0x80000000 | 49, 17, -1);
        treeNode(meta, 2, 0x80000000 | 222, 0x80000000 | 795, 4);
        treeNode(meta, 2, 0x80000000 | 222, 0x80000000 | 106, 4);
        treeNode(meta, 1, 0x80000000 | 49, 32, -1);
        treeNode(meta, 2, 0x80000000 | 222, 0x80000000 | 795, 4);
        treeNode(meta, 2, 0x80000000 | 222, 0x80000000 | 106, 4);
        treeNode(meta, 1, 12, 44, -1);
        treeNode(meta, 2, 0x80000000 | 222, 56, 4);
        treeNode(meta, 2, 0x80000000 | 222, 69, 4);
        meta.put(treeStrings).putInt(0);
        meta.putInt(28).put((byte) 0).putShort((short) -1).put(new byte[16]);
        String[] textureNames = {"Texture2D", "Base", "m_Width", "m_Height", "m_TextureFormat", "m_MipCount",
                "StreamingInfo", "m_StreamData", "offset", "size", "path", "image data"};
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        int[] offsets = new int[textureNames.length];
        for (int i = 0; i < offsets.length; i++) {
            offsets[i] = strings.size();
            strings.write(utf8(textureNames[i] + "\0"));
        }
        meta.putInt(10).putInt(strings.size());
        treeNode(meta, 0, offsets[0], offsets[1], -1);
        for (int i = 2; i <= 5; i++) treeNode(meta, 1, 0x80000000 | 222, offsets[i], 4);
        treeNode(meta, 1, offsets[6], offsets[7], -1);
        treeNode(meta, 2, 0x80000000 | 222, offsets[8], 4);
        treeNode(meta, 2, 0x80000000 | 222, offsets[9], 4);
        treeNode(meta, 2, 0x80000000 | 840, offsets[10], -1);
        treeNode(meta, 1, 0x80000000 | 894, offsets[11], -1);
        meta.put(strings.toByteArray()).putInt(0).putInt(3);
        while ((meta.position() & 3) != 0) meta.put((byte) 0);
        meta.putLong(1).putLong(0).putInt(16).putInt(0);
        meta.putLong(2).putLong(16).putInt(36).putInt(1);
        meta.putLong(3).putLong(52).putInt(44).putInt(1);
        meta.putInt(0).putInt(0).putInt(0).put((byte) 0);
        int offset = (48 + meta.position() + 3) & ~3;
        int cabSize = offset + 96, plainSize = cabSize + 4;
        ByteBuffer cab = ByteBuffer.allocate(plainSize).order(ByteOrder.BIG_ENDIAN);
        cab.putLong(0).putInt(22).putLong(0).putInt(meta.position()).putLong(cabSize).putLong(offset).putLong(0);
        cab.put(meta.array(), 0, meta.position()).order(ByteOrder.LITTLE_ENDIAN);
        cab.position(offset + 16);
        cab.putInt(1).putInt(1).putInt(4).putInt(1).putInt(0).putInt(0).putInt(0).putInt(4).put(new byte[]{1, 2, 3, 4});
        cab.putInt(1).putInt(1).putInt(4).putInt(1).putInt(0).putInt(4).putInt(12).put(utf8("fixture.resS")).putInt(0);
        cab.put(new byte[]{1, 2, 3, 4});
        byte[] body = compressed ? LZ4Factory.safeInstance().fastCompressor().compress(cab.array()) : cab.array();
        int mode = compressed ? 2 : 0;
        ByteArrayOutputStream directory = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(directory)) {
            out.write(new byte[16]); out.writeInt(1);
            out.writeInt(plainSize); out.writeInt(body.length); out.writeShort(mode);
            out.writeInt(2); out.writeLong(0); out.writeLong(cabSize); out.writeInt(4);
            out.write(utf8("CAB-synthetic\0"));
            out.writeLong(cabSize); out.writeLong(4); out.writeInt(0); out.write(utf8("fixture.resS\0"));
        }
        byte[] encoded = compressed ? LZ4Factory.safeInstance().fastCompressor().compress(directory.toByteArray()) : directory.toByteArray();
        ByteBuffer file = ByteBuffer.allocate(64 + encoded.length + body.length).order(ByteOrder.BIG_ENDIAN);
        file.put(utf8("UnityFS\0")).putInt(7).put(utf8("5.x.x\0"));
        file.put(utf8("2022.3.0f1\0")).putLong(file.capacity()).putInt(encoded.length).putInt(directory.size()).putInt(64 | mode);
        file.position(64);
        file.put(encoded).put(body);
        return file.array();
    }

    private static void treeNode(ByteBuffer buffer, int depth, int type, int name, int size) {
        buffer.putShort((short) 1).put((byte) depth).put((byte) 0).putInt(type).putInt(name)
                .putInt(size).putInt(0).putInt(0).putLong(0);
    }

    private static void sprite() {
        NativeArtworkTree.Value empty = value("Array", "empty");
        empty.arrayCount = 0;
        NativeArtworkTree.Value channels = value("Array", "m_Channels");
        NativeArtworkTree.Value[] channelRows = new NativeArtworkTree.Value[14];
        for (int i = 0; i < channelRows.length; i++) channelRows[i] = group("Channel", "row",
                n("dimension", i == 0 ? 3 : i == 4 ? 2 : 0), n("stream", i == 4 ? 1 : 0), n("format", 0), n("offset", 0));
        channels.elements = Arrays.asList(channelRows);
        NativeArtworkTree.Value submeshes = value("Array", "m_SubMeshes");
        submeshes.elements = Collections.singletonList(group("SubMesh", "row", n("firstByte", 0), n("indexCount", 3),
                n("topology", 0), n("baseVertex", 0), n("firstVertex", 0), n("vertexCount", 3)));
        ByteBuffer vertices = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
        vertices.putFloat(-2).putFloat(-2).putFloat(0).putFloat(2).putFloat(-2).putFloat(0).putFloat(0).putFloat(2).putFloat(0);
        NativeArtworkTree.Value indices = bytes("m_IndexBuffer", new byte[]{0, 0, 1, 0, 2, 0});
        NativeArtworkTree.Value render = group("Render", "m_RD", emptyNamed("m_Bindpose"), emptyNamed("secondaryTextures"),
                pointer("alphaTexture", 0), pointer("texture", 2), n("settingsRaw", 0), n("downscaleMultiplier", 1),
                rect("textureRect"), group("Vector", "textureRectOffset", n("x", 0), n("y", 0)),
                group("Vector", "uvTransform", n("x", 1), n("y", 4), n("z", 1), n("w", 4)),
                group("Vertex", "m_VertexData", n("m_VertexCount", 3), channels, bytes("m_DataSize", vertices.array())), submeshes, indices);
        NativeArtworkTree.Value sprite = group("Sprite", "Base", emptyNamed("m_Bones"), pointer("m_SpriteAtlas", 0), rect("m_Rect"),
                group("Vector", "m_Pivot", n("x", 0.5), n("y", 0.5)), n("m_PixelsToUnits", 1), render);
        NativeArtworkSpriteMesh.Mesh mesh = NativeArtworkSpriteMesh.read(sprite, 8, 8);
        expect(mesh != null && mesh.triangles.length == 6 && mesh.pivotX == 0.5f && mesh.pivotY == 0.5f, "synthetic Sprite triangle");
        indices.source[0] = 5;
        expect(NativeArtworkSpriteMesh.read(sprite, 8, 8) == null, "unsupported index uses fallback");
    }

    private static NativeArtworkTree.Value group(String type, String name, NativeArtworkTree.Value... children) {
        NativeArtworkTree.Value v = value(type, name);
        v.children = Arrays.asList(children);
        return v;
    }

    private static NativeArtworkTree.Value rect(String name) {
        return group("Rect", name, n("x", 0), n("y", 0), n("width", 8), n("height", 8));
    }

    private static NativeArtworkTree.Value pointer(String name, long id) {
        return group("PPtr", name, n("m_FileID", 0), n("m_PathID", id));
    }

    private static NativeArtworkTree.Value emptyNamed(String name) {
        NativeArtworkTree.Value v = value("Array", name);
        v.arrayCount = 0;
        return v;
    }

    private static NativeArtworkTree.Value bytes(String name, byte[] bytes) {
        NativeArtworkTree.Value v = value("TypelessData", name);
        v.source = bytes;
        v.rawOffset = 0;
        v.rawLength = bytes.length;
        return v;
    }

    private static NativeArtworkTree.Value n(String name, long x) {
        NativeArtworkTree.Value v = value("int", name);
        v.scalar = x;
        return v;
    }

    private static NativeArtworkTree.Value n(String name, double x) {
        NativeArtworkTree.Value v = value("float", name);
        v.scalar = x;
        return v;
    }

    private static NativeArtworkTree.Value value(String type, String name) {
        NativeArtworkTree.Node n = new NativeArtworkTree.Node();
        n.type = type;
        n.name = name;
        return new NativeArtworkTree.Value(n, 0);
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass an empty scratch directory");
        archive(Paths.get(args[0]));
        cards();
        levels();
        unity();
        System.out.println("PASS: ZIP and batch validation, card and CSV editing, level drafts, UnityFS and Sprite reading");
    }
}
