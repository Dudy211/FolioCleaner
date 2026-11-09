package com.yuneko.foliocleaner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 服务端设置：{@code config/foliocleaner/server-settings.json}
 *
 * <pre>{ "adapterCheck": "kick" }</pre>
 *
 * <ul>
 *   <li>{@code kick}（默认）：客户端适配器指纹不一致就直接断开，并列出差异</li>
 *   <li>{@code warn}：只提示 + 日志，不踢</li>
 *   <li>{@code off}：不检查</li>
 * </ul>
 */
public final class ServerSettings {

    public static String adapterCheck = "kick";

    private ServerSettings() {
    }

    public static Path file() {
        return FMLPaths.CONFIGDIR.get().resolve("foliocleaner").resolve("server-settings.json");
    }

    public static void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) return;
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
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 读取 server-settings.json 失败（用默认值）", t);
        }
    }
}
