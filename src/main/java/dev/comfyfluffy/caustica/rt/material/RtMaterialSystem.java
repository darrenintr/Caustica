package dev.comfyfluffy.caustica.rt.material;

import dev.comfyfluffy.caustica.rt.entity.RtEntityTextures;
import dev.comfyfluffy.caustica.rt.pipeline.RtPipeline;

/**
 * Lifecycle boundary for every LabPBR texture resource consumed by the world RT pipeline.
 *
 * <p>The implementation stores remain deliberately specialized:
 * <ul>
 *     <li>{@link RtBlockMaterials} owns the block-atlas-sized {@code _s}/{@code _n} textures.</li>
 *     <li>{@link RtEntityTextures} owns bindless slots and standalone entity material textures.</li>
 *     <li>{@link RtEntityMaterials} owns block-entity parallel atlases, but their lifetime is coupled to
 *     {@code RtEntityTextures} because their views are installed in the same bindless descriptor set.</li>
 * </ul>
 * This facade is the only renderer/client lifecycle coordinator; extraction code may still query the
 * specialized stores directly on its hot path.
 *
 * <p>{@link #releaseAfterPipelineDestroy()} must only run after the world pipeline has been destroyed (and
 * in-flight work drained), because that pipeline's descriptor sets reference all resources released here.
 * Render-thread only.
 */
public final class RtMaterialSystem {
    public static final RtMaterialSystem INSTANCE = new RtMaterialSystem();

    /** Block parallel-atlas views prepared for a newly created world pipeline. */
    public record BlockAtlasViews(long specular, long normal) {
        private static final BlockAtlasViews NONE = new BlockAtlasViews(0L, 0L);
    }

    private RtMaterialSystem() {
    }

    /** Current configured capacity for the world pipeline's bindless material arrays. */
    public int bindlessTextureCapacity() {
        return RtEntityTextures.maxTextures();
    }

    /**
     * Reset descriptor-coupled entity state and build the block LabPBR atlases for a new world pipeline.
     * The previous pipeline must already be gone when this recreates resource views.
     */
    public BlockAtlasViews prepareForPipeline(int descriptorCapacity, boolean blockAtlasesSupported) {
        RtEntityTextures.INSTANCE.resetForPipeline(descriptorCapacity);
        if (!blockAtlasesSupported) {
            RtBlockMaterials.INSTANCE.destroy();
            return BlockAtlasViews.NONE;
        }

        RtBlockMaterials.INSTANCE.reset();
        RtBlockMaterials.INSTANCE.prepareAll();
        return new BlockAtlasViews(
                RtBlockMaterials.INSTANCE.viewS(),
                RtBlockMaterials.INSTANCE.viewN());
    }

    /** Upload all material changes registered during terrain/entity extraction before tracing. */
    public void flushBeforeTrace(RtPipeline pipeline, long sampler) {
        RtEntityTextures.INSTANCE.uploadPending(pipeline, sampler);
        RtBlockMaterials.INSTANCE.flush();
        RtEntityMaterials.INSTANCE.flushAll();
    }

    /**
     * Release all descriptor-coupled LabPBR resources after the owning world pipeline has been destroyed.
     * Entity parallel atlases are released transitively by {@link RtEntityTextures}; they have one owner.
     */
    public void releaseAfterPipelineDestroy() {
        RtEntityTextures.INSTANCE.destroy();
        RtBlockMaterials.INSTANCE.destroy();
    }

    /** Renderer shutdown alias with an explicit ownership name at the client boundary. */
    public void destroy() {
        releaseAfterPipelineDestroy();
    }
}
