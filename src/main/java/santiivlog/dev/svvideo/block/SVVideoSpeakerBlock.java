package santiivlog.dev.svvideo.block;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import santiivlog.dev.svvideo.block.entity.SVVideoSpeakerBlockEntity;
import santiivlog.dev.svvideo.network.SVVideoSpeakerStatePayload;

public class SVVideoSpeakerBlock extends Block implements BlockEntityProvider {
    public static final MapCodec<SVVideoSpeakerBlock> CODEC = createCodec(SVVideoSpeakerBlock::new);
    public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;

    public SVVideoSpeakerBlock(Settings settings) {
        super(settings);
        setDefaultState(getStateManager().getDefaultState().with(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends Block> getCodec() {
        return CODEC;
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        return BlockRenderType.INVISIBLE;
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public @Nullable BlockState getPlacementState(ItemPlacementContext ctx) {
        return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    public @Nullable BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new SVVideoSpeakerBlockEntity(pos, state);
    }

    @Override
    protected ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
        if (world.isClient()) {
            return ActionResult.SUCCESS;
        }

        if (!(player instanceof ServerPlayerEntity serverPlayer)) {
            return ActionResult.PASS;
        }

        if (!serverPlayer.hasPermissionLevel(2)) {
            return ActionResult.FAIL;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof SVVideoSpeakerBlockEntity speaker) {
            SVVideoSpeakerStatePayload.send(serverPlayer, speaker);
            return ActionResult.CONSUME;
        }

        return ActionResult.PASS;
    }

    @Override
    public @Nullable <T extends BlockEntity> net.minecraft.block.entity.BlockEntityTicker<T> getTicker(
            World world, BlockState state, net.minecraft.block.entity.BlockEntityType<T> type) {
        if (world.isClient() || type != SVVideoBlocks.SPEAKER_BLOCK_ENTITY) {
            return null;
        }
        return (tickWorld, pos, tickState, blockEntity) -> SVVideoSpeakerBlockEntity.serverTick(
                (net.minecraft.server.world.ServerWorld) tickWorld,
                pos,
                tickState,
                (SVVideoSpeakerBlockEntity) blockEntity
        );
    }
}

