package com.gw.nsukwander;

import common.cn.kafei.simukraft.citizen.CitizenData;
import common.cn.kafei.simukraft.citizen.CitizenHomeRestService;
import common.cn.kafei.simukraft.citizen.CitizenManager;
import common.cn.kafei.simukraft.citizen.CitizenWorkStatus;
import common.cn.kafei.simukraft.city.CityChunkManager;
import common.cn.kafei.simukraft.city.CityData;
import common.cn.kafei.simukraft.city.CityManager;
import common.cn.kafei.simukraft.city.poi.CityPoiData;
import common.cn.kafei.simukraft.city.poi.CityPoiManager;
import common.cn.kafei.simukraft.job.CityJobType;
import common.cn.kafei.simukraft.path.CitizenNavigationService;
import common.cn.kafei.simukraft.registry.ModBlocks;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * NSUK 公开 API 的防御性封装。
 *
 * <p>所有调用包裹 try/catch：若 NSUK 版本变动导致方法签名/行为异常，
 * 每个调用点只记录一次 ERROR 并返回安全默认值，闲逛功能自动停用，绝不影响 NSUK 本体逻辑。
 */
public final class NsukApi {

    /** 记录已报过错的调用点，避免刷屏 */
    private static final Set<String> REPORTED_FAILURES =
            Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    private NsukApi() {
    }

    private static <T> T guard(String callSite, T fallback, java.util.concurrent.Callable<T> call) {
        try {
            return call.call();
        } catch (Throwable t) {
            if (REPORTED_FAILURES.add(callSite)) {
                WanderLogger.error("NSUK API 调用失败（已自动降级，仅报告一次）: " + callSite + " -> " + t);
            }
            return fallback;
        }
    }

    // ---------- 市民数据 ----------

    @Nullable
    public static CitizenData citizenData(ServerLevel level, UUID uuid) {
        return guard("CitizenManager.getCitizen", null,
                () -> CitizenManager.get(level).getCitizen(uuid).orElse(null));
    }

    /** 无业判定：jobType 为 UNEMPLOYED；jobType 缺失时回退判断 jobId */
    public static boolean isUnemployed(@Nullable CitizenData data) {
        if (data == null) {
            return false;
        }
        return guard("CitizenData.jobType", false, () -> {
            CityJobType type = data.jobType();
            if (type != null) {
                return type == CityJobType.UNEMPLOYED;
            }
            String jobId = data.jobId();
            return jobId == null || jobId.isEmpty() || "unemployed".equalsIgnoreCase(jobId);
        });
    }

    /** NSUK 是否正在接管该市民的主动作息（工作/休息/死亡状态不闲逛） */
    public static boolean isBusyStatus(@Nullable CitizenData data) {
        if (data == null) {
            return true;
        }
        return guard("CitizenData.workStatus", true, () -> {
            if (data.dead() || data.working()) {
                return true;
            }
            CitizenWorkStatus ws = data.workStatusType();
            if (ws == CitizenWorkStatus.WORKING || ws == CitizenWorkStatus.RESTING || ws == CitizenWorkStatus.DEAD) {
                return true;
            }
            // home_rest 标记：NSUK 居家休息服务正在管理
            String need = data.workNeedDetail();
            return CitizenHomeRestService.HOME_REST_MARKER.equals(need);
        });
    }

    // ---------- 住宅 / POI ----------

    /** 市民住宅 POI（必须存在且激活；拆除/停用返回 empty） */
    public static Optional<CityPoiData> homePoi(ServerLevel level, CitizenData data) {
        return guard("CityPoiManager.getPoi", Optional.empty(), () -> {
            UUID homeId = data.homeId();
            if (homeId == null) {
                return Optional.empty();
            }
            CityPoiData poi = CityPoiManager.get(level).getPoi(homeId);
            if (poi == null || !poi.active()) {
                return Optional.empty();
            }
            return Optional.of(poi);
        });
    }

    /** 解析住宅落脚点（床边等），复用 NSUK 居家逻辑，失败回退 POI 中心 */
    public static Vec3 resolveHomeTarget(ServerLevel level, BlockPos homePos) {
        return guard("CitizenHomeRestService.resolveHomeTarget",
                Vec3.atBottomCenterOf(homePos),
                () -> {
                    Vec3 v = CitizenHomeRestService.resolveHomeTarget(level, homePos);
                    return v != null ? v : Vec3.atBottomCenterOf(homePos);
                });
    }

    /** 城市全部 POI（用于目标建筑拆除检测） */
    public static List<CityPoiData> cityPois(ServerLevel level, UUID cityId) {
        return guard("CityPoiManager.getCityPois", List.of(),
                () -> CityPoiManager.get(level).getCityPois(cityId));
    }

    // ---------- 城市 ----------

    @Nullable
    public static CityData city(ServerLevel level, UUID cityId) {
        return guard("CityManager.getCity", null,
                () -> CityManager.get(level).getCity(cityId).orElse(null));
    }

    /**
     * 城市是否"停用"（数据仍在但核心方块已被拆除）。
     * 核心区块未加载时视为正常，避免误判。
     */
    public static boolean isCityDisabled(ServerLevel level, CityData city) {
        return guard("ModBlocks.CITY_CORE", false, () -> {
            BlockPos core = city.cityCorePos();
            if (core == null || !level.hasChunkAt(core)) {
                return false;
            }
            return !level.getBlockState(core).is(ModBlocks.CITY_CORE.get());
        });
    }

    /** 城市已声明区块（绿色边界），key 为 ChunkPos 的 packed long */
    public static Set<Long> cityChunks(ServerLevel level, UUID cityId) {
        return guard("CityChunkManager.getCityChunks", Set.of(),
                () -> Set.copyOf(CityChunkManager.get(level).getCityChunks(cityId)));
    }

    // ---------- NSUK 导航状态 ----------

    /** NSUK 导航服务是否正在驱动该市民（生存 AI / 工作 / 休息等），是则本补丁让行 */
    public static boolean isNsukNavigating(ServerLevel level, UUID uuid) {
        return guard("CitizenNavigationService.isNavigating", true,
                () -> CitizenNavigationService.isNavigating(level, uuid));
    }
}
