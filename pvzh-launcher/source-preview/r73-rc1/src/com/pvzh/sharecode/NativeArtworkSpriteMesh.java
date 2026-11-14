package com.pvzh.sharecode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static com.pvzh.sharecode.NativeArtworkTree.Value;

/** Static triangles for the known, unrotated Sprite render data layout. */
final class NativeArtworkSpriteMesh {
    private static final int MAX_VERTICES = 2048;
    private static final int MAX_INDICES = 12288;
    private static final double EPSILON = 1.0 / 1024;

    private NativeArtworkSpriteMesh() {}

    /** Call for the exact Sprite and local texture dimensions accepted by the pixel reader. */
    static Mesh read(Value sprite, int textureWidth, int textureHeight) {
        if (sprite == null || !"Sprite".equals(sprite.node.type) || textureWidth < 1 || textureHeight < 1
                || textureWidth > 4096 || textureHeight > 4096) return null;
        try {
            return readSupported(sprite, textureWidth, textureHeight);
        } catch (IOException unsupported) {
            return null;
        }
    }

    private static Mesh readSupported(Value sprite, int textureWidth, int textureHeight) throws IOException {
        Value render = sprite.field("m_RD");
        if (sprite.field("m_Bones").arrayCount != 0 || render.field("m_Bindpose").arrayCount != 0
                || !zeroPointer(sprite.field("m_SpriteAtlas")) || !zeroPointer(render.field("alphaTexture"))
                || integer(render.field("texture"), "m_FileID") != 0
                || integer(render.field("texture"), "m_PathID") == 0
                || render.field("secondaryTextures").arrayCount != 0
                || (integer(render, "settingsRaw") & ~64L) != 0
                || render.field("downscaleMultiplier").number() != 1) return null;

        Value source = sprite.field("m_Rect"), crop = render.field("textureRect");
        double sourceX = number(source, "x"), sourceY = number(source, "y");
        double sourceWidth = number(source, "width"), sourceHeight = number(source, "height");
        double x = number(crop, "x"), y = number(crop, "y");
        double width = number(crop, "width"), height = number(crop, "height");
        if (!pixelRect(sourceX, sourceY, sourceWidth, sourceHeight, textureWidth, textureHeight)
                || !pixelRect(x, y, width, height, textureWidth, textureHeight)
                || x < sourceX || y < sourceY || x + width > sourceX + sourceWidth
                || y + height > sourceY + sourceHeight) return null;

        Value pivot = sprite.field("m_Pivot"), offset = render.field("textureRectOffset"), uv = render.field("uvTransform");
        double pivotX = number(pivot, "x"), pivotY = number(pivot, "y"), ppu = sprite.field("m_PixelsToUnits").number();
        double originX = number(uv, "y"), originY = number(uv, "w");
        if (!(pivotX >= 0 && pivotX <= 1 && pivotY >= 0 && pivotY <= 1) || !Double.isFinite(ppu) || ppu <= 0
                || !near(number(uv, "x"), ppu) || !near(number(uv, "z"), ppu)
                || !near(originX, sourceX + pivotX * sourceWidth) || !near(originY, sourceY + pivotY * sourceHeight)
                || !near(number(offset, "x"), x - sourceX) || !near(number(offset, "y"), y - sourceY)) return null;

        Value vertexData = render.field("m_VertexData"), channels = vertexData.field("m_Channels");
        long vertexCount = integer(vertexData, "m_VertexCount");
        if (vertexCount < 3 || vertexCount > MAX_VERTICES || channels.elements.size() != 14) return null;
        int count = (int) vertexCount;
        for (int i = 0; i < channels.elements.size(); i++) {
            Value channel = channels.elements.get(i);
            int dimension = i == 0 ? 3 : i == 4 ? 2 : 0;
            if (integer(channel, "dimension") != dimension || integer(channel, "stream") != (i == 4 ? 1 : 0)
                    || integer(channel, "format") != 0 || integer(channel, "offset") != 0) return null;
        }

        Value submeshes = render.field("m_SubMeshes");
        if (submeshes.elements.size() != 1) return null;
        Value submesh = submeshes.elements.get(0);
        long indexCount = integer(submesh, "indexCount");
        if (indexCount < 3 || indexCount > MAX_INDICES || indexCount % 3 != 0
                || integer(submesh, "firstByte") != 0 || integer(submesh, "topology") != 0
                || integer(submesh, "baseVertex") != 0 || integer(submesh, "firstVertex") != 0
                || integer(submesh, "vertexCount") != count) return null;

        int uvOffset = (count * 12 + 15) & ~15;
        ByteBuffer vertices = bytes(vertexData.field("m_DataSize"), uvOffset + count * 8);
        ByteBuffer indices = bytes(render.field("m_IndexBuffer"), (int) indexCount * 2);
        if (vertices == null || indices == null) return null;
        boolean explicitUv = false;
        for (int i = uvOffset; i < vertices.limit(); i += 4) explicitUv |= vertices.getFloat(i) != 0;

        float[] points = new float[count * 2];
        for (int i = 0; i < count; i++) {
            double px = vertices.getFloat(i * 12) * ppu + originX;
            double py = vertices.getFloat(i * 12 + 4) * ppu + originY;
            if (!within(px, x, x + width) || !within(py, y, y + height) || vertices.getFloat(i * 12 + 8) != 0
                    || explicitUv && (!near(vertices.getFloat(uvOffset + i * 8) * (double) textureWidth, px)
                    || !near(vertices.getFloat(uvOffset + i * 8 + 4) * (double) textureHeight, py))) return null;
            points[i * 2] = unit((px - x) / width);
            points[i * 2 + 1] = unit(1 - (py - y) / height);
        }

        float[] triangles = new float[(int) indexCount * 2];
        for (int i = 0; i < triangles.length; i += 6) {
            int a = indices.getShort() & 0xffff, b = indices.getShort() & 0xffff, c = indices.getShort() & 0xffff;
            if (a >= count || b >= count || c >= count) return null;
            float ax = points[a * 2], ay = points[a * 2 + 1];
            float bx = points[b * 2], by = points[b * 2 + 1];
            float cx = points[c * 2], cy = points[c * 2 + 1];
            double cross = (double) (bx - ax) * (cy - ay) - (double) (by - ay) * (cx - ax);
            if (cross == 0) return null;
            triangles[i] = ax;
            triangles[i + 1] = ay;
            triangles[i + 2] = cross > 0 ? bx : cx;
            triangles[i + 3] = cross > 0 ? by : cy;
            triangles[i + 4] = cross > 0 ? cx : bx;
            triangles[i + 5] = cross > 0 ? cy : by;
        }
        return new Mesh(triangles, (float) ((originX - x) / width), (float) (1 - (originY - y) / height));
    }

