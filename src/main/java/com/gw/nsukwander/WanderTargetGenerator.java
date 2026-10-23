package com.gw.nsukwander;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 闲逛目标点生成器。
 *
 * <p>规则（全部来自需求清单）：
 * <ul>
 *   <li>实心可站立地面，非虚空、非液体；</li>
 *   <li>落在城市绿色边界（已声明区块）与指定 Y 区间内，水平距离 [minTargetDistance, maxTargetDistance]；</li>
 *   <li>过滤危险方块：岩浆、TNT、仙人掌、甜浆果丛；过滤上锁（不可手开）门后方、区块边缘、铁轨矿车碰撞区域；</li>
 *   <li>点位可在道路也可在经营建筑内部（只要求可站立，不限制必须在室外）；</li>
 *   <li>多次候选采样：最多 pointGenRetryTimes 轮，每轮 candidateSampleCount 个候选，合格点位中随机选一个；全部失败返回 null。</li>
 * </ul>
 */
public final class WanderTargetGenerator {

    private WanderTargetGenerator() {
    }

    /**
     * @param center     采样中心（市民当前位置，触发时市民在住宅附近）
     * @param cityChunks 城市已声明区块（绿色边界，packed long 集合）
     * @return 合格目标点；重试耗尽返回 null
     */
    @Nullable
    public static BlockPos generate(ServerLevel level, BlockPos center, Set<Long> cityChunks, RandomSource random) {
        if (cityChunks.isEmpty()) {
            return null;
        }
        int minDist = WanderConfig.minTargetDistance();
        int maxDist = Math.max(minDist + 1, WanderConfig.maxTargetDistance());
        int minY = WanderConfig.minY();
        int maxY = Math.max(minY, WanderConfig.maxY());
        int retries = WanderConfig.pointGenRetryTimes();
        int samples = WanderConfig.candidateSampleCount();

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        for (int attempt = 0; attempt < retries; attempt++) {
            List<BlockPos> valid = null;
            for (int i = 0; i < samples; i++) {
                double angle = random.nextDouble() * Mth.TWO_PI;
                double dist = minDist + random.nextDouble() * (maxDist - minDist);
                int x = center.getX() + (int) Math.round(Math.cos(angle) * dist);
                int z = center.getZ() + (int) Math.round(Math.sin(angle) * dist);
                // Y 以市民高度为基准在配置区间内随机取，随后向下/向上微调找可站立面
                int yBase = Mth.clamp(center.getY() + random.nextInt(13) - 6, minY, maxY);

                BlockPos candidate = findStandable(level, x, yBase, z, minY, maxY, center, minDist, maxDist, cityChunks, cursor);
                if (candidate != null) {
                    if (valid == null) {
                        valid = new ArrayList<>(samples);
                    }
                    valid.add(candidate);
                }
            }
            if (valid != null && !valid.isEmpty()) {
                return valid.get(random.nextInt(valid.size()));
            }
        }
        return null;
    }

    /** 在 (x,z) 处、以 yBase 为起点上下扫描（先下后上），找最近的可站立点位 */
    @Nullable
    private static BlockPos findStandable(ServerLevel level, int x, int yBase, int z,
                                          int minY, int maxY,
                                          BlockPos center, int minDist, int maxDist,
                                          Set<Long> cityChunks, BlockPos.MutableBlockPos cursor) {
        // 区块边缘过滤：局部坐标 1..14，避开跨区块边界判定死角
        int lx = x & 15;
        int lz = z & 15;
        if (lx < 1 || lx > 14 || lz < 1 || lz > 14) {
            return null;
        }
        // 绿色边界过滤：目标区块必须属于城市
        if (!cityChunks.contains(ChunkPos.asLong(x >> 4, z >> 4))) {
            return null;
        }
        // 未加载区块不处理（同时避免 getBlockState 触发区块加载）
        cursor.set(x, yBase, z);
        if (!level.hasChunkAt(cursor)) {
            return null;
        }
        // 水平距离校验（构造上已满足，Y 微调不改变水平坐标，此处是防御性复核）
        double dx = x - center.getX();
        double dz = z - center.getZ();
        double distSqr = dx * dx + dz * dz;
        if (distSqr < (double) minDist * minDist || distSqr > (double) maxDist * maxDist) {
            return null;
        }

        for (int dy = 0; dy <= 5; dy++) {
            // 交替向下/向上：0, -1, +1, -2, +2 ...
            int y = yBase + (dy == 0 ? 0 : (dy % 2 == 1 ? -((dy + 1) / 2) : dy / 2));
            if (y < minY || y > maxY || y <= level.getMinBuildHeight()) {
                continue;
            }
            cursor.set(x, y, z);
            if (isValidStandPoint(level, cursor)) {
                return cursor.immutable();
            }
        }
        return null;
    }

