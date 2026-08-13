package santiivlog.dev.svvideo.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.block.BlockState;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import org.joml.Matrix4f;
import org.watermedia.api.media.players.MediaPlayer;
import santiivlog.dev.svvideo.block.SVVideoSpeakerBlock;
import santiivlog.dev.svvideo.block.entity.SVVideoSpeakerBlockEntity;

public class SVVideoSpeakerBlockEntityRenderer implements BlockEntityRenderer<SVVideoSpeakerBlockEntity> {

    public SVVideoSpeakerBlockEntityRenderer(BlockEntityRendererFactory.Context context) {
    }

    @Override
    public void render(SVVideoSpeakerBlockEntity entity, float tickDelta, MatrixStack matrices,
                       net.minecraft.client.render.VertexConsumerProvider vertexConsumers, int light, int overlay) {
        SVVideoWorldMediaManager.WorldMediaInstance instance = SVVideoWorldMediaManager.getOrCreate(entity);
        if (instance == null) {
            return;
        }

        boolean pushed = false;
        try {
            MediaPlayer player = instance.player();
            boolean hasVideoTexture = player != null && player.withVideo()
                    && player.texture() > 0 && player.width() > 0 && player.height() > 0;
            boolean showLoadingIndicator = !hasVideoTexture && instance.isLoading();
            if (!hasVideoTexture && !showLoadingIndicator) {
                return;
            }

            BlockState state = entity.getCachedState();
            Direction facing = state.get(SVVideoSpeakerBlock.FACING);
            float width = entity.getDisplayWidth();
            float height = entity.getDisplayHeight();
            int renderQuality = entity.getRenderQuality();
            float framePadding = 0.08f;
            float frameDepth = 0.03f;
            float bezelDepth = 0.006f;
            float videoDepth = 0.012f;
            float zOffset = 0.501f;

            matrices.push();
            pushed = true;
            matrices.translate(0.5f, 0.5f, 0.5f);
            matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facing.asRotation()));
            matrices.translate(0.0f, 0.0f, zOffset);

            Matrix4f matrix = matrices.peek().getPositionMatrix();

            float x1 = -width / 2.0f;
            float x2 = width / 2.0f;
            float y1 = -height / 2.0f;
            float y2 = height / 2.0f;
            float fx1 = x1 - framePadding;
            float fx2 = x2 + framePadding;
            float fy1 = y1 - framePadding;
            float fy2 = y2 + framePadding;

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.depthMask(true);

            RenderSystem.setShader(GameRenderer::getPositionColorProgram);
            BufferBuilder frameBuffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            frameBuffer.vertex(matrix, fx1, fy1, -frameDepth).color(18, 18, 22, 255);
            frameBuffer.vertex(matrix, fx2, fy1, -frameDepth).color(18, 18, 22, 255);
            frameBuffer.vertex(matrix, fx2, fy2, -frameDepth).color(18, 18, 22, 255);
            frameBuffer.vertex(matrix, fx1, fy2, -frameDepth).color(18, 18, 22, 255);
            BufferRenderer.drawWithGlobalProgram(frameBuffer.end());

            BufferBuilder bezelBuffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            bezelBuffer.vertex(matrix, fx1, fy1, -bezelDepth).color(48, 48, 54, 255);
            bezelBuffer.vertex(matrix, fx2, fy1, -bezelDepth).color(48, 48, 54, 255);
            bezelBuffer.vertex(matrix, fx2, fy2, -bezelDepth).color(48, 48, 54, 255);
            bezelBuffer.vertex(matrix, fx1, fy2, -bezelDepth).color(48, 48, 54, 255);
            BufferRenderer.drawWithGlobalProgram(bezelBuffer.end());

            RenderSystem.setShader(GameRenderer::getPositionTexProgram);
            if (hasVideoTexture) {
                float aspect = player.width() / (float) player.height();
                RenderSystem.setShaderTexture(0, (int) player.texture());
                if (renderQuality >= 100) {
                    drawTexturedQuad(matrix, x1, y1, x2, y2, videoDepth);
                } else {
                    drawPixelatedVideo(matrix, x1, y1, x2, y2, videoDepth, aspect, width, height, renderQuality);
                }
            } else {
                drawLoadingSurface(matrix, x1, y1, x2, y2, videoDepth);
            }

