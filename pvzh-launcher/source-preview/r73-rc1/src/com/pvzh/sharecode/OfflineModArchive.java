package com.pvzh.sharecode;

import java.io.IOException;
import java.io.DataInputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only validation and logical mapping of caller-supplied ZIPs. */
public final class OfflineModArchive {
    private static final long GIB = 1024L * 1024 * 1024;
    private static final int MAX_MANIFEST = 64 * 1024 * 1024;
    private static final int MAX_ENTRIES = 30_000;

    public static final class Report {
        public final String sha256, id, manifestPath;
        public final List<String> entries, warnings;
        private final JSONObject manifest;

        private Report(String sha256, String id, String manifestPath, JSONObject manifest,
                       Collection<String> entries, List<String> warnings) {
            this.sha256 = sha256;
            this.id = id;
            this.manifestPath = manifestPath;
            this.manifest = manifest;
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        }

        public JSONObject manifest() {
            return new JSONObject(manifest.toString());
        }

        /** Maps entries to the caller's active logical names, in ZIP path order. */
        public Map<String, String> mappings(Collection<String> logicalNames, Map<String, String> overrides) {
            Map<String, String> declared = new LinkedHashMap<>();
            JSONArray files = manifest.optJSONArray("files");
            if (files != null) {
                for (int i = 0; i < files.length(); i++) {
                    JSONObject row = files.getJSONObject(i);
                    declared.put(row.getString("source"), row.getString("logical"));
                }
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (String path : entries) {
                if (path.endsWith("/") || isManifest(path)) continue;
                String logical;
                if (overrides.containsKey(path)) logical = overrides.get(path);
                else if (declared.containsKey(path)) logical = declared.get(path);
                else logical = infer(path, logicalNames);
                if (logical != null && logicalNames.contains(logical)) result.put(path, logical);
            }
            return result;
        }
    }

    private OfflineModArchive() {}

    /** Missing manifests are supported. An explicit schema other than 1 is reported as unsupported. */
    public static Report inspect(Path path, String expectedSha256) throws IOException {
        String sha = checkedFile(path, 2 * GIB, expectedSha256);
        List<String> warnings = new ArrayList<>();
        Report report;
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            Map<String, ZipEntry> entries = index(zip, GIB, 4 * GIB);
            List<String> manifests = new ArrayList<>();
            byte[] buffer = new byte[65536];
            for (ZipEntry entry : entries.values()) {
                verify(zip, entry, buffer);
                if (!entry.isDirectory() && isManifest(entry.getName())) manifests.add(entry.getName());
            }
            manifests.sort(Comparator.comparingInt((String name) -> name.split("/").length).thenComparing(name -> name));
            String selected = manifests.isEmpty() ? null : manifests.get(0);
            if (manifests.size() > 1) warnings.add("Multiple mod.json entries. Selected " + selected);
            if (selected == null && entries.containsKey("manifest.json") && !entries.get("manifest.json").isDirectory()) {
                JSONObject index = null;
                try { index = readJson(zip, entries.get("manifest.json")); }
                catch (IOException unrelated) { /* Legacy ZIPs can contain unrelated author files. */ }
                if (index != null && "pvzh-mod-batch".equals(index.opt("format"))) {
                    throw new IOException("Batch export. Use verifyBatch.");
                }
            }
            JSONObject manifest = selected == null ? new JSONObject() : readJson(zip, entries.get(selected));
            validateManifest(manifest, entries);
            String id = manifest.optString("id", "zip-" + sha.substring(0, 16));
            report = new Report(sha, id, selected, manifest, entries.keySet(), warnings);
        }
        checkedFile(path, 2 * GIB, sha);
        return report;
    }

    /** Verifies the container index and original ZIP payload hashes without unpacking them. */
    public static JSONObject verifyBatch(Path path, String expectedSha256) throws IOException {
        String sha = checkedFile(path, 9 * GIB, expectedSha256);
        JSONObject manifest;
        try (ZipFile zip = new ZipFile(path.toFile(), StandardCharsets.UTF_8)) {
            Map<String, ZipEntry> entries = index(zip, 2 * GIB, 9 * GIB);
            manifest = readJson(zip, entries.get("manifest.json"));
            if (!"pvzh-mod-batch".equals(manifest.opt("format"))) throw new IOException("Unrecognized batch format");
            if (!isInteger(manifest.opt("schema"), 1)) throw new IOException("Unsupported batch schema");
            JSONArray items = manifest.optJSONArray("items");
            ZipEntry readme = entries.get("README.txt");
            if (items == null || items.length() == 0 || entries.size() != items.length() + 2
                    || readme == null || readme.isDirectory()) throw new IOException("Invalid batch index");
            byte[] buffer = new byte[65536];
            verify(zip, entries.remove("manifest.json"), buffer);
            verify(zip, entries.remove("README.txt"), buffer);
            long total = 0;
            for (int i = 0; i < items.length(); i++) {
                JSONObject row = items.optJSONObject(i);
                if (row == null || !validId(row.opt("id"))) throw new IOException("Invalid batch item ID");
                String name = "mods/" + row.getString("id") + ".zip";
                ZipEntry entry = entries.remove(name);
                if (!name.equals(row.opt("path")) || entry == null || entry.isDirectory()
                        || !isInteger(row.opt("bytes"), entry.getSize())) throw new IOException("Invalid batch item: " + name);
                total += entry.getSize();
                if (total > 8 * GIB || !verify(zip, entry, buffer).equals(row.opt("sha256"))) {
                    throw new IOException("Batch item exceeds the limit or has a different SHA-256: " + name);
                }
            }
        }
        checkedFile(path, 9 * GIB, sha);
        return manifest;
    }

