package dev.comfyfluffy.caustica.rt.entity;

import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.comfyfluffy.caustica.rt.material.RtMaterials;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/**
 * A {@link VertexConsumer} that records the posed entity geometry vanilla emits — exactly the same bulk
 * {@code addVertex(x,y,z,color,u,v,overlay,light,nx,ny,nz)} that {@code ModelPart.Cube.compile} calls
 * (4 verts/quad → 2 triangles). {@link RtEntityCollector} drives it by calling
 * {@code model.renderToBuffer(pose, this, …)}.
 *
 * <p>Accumulators use the same layout as terrain's {@code SectionMesh} (positions, indices, atlas UV,
 * per-prim {@code {normal.xyz, emission}, {tint.rgb, texSlot}, {rough, metal, 0, 0}}) so entities
 * share the terrain upload + BLAS path verbatim.
 */
public final class RtEntityCapture implements VertexConsumer {
    private static final int DEFAULT_VERTEX_CAPACITY = 1024;
    // Same magnitude as RtTerrain.QuadCapture.OFFSET (2e-4 blocks) — proven large enough to break a BVH
    // depth tie without a visible gap at terrain/entity scale.
    private static final float ORDER_OFFSET = 2.0e-4f;

    final FloatArrayList verts = new FloatArrayList(DEFAULT_VERTEX_CAPACITY * 3);   // 3 floats/vertex (capture-space position)
    final IntArrayList idx = new IntArrayList(indexCapacity(DEFAULT_VERTEX_CAPACITY)); // 3 indices/triangle
    final FloatArrayList uvList = new FloatArrayList(DEFAULT_VERTEX_CAPACITY * 2);  // 2 floats/vertex (entity-texture UV)
    final FloatArrayList prim = new FloatArrayList(primCapacity(DEFAULT_VERTEX_CAPACITY)); // 12 floats/triangle: normal.xyz+0, tint.rgb+texSlot, mat.{rough,metal,0,0}

    // Bindless texture slot for the geometry currently being submitted (set by the collector per
    // submitModel, so body + feature layers get their own texture). Stored per-prim in tint.w;
    // the hit shader samples entityTex[texSlot].
    int currentTexSlot;
    // Whether the current submission's bindless slot has a LabPBR _s / _n map (→ prim mat.z/mat.w).
    // Set by the collector per submission; mobs (per-type textures) may have them, atlas-sourced quads don't.
    boolean currentHasS;
    boolean currentHasN;
    // Block-atlas geometry (dropped/held block items, falling blocks, contained block displays) samples the
    // BLOCK atlas, so its LabPBR _s/_n live in the terrain parallel atlases (blockSpecAtlas/blockNormalAtlas)
    // — NOT the per-type bindless arrays. When set, currentHasS/currentHasN refer to those: emitQuad encodes
    // mat.z/mat.w as 2 (block atlas) instead of 1 (bindless entity), so world.rchit samples the right atlas.
    boolean currentBlockAtlas;
    // Whether the current submission is an alpha-blended (translucent) render type — slime / sulfur-cube
    // shells, ghosts, … Stored per-prim in the otherwise-unused entity emission lane (normal.w); world.rahit
    // reads it and does stochastic transparency for those surfaces instead of a binary cutout, so the inner
    // content (slime core, the sulfur cube's contained block) shows through the shell. Set by the collector
    // per submission (model bodies only — block/item/particle paths force it false).
    boolean currentTranslucent;
    // Decal-stacking rank for the current submission (0 = no offset). Set by the collector from
    // SubmitNodeCollector#order(int) — see emitQuad's coincident-layer push.
    int currentOrder;
    // When a model textures from an atlas sprite (block entities: chests/signs/beds via a Material),
    // its ModelPart UVs are 0..1 in a virtual texture and must be remapped into the sprite's atlas
    // region — the work vanilla's sprite-coordinate-expander VertexConsumer does, which we bypass.
    // Off for full-texture models (mobs, sprite == null) and for baked quads (already atlas-space).
    private boolean uvRemap;
    private float uvU0, uvV0, uvDU, uvDV;