            RenderSystem.enableDepthTest();
            RenderSystem.enableCull();
            RenderSystem.disableBlend();
        } catch (Exception | LinkageError e) {
            instance.fail("Excepcion silenciosa en el render del bloque.", e, false);
        } finally {
            if (pushed) {
                matrices.pop();
            }
        }
    }

    private void drawLoadingSurface(Matrix4f matrix, float x1, float y1, float x2, float y2, float depth) {
        if (SVVideoLoadingIndicator.hasTexture()) {
            RenderSystem.setShaderTexture(0, (int) SVVideoLoadingIndicator.texture());

            drawTexturedQuad(matrix, x1, y1, x2, y2, depth);
            return;
        }

        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        buffer.vertex(matrix, x1, y1, depth).color(8, 8, 12, 255);
        buffer.vertex(matrix, x2, y1, depth).color(8, 8, 12, 255);
        buffer.vertex(matrix, x2, y2, depth).color(8, 8, 12, 255);
        buffer.vertex(matrix, x1, y2, depth).color(8, 8, 12, 255);
        BufferRenderer.drawWithGlobalProgram(buffer.end());

        float centerY = (y1 + y2) * 0.5f;
        float barWidth = (x2 - x1) * 0.18f;
        float barHeight = (y2 - y1) * 0.12f;
        float gap = barWidth * 0.45f;
        float startX = ((x1 + x2) * 0.5f) - (barWidth * 1.5f) - gap;

        BufferBuilder bars = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        for (int i = 0; i < 3; i++) {
            float bx1 = startX + i * (barWidth + gap);
            float bx2 = bx1 + barWidth;
            float by1 = centerY - barHeight * 0.5f;
            float by2 = centerY + barHeight * 0.5f;
            int alpha = 120 + (i * 40);
            bars.vertex(matrix, bx1, by1, depth + 0.0005f).color(180, 160, 255, alpha);
            bars.vertex(matrix, bx2, by1, depth + 0.0005f).color(180, 160, 255, alpha);
            bars.vertex(matrix, bx2, by2, depth + 0.0005f).color(180, 160, 255, alpha);
            bars.vertex(matrix, bx1, by2, depth + 0.0005f).color(180, 160, 255, alpha);
        }
        BufferRenderer.drawWithGlobalProgram(bars.end());
    }

    private void drawTexturedQuad(Matrix4f matrix, float x1, float y1, float x2, float y2, float depth) {
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        buffer.vertex(matrix, x1, y1, depth).texture(0, 1);
        buffer.vertex(matrix, x2, y1, depth).texture(1, 1);
        buffer.vertex(matrix, x2, y2, depth).texture(1, 0);
        buffer.vertex(matrix, x1, y2, depth).texture(0, 0);
        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }

    private void drawPixelatedVideo(Matrix4f matrix, float x1, float y1, float x2, float y2,
                                    float depth, float aspect, float widthBlocks, float heightBlocks,
                                    int renderQuality) {
        float qualityFactor = renderQuality / 100.0f;
        int rows = Math.max(6, Math.round(heightBlocks * (4.0f + 8.0f * qualityFactor)));
        int cols = Math.max(6, Math.round(rows * aspect));
        cols = Math.max(cols, Math.round(widthBlocks * (4.0f + 8.0f * qualityFactor)));

        float cellWidth = (x2 - x1) / cols;
        float cellHeight = (y2 - y1) / rows;
        BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);

        for (int row = 0; row < rows; row++) {
            float py1 = y1 + row * cellHeight;
            float py2 = py1 + cellHeight;
            float sampleV = 1.0f - ((row + 0.5f) / rows);

            for (int col = 0; col < cols; col++) {
                float px1 = x1 + col * cellWidth;
                float px2 = px1 + cellWidth;
                float sampleU = (col + 0.5f) / cols;

                buffer.vertex(matrix, px1, py1, depth).texture(sampleU, sampleV);
                buffer.vertex(matrix, px2, py1, depth).texture(sampleU, sampleV);
                buffer.vertex(matrix, px2, py2, depth).texture(sampleU, sampleV);
                buffer.vertex(matrix, px1, py2, depth).texture(sampleU, sampleV);
            }
        }

        BufferRenderer.drawWithGlobalProgram(buffer.end());
    }
}