    private static Map<String, ZipEntry> index(ZipFile zip, long entryLimit, long totalLimit) throws IOException {
        Map<String, ZipEntry> entries = new TreeMap<>();
        long total = 0;
        Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) {
            ZipEntry entry = all.nextElement();
            String name = entry.getName();
            checkPath(name, entry.isDirectory());
            if (entries.put(name, entry) != null) throw new IOException("Duplicate ZIP path: " + name);
            long size = entry.getSize();
            if (size < 0 || size > entryLimit || entry.getCrc() < 0 || size > totalLimit - total
                    || entries.size() > MAX_ENTRIES) throw new IOException("ZIP entry exceeds the archive limits: " + name);
            total += size;
        }
        return entries;
    }

    private static String verify(ZipFile zip, ZipEntry entry, byte[] buffer) throws IOException {
        MessageDigest digest = digest();
        CRC32 crc = new CRC32();
        long total = 0;
        try (InputStream in = zip.getInputStream(entry)) {
            for (int n; (n = in.read(buffer)) != -1;) {
                total += n;
                if (total > entry.getSize()) throw new IOException("ZIP entry length changed: " + entry.getName());
                crc.update(buffer, 0, n);
                digest.update(buffer, 0, n);
            }
        }
        if (total != entry.getSize() || crc.getValue() != entry.getCrc()) {
            throw new IOException("ZIP entry length or CRC mismatch: " + entry.getName());
        }
        return hex(digest.digest());
    }

    private static JSONObject readJson(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry == null || entry.isDirectory() || entry.getSize() > MAX_MANIFEST) throw new IOException("Missing or oversized manifest");
        byte[] bytes = new byte[(int) entry.getSize()];
        try (DataInputStream in = new DataInputStream(zip.getInputStream(entry))) {
            in.readFully(bytes);
            if (in.read() != -1) throw new IOException("Manifest length changed");
        }
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        return PreviewJson.object(text);
    }

    private static void validateManifest(JSONObject manifest, Map<String, ZipEntry> entries) throws IOException {
        if (manifest.has("schema") && !isInteger(manifest.opt("schema"), 1)) throw new IOException("Unsupported Mod schema");
        for (String key : new String[]{"id", "card_identity_namespace"}) {
            if (manifest.has(key) && !validId(manifest.opt(key))) throw new IOException("Invalid " + key);
        }
        if (!manifest.has("files")) return;
        JSONArray files = manifest.optJSONArray("files");
        if (files == null) throw new IOException("Manifest files must be an array");
        Map<String, String> declarations = new LinkedHashMap<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject row = files.optJSONObject(i);
            if (row == null || !(row.opt("source") instanceof String) || !(row.opt("logical") instanceof String)) {
                throw new IOException("Manifest file needs source and logical paths");
            }
            String source = row.getString("source"), logical = row.getString("logical");
            checkPath(source, false);
            checkPath(logical, false);
            ZipEntry entry = entries.get(source);
            if (entry == null || entry.isDirectory() || isManifest(source) || declarations.put(source, logical) != null) {
                throw new IOException("Missing or duplicate manifest source: " + source);
            }
        }
    }

    private static boolean isManifest(String path) { return path.equals("mod.json") || path.endsWith("/mod.json"); }

    private static String infer(String path, Collection<String> logicalNames) {
        String family = path.replaceFirst("_[0-9]+$", "");
        List<String> matches = new ArrayList<>();
        for (String name : logicalNames) {
            if (family.equals(name) || family.endsWith("/" + name)) matches.add(name);
        }
        if (matches.isEmpty()) {
            String basename = family.substring(family.lastIndexOf('/') + 1);
            for (String name : logicalNames) {
                if (name.substring(name.lastIndexOf('/') + 1).equals(basename)) matches.add(name);
            }
        }
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static void checkPath(String path, boolean directory) throws IOException {
        String name = directory ? path.substring(0, path.length() - 1) : path;
        if (name.indexOf('\\') >= 0 || name.indexOf(':') >= 0 || name.indexOf('\0') >= 0) {
            throw new IOException("Invalid ZIP path: " + path);
        }
        for (String part : name.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IOException("Invalid ZIP path: " + path);
        }
    }

    private static boolean isInteger(Object value, long expected) {
        return (value instanceof Integer || value instanceof Long) && ((Number) value).longValue() == expected;
    }

    private static boolean validId(Object value) {
        return value instanceof String && ((String) value).matches("[A-Za-z0-9_-]{1,80}");
    }

    private static String checkedFile(Path path, long limit, String expected) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > limit) throw new IOException("Missing or oversized ZIP");
        MessageDigest digest = digest();
        byte[] buffer = new byte[65536];
        long total = 0;
        try (InputStream in = Files.newInputStream(path)) {
            for (int n; (n = in.read(buffer)) != -1;) {
                if ((total += n) > limit) throw new IOException("ZIP size exceeds the limit");
                digest.update(buffer, 0, n);
            }
        }
        String actual = hex(digest.digest());
        if (expected != null && !expected.equals(actual)) throw new IOException("ZIP SHA-256 mismatch");
        return actual;
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(Character.forDigit((value >>> 4) & 15, 16))
                .append(Character.forDigit(value & 15, 16));
        return result.toString();
    }
}
