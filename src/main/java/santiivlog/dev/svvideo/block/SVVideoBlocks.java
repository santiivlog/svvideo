package santiivlog.dev.svvideo.block;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import santiivlog.dev.svvideo.block.entity.SVVideoSpeakerBlockEntity;

public final class SVVideoBlocks {
    public static final Block SPEAKER_BLOCK = Registry.register(
            Registries.BLOCK,
            Identifier.of("svvideo", "speaker_block"),
            new SVVideoSpeakerBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.IRON_GRAY)
                    .strength(2.0f)
                    .sounds(BlockSoundGroup.METAL))
    );

    public static final BlockEntityType<SVVideoSpeakerBlockEntity> SPEAKER_BLOCK_ENTITY = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            Identifier.of("svvideo", "speaker_block_entity"),
            BlockEntityType.Builder.create(SVVideoSpeakerBlockEntity::new, SPEAKER_BLOCK).build()
    );

    private SVVideoBlocks() {
    }

    public static void register() {
        Registry.register(
                Registries.ITEM,
                Identifier.of("svvideo", "speaker_block"),
                new BlockItem(SPEAKER_BLOCK, new Item.Settings())
        );
    }
}

