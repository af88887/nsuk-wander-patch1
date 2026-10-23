package com.gw.nsukwander;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

/**
 * 分级日志：INFO / WARN / ERROR 受 logLevel 下限控制；DEBUG 由 enableDebugLog 单独开关。
 * 记录事件：闲逛开始、终止原因、寻路失败、异常拦截、冲突跳过。
 */
public final class WanderLogger {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int L_DEBUG = 0;
    private static final int L_INFO = 1;
    private static final int L_WARN = 2;
    private static final int L_ERROR = 3;

    private WanderLogger() {
    }

    private static int minLevel() {
        String lv = WanderConfig.logLevel();
        if (lv == null) {
            return L_WARN;
        }
        return switch (lv.trim().toUpperCase()) {
            case "DEBUG" -> L_DEBUG;
            case "INFO" -> L_INFO;
            case "ERROR" -> L_ERROR;
            default -> L_WARN;
        };
    }

    public static void info(String msg, Object... args) {
        if (minLevel() <= L_INFO) {
            LOGGER.info("[nsukwander] " + msg, args);
        }
    }

    public static void warn(String msg, Object... args) {
        if (minLevel() <= L_WARN) {
            LOGGER.warn("[nsukwander] " + msg, args);
        }
    }

    public static void error(String msg, Object... args) {
        if (minLevel() <= L_ERROR) {
            LOGGER.error("[nsukwander] " + msg, args);
        }
    }

    public static void error(String msg, Throwable t) {
        if (minLevel() <= L_ERROR) {
            LOGGER.error("[nsukwander] " + msg, t);
        }
    }

    public static void debug(String msg, Object... args) {
        if (WanderConfig.enableDebugLog()) {
            LOGGER.info("[nsukwander][debug] " + msg, args);
        }
    }
}
