package com.yuneko.foliocleaner;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 服务端设置：{@code config/foliocleaner/server-settings.json}
 *
 * <pre>
 * { "adapterCheck": "kick", "unifyMode": "rewrite" }
 * </pre>
 *
 * <ul>
 *   <li>{@code adapterCheck}：{@code kick}（默认）指纹不一致直接断开 / {@code warn} 只提示 / {@code off} 不检查</li>
 *   <li>{@code unifyMode}：<b>统一的实现方式由服务端说了算</b>
 *       （{@code datapack} / {@code rewrite} / {@code match}）。
 *       登录时随规则一起下发，客户端那份设置会变灰 —— 否则两边方式不同，
 *       会出现「客户端按 A 改宽、服务端按 B 改宽」的幽灵配方。</li>
 * </ul>
 */
public final class ServerSettings {

    public static String adapterCheck = "kick";
    /** 统一实现方式（服务端权威）。默认 rewrite：单人 / 集成服最省，专用服也能跑 */
    public static String unifyMode = "rewrite";

    private ServerSettings() {
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("foliocleaner").resolve("server-settings.json");
    }

    public static void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            // 还没有 server-settings.json：把客户端当前选的那个当默认
            // （2.0.0 早期版本把「统一实现方式」存在 client-settings.json 里，升级上来不该被静默改成 rewrite）
            try {
                if (validMode(ClientSettings.unifyMode)) unifyMode = ClientSettings.unifyMode;
            } catch (Throwable ignored) {
            }
            return;
        }
        try {
            var root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return;
            JsonObject obj = root.getAsJsonObject();
            if (obj.has("adapterCheck") && obj.get("adapterCheck").isJsonPrimitive()) {
                String mode = obj.get("adapterCheck").getAsString().toLowerCase(java.util.Locale.ROOT);
                if ("kick".equals(mode) || "warn".equals(mode) || "off".equals(mode)) {
                    adapterCheck = mode;
                }
            }
            if (obj.has("unifyMode") && obj.get("unifyMode").isJsonPrimitive()) {
                String mode = obj.get("unifyMode").getAsString();
                if (validMode(mode)) {
                    unifyMode = mode;
                } else {
                    FolioCleaner.LOGGER.warn("[Folio Cleaner] server-settings.json 里的 unifyMode={} 不认识，用 rewrite", mode);
                }
            } else {
                // 老文件没有这一项：把客户端当前选的那个当默认（2.0.0 早期版本把选择存在 client-settings.json）
                try {
                    if (validMode(ClientSettings.unifyMode)) unifyMode = ClientSettings.unifyMode;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 读取 server-settings.json 失败（用默认值）", t);
        }
    }

    public static void save() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            JsonObject obj = new JsonObject();
            obj.addProperty("adapterCheck", adapterCheck);
            obj.addProperty("unifyMode", unifyMode);
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(obj),
                    StandardCharsets.UTF_8);
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 写 server-settings.json 失败", t);
        }
    }

    public static boolean validMode(String mode) {
        return "datapack".equals(mode) || "rewrite".equals(mode) || "match".equals(mode);
    }
}
