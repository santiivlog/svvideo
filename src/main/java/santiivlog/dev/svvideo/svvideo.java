package santiivlog.dev.svvideo;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import santiivlog.dev.svvideo.block.SVVideoBlocks;
import santiivlog.dev.svvideo.command.svvideocommands;
import santiivlog.dev.svvideo.network.SVVideoSpeakerActionPayload;
import santiivlog.dev.svvideo.network.SVVideoSpeakerStatePayload;
import santiivlog.dev.svvideo.network.SVVideoMediaChunkPayload;
import santiivlog.dev.svvideo.network.SVVideoMediaRequestPayload;
import santiivlog.dev.svvideo.network.SVVideoMediaTransfer;
import santiivlog.dev.svvideo.network.SVvideonetwork;
import santiivlog.dev.svvideo.util.SVVideoFiles;

public class svvideo implements ModInitializer {

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playS2C().register(SVvideonetwork.ID, SVvideonetwork.CODEC);
        PayloadTypeRegistry.playS2C().register(SVVideoSpeakerStatePayload.ID, SVVideoSpeakerStatePayload.CODEC);
        PayloadTypeRegistry.playS2C().register(SVVideoMediaChunkPayload.ID, SVVideoMediaChunkPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SVVideoSpeakerActionPayload.ID, SVVideoSpeakerActionPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(SVVideoMediaRequestPayload.ID, SVVideoMediaRequestPayload.CODEC);
        SVVideoSpeakerActionPayload.registerReceiver();
        SVVideoMediaTransfer.registerReceiver();
        SVVideoBlocks.register();
        svvideocommands.register();
        SVVideoFiles.createFolder();
    }
}
