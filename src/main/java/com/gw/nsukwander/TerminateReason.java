package com.gw.nsukwander;

/** 闲逛终止原因（日志记录用） */
public enum TerminateReason {
    /** 一轮闲逛正常完成（回到住宅） */
    COMPLETED("正常完成"),
    /** 遭遇怪物攻击，交给 NSUK 生存 AI */
    ATTACKED("遭遇攻击"),
    /** 玩家右键交互 */
    PLAYER_INTERACT("玩家交互"),
    /** 血量过低，交给 NSUK 避险 AI */
    LOW_HP("血量过低"),
    /** 进入未加载区块 / 所在区块不再被玩家加载 */
    CHUNK_UNLOAD("区块卸载"),
    /** 跨维度传送 */
    DIMENSION_CHANGE("跨维度"),
    /** 路径中途方块失效 / 被堵路 */
    PATH_INVALID("路径失效"),
    /** 寻路持续超时失败 */
    STUCK("寻路超时"),
    /** 住宅被拆除：原地静止，不再触发闲逛 */
    HOME_DEMOLISHED("住宅拆除"),
    /** 所属城市被删除 */
    CITY_DELETED("城市删除"),
    /** 闲逛目标所在建筑被拆除 */
    BUILDING_REMOVED("建筑拆除"),
    /** 全局指令关闭 */
    GLOBAL_OFF("全局关闭"),
    /** NSUK 导航服务接管，让行 */
    YIELD_NSUK("NSUK接管"),
    /** 检测到其他模组修改 AI，永久跳过该市民 */
    CONFLICT("AI冲突"),
    /** 目标点生成失败 */
    NO_TARGET("无合格目标点"),
    /** 导航创建路径失败 */
    PATH_FAIL("寻路失败");

    private final String display;

    TerminateReason(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }
}
