package santiivlog.dev.svvideo.block.entity;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import santiivlog.dev.svvideo.block.SVVideoBlocks;
public class SVVideoSpeakerBlockEntity extends BlockEntity {
    private String sourceMode = "url";
    private String mediaSource = "";
    private int volume = 100;
    private int minDistance = 5;
    private int maxDistance = 20;
    private int displayWidth = 1;
    private int displayHeight = 1;
    private int renderQuality = 100;
    private boolean paused = false;
    private boolean reloadPending = false;

    public SVVideoSpeakerBlockEntity(BlockPos pos, BlockState state) {
        super(SVVideoBlocks.SPEAKER_BLOCK_ENTITY, pos, state);
    }

    public static void serverTick(ServerWorld world, BlockPos pos, BlockState state, SVVideoSpeakerBlockEntity blockEntity) {
        blockEntity.reloadPending = false;
    }

    public int computeVolume(double distance) {
        if (distance <= minDistance) {
            return volume;
        }

        if (maxDistance <= minDistance) {
            return volume;
        }

        double t = (distance - minDistance) / (double) (maxDistance - minDistance);
        double scaled = volume * Math.max(0.0, 1.0 - t);
        return Math.max(0, Math.min(100, (int) Math.round(scaled)));
    }

    public void stopAll(ServerWorld world) {
    }

    public void requestReload() {
        this.reloadPending = true;
        markDirty();
    }

    public void requestReloadAll(ServerWorld world) {
        stopAll(world);
        this.reloadPending = true;
        markDirty();
    }

    public void togglePaused(ServerWorld world) {
        this.paused = !this.paused;
        if (this.paused) {
            stopAll(world);
        } else {
            this.reloadPending = true;
        }
        markDirty();
    }

    public String getSourceMode() {
        return sourceMode;
    }

    public void setSourceMode(String sourceMode) {
        this.sourceMode = sourceMode;
    }

    public String getMediaSource() {
        return mediaSource;
    }

    public void setMediaSource(String mediaSource) {
        this.mediaSource = mediaSource;
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = Math.max(0, Math.min(100, volume));
    }

    public int getMinDistance() {
        return minDistance;
    }

    public void setMinDistance(int minDistance) {
        this.minDistance = Math.max(0, minDistance);
    }

    public int getMaxDistance() {
        return maxDistance;
    }

    public void setMaxDistance(int maxDistance) {
        this.maxDistance = Math.max(1, maxDistance);
    }

    public int getDisplayWidth() {
        return displayWidth;
    }

    public void setDisplayWidth(int displayWidth) {
        this.displayWidth = Math.max(1, Math.min(50, displayWidth));
    }

    public int getDisplayHeight() {
        return displayHeight;
    }

    public void setDisplayHeight(int displayHeight) {
        this.displayHeight = Math.max(1, Math.min(50, displayHeight));
    }

    public int getRenderQuality() {
        return renderQuality;
    }

    public void setRenderQuality(int renderQuality) {
        this.renderQuality = Math.max(25, Math.min(100, renderQuality));
    }

    public boolean isPaused() {
        return paused;
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.writeNbt(nbt, registries);
        nbt.putString("SourceMode", sourceMode);
        nbt.putString("MediaSource", mediaSource);
        nbt.putInt("Volume", volume);
        nbt.putInt("MinDistance", minDistance);
        nbt.putInt("MaxDistance", maxDistance);
        nbt.putInt("DisplayWidth", displayWidth);
        nbt.putInt("DisplayHeight", displayHeight);
        nbt.putInt("RenderQuality", renderQuality);
        nbt.putBoolean("Paused", paused);
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt, registries);
        sourceMode = nbt.contains("SourceMode") ? nbt.getString("SourceMode") : "url";
        mediaSource = nbt.contains("MediaSource") ? nbt.getString("MediaSource") : "";
        volume = nbt.contains("Volume") ? nbt.getInt("Volume") : 100;
        minDistance = nbt.contains("MinDistance") ? nbt.getInt("MinDistance") : 5;
        maxDistance = nbt.contains("MaxDistance") ? nbt.getInt("MaxDistance") : 20;
        displayWidth = nbt.contains("DisplayWidth") ? nbt.getInt("DisplayWidth") : (nbt.contains("BlockScale") ? nbt.getInt("BlockScale") : 1);
        displayHeight = nbt.contains("DisplayHeight") ? nbt.getInt("DisplayHeight") : 1;
        renderQuality = nbt.contains("RenderQuality") ? nbt.getInt("RenderQuality") : 100;
        paused = nbt.contains("Paused") && nbt.getBoolean("Paused");
    }

    @Override
    public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registries) {
        return createNbt(registries);
    }

    @Override
    public Packet<ClientPlayPacketListener> toUpdatePacket() {
        return BlockEntityUpdateS2CPacket.create(this);
    }
}