    private int n; // quad vertex accumulator (0..3)
    private final float[] qx = new float[4], qy = new float[4], qz = new float[4];
    private final float[] qu = new float[4], qv = new float[4];
    private final float[] qnx = new float[4], qny = new float[4], qnz = new float[4];
    private final int[] qcol = new int[4];
    private final Vector3f scratch = new Vector3f(); // baked-quad position transform scratch

    /** Clear all accumulators for a fresh entity capture. */
    public void reset() {
        reset(0);
    }

    /** Clear and pre-size for a known previous topology to avoid add() growth during capture. */
    public void reset(int expectedVertices) {
        verts.clear();
        idx.clear();
        uvList.clear();
        prim.clear();
        ensureVertexCapacity(expectedVertices);
        n = 0;
        currentTexSlot = 0;
        currentHasS = false;
        currentHasN = false;
        currentBlockAtlas = false;
        currentTranslucent = false;
        currentOrder = 0;
        uvRemap = false;
    }

    private void ensureVertexCapacity(int vertexCount) {
        if (vertexCount <= 0) {
            return;
        }
        verts.ensureCapacity(vertexCount * 3);
        idx.ensureCapacity(indexCapacity(vertexCount));
        uvList.ensureCapacity(vertexCount * 2);
        prim.ensureCapacity(primCapacity(vertexCount));
    }

    private static int indexCapacity(int vertexCount) {
        int quadCount = (vertexCount + 3) / 4;
        return quadCount * 6;
    }

    private static int primCapacity(int vertexCount) {
        int quadCount = (vertexCount + 3) / 4;
        return quadCount * 24;
    }

    /** Remap subsequent {@link #addVertex} (ModelPart) UVs from 0..1 into a sprite's atlas region. */
    public void setUvRemap(float u0, float v0, float u1, float v1) {
        uvRemap = true;
        uvU0 = u0;
        uvV0 = v0;
        uvDU = u1 - u0;
        uvDV = v1 - v0;
    }

    /** Use ModelPart UVs as-is (full-texture models). */
    public void clearUvRemap() {
        uvRemap = false;
    }

    public boolean isEmpty() {
        return idx.isEmpty();
    }

    @Override
    public void addVertex(float x, float y, float z, int color, float u, float v,
                          int overlay, int light, float nx, float ny, float nz) {
        if (uvRemap) { // ModelPart 0..1 UV → sprite's atlas region (block-entity Material sprites)
            u = uvU0 + u * uvDU;
            v = uvV0 + v * uvDV;
        }
        qx[n] = x; qy[n] = y; qz[n] = z;
        qu[n] = u; qv[n] = v;
        qnx[n] = nx; qny[n] = ny; qnz[n] = nz;
        qcol[n] = color;
        if (++n == 4) {
            emitQuad();
            n = 0;
        }
    }

    /**
     * Capture a {@link BakedQuad} (held/dropped items via {@code submitItem}, falling blocks via {@code
     * submitBlockModel}) — its 4 positions transformed by {@code pose}, atlas UV from {@code packedUV},
     * a flat {@code color} tint. These quads carry no authored normal, so emitQuad computes a geometric
     * one. They sample the block atlas (the capture's {@code currentTexSlot} = 0, the bindless fallback).
     */
    public void addBakedQuad(Matrix4f pose, BakedQuad quad, int color) {
        for (int i = 0; i < 4; i++) {
            Vector3fc p = quad.position(i);
            pose.transformPosition(p.x(), p.y(), p.z(), scratch);
            long uv = quad.packedUV(i);
            qx[n] = scratch.x; qy[n] = scratch.y; qz[n] = scratch.z;
            qu[n] = Float.intBitsToFloat((int) (uv >>> 32));
            qv[n] = Float.intBitsToFloat((int) uv);
            qnx[n] = 0f; qny[n] = 0f; qnz[n] = 0f; // no authored normal → emitQuad falls back to geometric
            qcol[n] = color;
            if (++n == 4) {
                emitQuad();
                n = 0;
            }
        }
    }

