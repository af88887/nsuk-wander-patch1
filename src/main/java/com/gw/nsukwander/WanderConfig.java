package com.gw.nsukwander;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * TOML 配置项（config/nsukwander-common.toml），键名与需求清单一一对应。
 * 所有取值在运行时通过 {@link #get()} 读取，支持热重载与指令修改。
 */
public final class WanderConfig {

    private WanderConfig() {
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /** 全局开关（指令 /nsukwander on|off 直接改写本项并持久化） */
    public static final ModConfigSpec.BooleanValue GLOBAL_ENABLE = BUILDER
            .comment("全局开关。关闭时正在闲逛的市民立刻终止并返回住宅，不再生成新闲逛任务。")
            .define("globalEnable", true);

    /** 闲逛移动速度（格/tick），通过导航速度系数实现 */
    public static final ModConfigSpec.DoubleValue WALK_SPEED = BUILDER
            .comment("闲逛移动速度（格/tick）。NSUK 市民基础移速属性为 0.28，0.22 约为步行的 80%。")
            .defineInRange("walkSpeed", 0.22D, 0.05D, 1.0D);

    /** 单次闲逛（去程）最大持续 tick */
    public static final ModConfigSpec.IntValue SINGLE_WALK_TICK_DURATION = BUILDER
            .comment("单次闲逛去程的最大 tick 数，超时则结束本次闲逛并返回住宅。")
            .defineInRange("singleWalkTickDuration", 2400, 200, 72000);

    /** 闲逛结束后的冷却 tick */
    public static final ModConfigSpec.IntValue COOLDOWN_TICK = BUILDER
            .comment("一轮闲逛结束后的冷却 tick。玩家传送市民不会重置该计时。")
            .defineInRange("cooldownTick", 3600, 0, 720000);

    /** 白天同时闲逛人数上限 */
    public static final ModConfigSpec.IntValue MAX_DAY_CITIZEN = BUILDER
            .comment("白天（主世界 0~13000 tick）同时闲逛的市民数量上限。")
            .defineInRange("maxDayCitizen", 30, 0, 500);

    /** 夜晚同时闲逛人数上限 */
    public static final ModConfigSpec.IntValue MAX_NIGHT_CITIZEN = BUILDER
            .comment("夜晚（主世界 13000~23000 tick）同时闲逛的市民数量上限。")
            .defineInRange("maxNightCitizen", 15, 0, 500);

    /** 夜晚触发概率 */
    public static final ModConfigSpec.DoubleValue NIGHT_TRIGGER_CHANCE = BUILDER
            .comment("夜晚冷却结束且回到住宅后，开启一轮闲逛的概率（白天固定为 1.0）。")
            .defineInRange("nightTriggerChance", 0.3D, 0.0D, 1.0D);

    public static final ModConfigSpec.IntValue MIN_TARGET_DISTANCE = BUILDER
            .comment("目标点与市民的最小水平距离（格）。")
            .defineInRange("minTargetDistance", 8, 2, 256);

    public static final ModConfigSpec.IntValue MAX_TARGET_DISTANCE = BUILDER
            .comment("目标点与市民的最大水平距离（格）。")
            .defineInRange("maxTargetDistance", 64, 4, 256);

    public static final ModConfigSpec.IntValue MIN_Y = BUILDER
            .comment("目标点允许的最低 Y（绝对高度）。")
            .defineInRange("minY", 60, -64, 320);

    public static final ModConfigSpec.IntValue MAX_Y = BUILDER
            .comment("目标点允许的最高 Y（绝对高度）。")
            .defineInRange("maxY", 90, -64, 320);

    /** 目标点生成最大重试轮数 */
    public static final ModConfigSpec.IntValue POINT_GEN_RETRY_TIMES = BUILDER
            .comment("目标点生成的最大重试轮数；全部失败则放弃本次闲逛。")
            .defineInRange("pointGenRetryTimes", 8, 1, 64);

    /** 每轮候选采样数量 */
    public static final ModConfigSpec.IntValue CANDIDATE_SAMPLE_COUNT = BUILDER
            .comment("每轮重试中采样的候选点数量，在合格点位中随机选取一个。")
            .defineInRange("candidateSampleCount", 4, 1, 32);

    /** 每 tick 最多新开启的闲逛任务数 */
    public static final ModConfigSpec.IntValue PER_TICK_PROCESS_COUNT = BUILDER
            .comment("每 tick 全服最多新开启的闲逛任务数，防止集中判定造成卡顿。")
            .defineInRange("perTickProcessCount", 4, 1, 100);

    /** 是否允许翻越 1 格高差 */
    public static final ModConfigSpec.BooleanValue ALLOW_STEP_CLIMB = BUILDER
            .comment("true=闲逛期间跨步高度设为 1.0（平滑走上 1 格高差）；false=保持 NSUK 默认。")
            .define("allowStepClimb", true);

    /** 是否允许穿过透明阻挡方块 */
    public static final ModConfigSpec.BooleanValue CAN_PASS_TRANSPARENT_BLOCK = BUILDER
            .comment("false=玻璃、栅栏门等透明阻挡方块在寻路与目标点过滤中视为不可通过（物理碰撞决定其本就不可穿越，本项主要约束目标点筛选与寻路节点评估）。")
            .define("canPassTransparentBlock", false);

    /** 最低日志等级：INFO / WARN / ERROR */
    public static final ModConfigSpec.ConfigValue<String> LOG_LEVEL = BUILDER
            .comment("最低输出日志等级：INFO / WARN / ERROR。")
            .define("logLevel", "WARN");

    /** 是否输出调试日志 */
    public static final ModConfigSpec.BooleanValue ENABLE_DEBUG_LOG = BUILDER
            .comment("true=额外输出 DEBUG 级别日志（不受 logLevel 限制），用于排查闲逛触发/寻路细节。")
            .define("enableDebugLog", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    // ---------- 便捷访问 ----------

    public static boolean globalEnable() {
        return GLOBAL_ENABLE.get();
    }

    public static double walkSpeed() {
        return WALK_SPEED.get();
    }

    public static int singleWalkTickDuration() {
        return SINGLE_WALK_TICK_DURATION.get();
    }

    public static int cooldownTick() {
        return COOLDOWN_TICK.get();
    }

    public static int maxDayCitizen() {
        return MAX_DAY_CITIZEN.get();
    }

    public static int maxNightCitizen() {
        return MAX_NIGHT_CITIZEN.get();
    }

    public static double nightTriggerChance() {
        return NIGHT_TRIGGER_CHANCE.get();
    }

    public static int minTargetDistance() {
        return MIN_TARGET_DISTANCE.get();
    }

    public static int maxTargetDistance() {
        return MAX_TARGET_DISTANCE.get();
    }

    public static int minY() {
        return MIN_Y.get();
    }

    public static int maxY() {
        return MAX_Y.get();
    }

    public static int pointGenRetryTimes() {
        return POINT_GEN_RETRY_TIMES.get();
    }

    public static int candidateSampleCount() {
        return CANDIDATE_SAMPLE_COUNT.get();
    }

    public static int perTickProcessCount() {
        return PER_TICK_PROCESS_COUNT.get();
    }

    public static boolean allowStepClimb() {
        return ALLOW_STEP_CLIMB.get();
    }

    public static boolean canPassTransparentBlock() {
        return CAN_PASS_TRANSPARENT_BLOCK.get();
    }

    public static String logLevel() {
        return LOG_LEVEL.get();
    }

    public static boolean enableDebugLog() {
        return ENABLE_DEBUG_LOG.get();
    }
}