    private static long integer(Value tree, String name) throws IOException { return tree.field(name).integer(); }
    private static double number(Value tree, String name) throws IOException { return tree.field(name).number(); }

    private static boolean zeroPointer(Value ptr) throws IOException {
        return integer(ptr, "m_FileID") == 0 && integer(ptr, "m_PathID") == 0;
    }

    private static boolean pixelRect(double x, double y, double w, double h, int textureWidth, int textureHeight) {
        return x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= textureWidth && y + h <= textureHeight
                && x == Math.rint(x) && y == Math.rint(y) && w == Math.rint(w) && h == Math.rint(h);
    }

    private static boolean near(double actual, double expected) { return Math.abs(actual - expected) <= EPSILON; }
    private static boolean within(double value, double min, double max) { return value >= min - EPSILON && value <= max + EPSILON; }
    private static float unit(double value) { return (float) Math.max(0, Math.min(1, value)); }

    private static ByteBuffer bytes(Value data, int expectedLength) {
        byte[] source = data.rawData == null ? data.source : data.rawData;
        if (source == null || data.rawLength != expectedLength || data.rawOffset < 0
                || data.rawOffset > source.length - expectedLength) return null;
        return ByteBuffer.wrap(source, data.rawOffset, expectedLength).slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    static final class Mesh {
        final float[] triangles;
        final float pivotX, pivotY;

        Mesh(float[] triangles, float pivotX, float pivotY) {
            this.triangles = triangles;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
        }
    }
}