    private void emitQuad() {
        appendQuad(qx, qy, qz, qu, qv, qnx[0], qny[0], qnz[0], qcol[0], false);
    }

    /**
     * Append one already-transformed quad without routing through the VertexConsumer accumulator.
     * Used by leash ribbons and custom line/geometry paths.
     */
    void addDirectQuad(float[] x, float[] y, float[] z, float[] u, float[] v,
                       float nx, float ny, float nz, int color) {
        appendQuad(x, y, z, u, v, nx, ny, nz, color, uvRemap);
    }

    /** Fail fast before a later submission can complete a malformed custom-geometry quad. */
    void requireCompleteQuads(String label) {
        if (n != 0) {
            int incomplete = n;
            n = 0;
            throw new IllegalStateException(label + " left an incomplete quad (" + incomplete + " vertices)");
        }
    }

    private void appendQuad(float[] x, float[] y, float[] z, float[] u, float[] v,
                            float nxIn, float nyIn, float nzIn, int color, boolean remapUv) {
        float nx = nxIn, ny = nyIn, nz = nzIn;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len <= 1.0e-6f) {
            float ex1 = x[1] - x[0], ey1 = y[1] - y[0], ez1 = z[1] - z[0];
            float ex2 = x[2] - x[0], ey2 = y[2] - y[0], ez2 = z[2] - z[0];
            nx = ey1 * ez2 - ez1 * ey2;
            ny = ez1 * ex2 - ex1 * ez2;
            nz = ex1 * ey2 - ey1 * ex2;
            len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        }
        if (len > 1.0e-6f) {
            nx /= len;
            ny /= len;
            nz /= len;
        }
        float ox = 0f, oy = 0f, oz = 0f;
        if (currentOrder != 0 && len > 1.0e-6f) {
            float off = ORDER_OFFSET * currentOrder;
            ox = nx * off;
            oy = ny * off;
            oz = nz * off;
        }

        int base = verts.size() / 3;
        for (int i = 0; i < 4; i++) {
            verts.add(x[i] + ox);
            verts.add(y[i] + oy);
            verts.add(z[i] + oz);
            float uu = u[i], vv = v[i];
            if (remapUv) {
                uu = uvU0 + uu * uvDU;
                vv = uvV0 + vv * uvDV;
            }
            uvList.add(uu);
            uvList.add(vv);
        }
        idx.add(base);
        idx.add(base + 1);
        idx.add(base + 2);
        idx.add(base);
        idx.add(base + 2);
        idx.add(base + 3);
        float tr = ((color >> 16) & 0xFF) * (1f / 255f);
        float tg = ((color >> 8) & 0xFF) * (1f / 255f);
        float tb = (color & 0xFF) * (1f / 255f);
        for (int t = 0; t < 2; t++) {
            prim.add(nx);
            prim.add(ny);
            prim.add(nz);
            prim.add(currentTranslucent ? 1f : 0f);
            prim.add(tr);
            prim.add(tg);
            prim.add(tb);
            prim.add((float) currentTexSlot);
            prim.add(RtMaterials.ENTITY_ROUGH);
            prim.add(0f);
            float matSource = currentBlockAtlas ? 2f : 1f;
            prim.add(currentHasS ? matSource : 0f);
            prim.add(currentHasN ? matSource : 0f);
        }
    }

    // Unused VertexConsumer surface — ModelPart.Cube.compile only calls the bulk addVertex above.
    @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
    @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
    @Override public VertexConsumer setColor(int color) { return this; }
    @Override public VertexConsumer setUv(float u, float v) { return this; }
    @Override public VertexConsumer setUv1(int u, int v) { return this; }
    @Override public VertexConsumer setUv2(int u, int v) { return this; }
    @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    @Override public VertexConsumer setLineWidth(float width) { return this; }
}