    /**
     * 可站立点位判定：
     * 脚下=实心可站立地面（非液体、非危险方块）；脚部与头部=无碰撞（非液体、非危险、非铁轨、非上锁门）。
     */
    private static boolean isValidStandPoint(ServerLevel level, BlockPos pos) {
        BlockState ground = level.getBlockState(pos.below());
        // 实心可站立地面：上表面坚固；排除液体与虚空
        if (!ground.isFaceSturdy(level, pos.below(), Direction.UP)) {
            return false;
        }
        if (!ground.getFluidState().isEmpty()) {
            return false;
        }
        if (isDangerous(level, pos.below(), ground)) {
            return false;
        }

        BlockState feet = level.getBlockState(pos);
        if (!isFreePassable(level, pos, feet)) {
            return false;
        }
        BlockState head = level.getBlockState(pos.above());
        if (!isFreePassable(level, pos.above(), head)) {
            return false;
        }

        // 危险方块邻域过滤（脚部一圈 + 头顶，防止贴着岩浆/仙人掌的点）
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos near = pos.relative(dir);
            if (isDangerous(level, near, level.getBlockState(near))) {
                return false;
            }
            // 铁轨矿车碰撞区域：相邻格含铁轨即过滤
            if (level.getBlockState(near).getBlock() instanceof BaseRailBlock) {
                return false;
            }
            // 上锁门后方过滤：相邻格存在不可手开的门（铁门等），说明该点可能在锁闭区域内侧
            BlockState nearState = level.getBlockState(near);
            if (isLockedDoor(nearState)) {
                return false;
            }
            // 透明阻挡方块过滤（玻璃/关闭的栅栏门），canPassTransparentBlock=false 时不允许贴身穿行的点位
            if (!WanderConfig.canPassTransparentBlock() && isTransparentBarrier(level, near, nearState)) {
                // 仅当目标点被透明方块“夹住”（对向两侧同时是透明阻挡）时过滤，避免误杀靠窗的正常点位
                BlockState opposite = level.getBlockState(pos.relative(dir.getOpposite()));
                if (isTransparentBarrier(level, pos.relative(dir.getOpposite()), opposite)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** 脚部/头部可通过：无碰撞、无液体、非危险、非铁轨、非上锁门 */
    private static boolean isFreePassable(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (isDangerous(level, pos, state)) {
            return false;
        }
        if (state.getBlock() instanceof BaseRailBlock) {
            return false;
        }
        // 门方块：可手开的木门允许作为目标点（闲逛时可开门通过），上锁（不可手开）门过滤
        return !isLockedDoor(state);
    }

    /** 危险方块：岩浆（含流动）、TNT、仙人掌、甜浆果丛、火 */
    private static boolean isDangerous(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.getFluidState().is(FluidTags.LAVA)) {
            return true;
        }
        return state.is(Blocks.TNT)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.FIRE)
                || state.is(Blocks.MAGMA_BLOCK);
    }

    /** 上锁（不可空手开启）的门：铁门及模组的锁闭门（canOpenByHand=false 统一视为上锁） */
    private static boolean isLockedDoor(BlockState state) {
        return state.getBlock() instanceof DoorBlock door && !door.type().canOpenByHand();
    }

    /** 透明阻挡方块：玻璃类、关闭的栅栏门（有碰撞但视觉上透明） */
    private static boolean isTransparentBarrier(ServerLevel level, BlockPos pos, BlockState state) {
        if (state.getBlock() instanceof AbstractGlassBlock) {
            return true;
        }
        if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
            return true;
        }
        // 其他透明实心碰撞体（玻璃板等）：不遮光但有碰撞
        return !state.canOcclude() && !state.getCollisionShape(level, pos).isEmpty();
    }

    /** 供 Goal 使用：快速复核某点位在闲逛途中是否仍然有效（路径方块失效检测） */
    public static boolean isStillValidTarget(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        return isValidStandPoint(level, pos);
    }

    /** 供 Goal 使用：路径下一节点是否仍可通行（被破坏/堵路检测） */
    public static boolean isNodePassable(ServerLevel level, BlockPos pos) {
        if (!level.hasChunkAt(pos)) {
            return false;
        }
        BlockState feet = level.getBlockState(pos);
        // 脚下有坚固地面且脚部无碰撞（或是可开门），视为可通行
        BlockState ground = level.getBlockState(pos.below());
        boolean groundOk = ground.isFaceSturdy(level, pos.below(), Direction.UP)
                || feet.getBlock() instanceof DoorBlock;
        if (!groundOk) {
            return false;
        }
        if (!feet.getCollisionShape(level, pos).isEmpty() && !(feet.getBlock() instanceof DoorBlock)) {
            return false;
        }
        return isFreePassable(level, pos, feet) || feet.getBlock() instanceof DoorBlock;
    }

    /** Vec3 转换工具（供 Goal 使用） */
    public static Vec3 toCenterPos(BlockPos pos) {
        return Vec3.atBottomCenterOf(pos);
    }
}
