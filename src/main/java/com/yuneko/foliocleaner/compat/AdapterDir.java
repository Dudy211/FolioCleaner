package com.yuneko.foliocleaner.compat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yuneko.foliocleaner.ClientSettings;
import com.yuneko.foliocleaner.FolioCleaner;
import net.minecraftforge.fml.loading.FMLPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * **统一适配器目录**：{@code config/foliocleaner/adapters/}
 *
 * <p>只认扩展名，自动分类：
 * <ul>
 *   <li>{@code *.json} → 声明式（槽位 + 动作），可静态校验</li>
 *   <li>{@code *.lua}  → 脚本式（白名单 API + 指令预算）</li>
 *   <li>{@code *.jar}  → 代码式（ServiceLoader 找 {@link CraftGuard}）</li>
 * </ul>
 *
 * <p>这里只做「列出来 + 开关状态」，不负责执行。执行分别由
 * {@code DeclarativeGuards}（json）/ {@code LuaGuards}（lua）/ {@code CraftGuards}（jar）接管 ——
 * 这样界面只有一份数据源，不会出现"界面说有、实际没生效"。
 */
public final class AdapterDir {

    public static final String KIND_JSON = "json";
    public static final String KIND_LUA = "lua";
    public static final String KIND_JAR = "jar";

    /** 界面一行 */
    public record Entry(String kind, String id, String name, String file, boolean enabled,
                        String tooltip, String detail) {
    }

    private static List<Entry> cache;
    /** jar 适配器要重启才生效（类加载器无法卸载） */
    private static boolean jarNeedsRestart;
    /** 待重启的 jar 文件名（给界面提示用） */
    private static final List<String> pendingJars = new ArrayList<>();

    private AdapterDir() {
    }

    public static Path folder() {
        Path dir = FMLPaths.CONFIGDIR.get().resolve("foliocleaner").resolve("adapters");
        try {
            Files.createDirectories(dir);
        } catch (Throwable ignored) {
        }
        return dir;
    }

    /** 界面用：按类型分组的全部适配器（缓存到 reload） */
    public static List<Entry> all() {
        if (cache == null) reload();
        return cache;
    }

    public static List<Entry> of(String kind) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : all()) {
            if (e.kind().equals(kind)) out.add(e);
        }
        return out;
    }

    /** 让下次 all() 重新算（服务端下发了新的开关状态时用） */
    public static void invalidate() {
        cache = null;
    }

    public static boolean jarNeedsRestart() {
        return jarNeedsRestart;
    }

    public static int reload() {
        List<Entry> out = new ArrayList<>();
        Path dir = folder();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path f : ds) {
                String name = f.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
                if (name.endsWith(".json")) {
                    Entry e = readJson(f);
                    if (e != null) out.add(e);
                } else if (name.endsWith(".lua")) {
                    out.add(new Entry(KIND_LUA, stem(f), stem(f), f.getFileName().toString(),
                            !ClientSettings.disabledGuards.contains(stem(f)),
                            "脚本式：可写条件/循环（沙箱 + 20ms 预算）", ""));
                }
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 扫描适配器目录失败：{}", dir, t);
        }

        // jar：来自 CraftGuards 已注册的那些（外部 jar 通过 ServiceLoader 导入）
        for (CraftGuards.GuardInfo info : CraftGuards.infos()) {
            String kind = KIND_JAR;
            out.add(new Entry(kind, info.id(), info.name(), info.source(), info.enabled(),
                    "代码式：任意逻辑、可调别的 mod API", info.id()));
        }

        // ★结构 hook（带 mixin 的 jar）：仍然归在 jar 类别，名字加 [hook] 前缀
        try {
            java.util.Set<String> mirrored = HookMirror.mirroredNames();
            for (java.nio.file.Path hookJar : HookMirror.scanHooks()) {
                String file = hookJar.getFileName().toString();
                String stem = file.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
                        ? file.substring(0, file.length() - 4) : file;
                String id = "hook:" + stem;
                boolean isMirrored = mirrored.contains(HookMirror.PREFIX + file);
                out.add(new Entry(KIND_JAR, id, "[hook] " + stem, file,
                        !ClientSettings.disabledGuards.contains(id),
                        "结构 hook 适配器：需要镜像到 mods/ 才能加载 mixin；开关即时生效",
                        isMirrored ? "已镜像到 mods/（重启后生效）" : "待镜像（重启后生效）"));
            }
            if (net.minecraftforge.fml.ModList.get().isLoaded("ars_nouveau")) {
                String id = "hook:arsnouveau";
                out.add(new Entry(KIND_JAR, id, "[hook] 新生魔艺·仪式（内置）", "内置",
                        !ClientSettings.disabledGuards.contains(id),
                        "命中「删除」规则的物品无法被仪式消耗（内置 hook）",
                        "内置 · 随模组加载"));
            }
        } catch (Throwable ignored) {
        }
        HookMirror.invalidateCache();

        out.sort((a, b) -> {
            int k = a.kind().compareTo(b.kind());
            return k != 0 ? k : a.name().compareToIgnoreCase(b.name());
        });
        cache = out;
        try {
            DeclarativeGuards.reload();     // 「同步加载」顺带让 json 适配器立即生效
            AdapterLoader.loadAll(CraftGuards::register);   // 新丢进来的 jar 也能立刻认出来
            LuaGuards.reload();             // lua 也一起重读
        } catch (Throwable ignored) {
        }
        return out.size();
    }

    /** 切换某个适配器的开关（json/lua 用 id；jar 走 CraftGuards） */
    public static void setEnabled(Entry entry, boolean enabled) {
        if (entry == null) return;
        // 结构 hook（含内置）：开关走 disabledGuards，hook 自己在运行期查 → 立即生效
        if (entry.id() != null && entry.id().startsWith("hook:")) {
            List<String> list = ClientSettings.disabledGuards;
            if (enabled) {
                list.remove(entry.id());
            } else if (!list.contains(entry.id())) {
                list.add(entry.id());
            }
            ClientSettings.touch();
            cache = null;
            return;
        }
        if (KIND_JAR.equals(entry.kind())) {
            CraftGuards.setEnabled(entry.id(), enabled);
            cache = null;
            return;
        }
        List<String> list = ClientSettings.disabledGuards;
        if (enabled) {
            list.remove(entry.id());
        } else if (!list.contains(entry.id())) {
            list.add(entry.id());
        }
        ClientSettings.touch();
        cache = null;
    }

    /**
     * 整套适配器的**单值指纹**：客户端进服时只比这一个值。
     *
     * <pre>
     * material = "v1;" + 按 (kind,id) 排序的 "kind|id|内容哈希|enabled;"
     * fp       = sha1(material) 前 16 位
     * </pre>
     *
     * <p>为什么这么设计：
     * <ul>
     *   <li>用**文件内容**哈希 → 同名不同内容也能抓出来（整合包更新适配器最常见的坑）</li>
     *   <li>**enabled 不进指纹** —— 开关由服务端下发（见 AdapterCheckPacket），不是"内容"</li>
     *   <li>带 {@code v1} 版本号 → 以后算法改了会天然不匹配，不会静默错判</li>
     *   <li>不含绝对路径 / 时间戳 → 两台机器才可能算出同一个值</li>
     * </ul>
     */
    public static String fingerprint() {
        StringBuilder sb = new StringBuilder("v1;");
        List<Entry> list = new ArrayList<>(all());
        list.sort(java.util.Comparator.comparing(Entry::kind).thenComparing(Entry::id));
        for (Entry e : list) {
            sb.append(e.kind()).append('|').append(e.id()).append('|')
                    .append(contentHash(e)).append(';');   // enabled 不进指纹：开关由服务端同步，不是内容
        }
        String sha = com.yuneko.foliocleaner.UnifyManifest.sha1(
                sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return sha.length() >= 16 ? sha.substring(0, 16) : sha;
    }

    /** 全部适配器 id（"kind:id"，逗号分隔）—— 用来给玩家报差异清单 */
    public static String idList() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : all()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.kind()).append(':').append(e.id());
        }
        return sb.toString();
    }

    /**
     * 「id=内容哈希前 8 位」逗号分隔 —— 校验时能指名道姓说**哪个**适配器内容不一样。
     *
     * <p>{@link #fingerprint()} 只能回答「有一套不一样」，玩家还得自己一个个试；
     * 带上每个 id 的哈希之后，服务端就能直接回「lua:altar 内容不同」。
     * 单值指纹仍然保留（最快的放行路径），这份只在指纹对不上时才用。
     */
    public static String hashList() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : all()) {
            if (sb.length() > 0) sb.append(',');
            String h = contentHash(e);
            sb.append(e.kind()).append(':').append(e.id()).append('=')
                    .append(h.length() > 8 ? h.substring(0, 8) : h);
        }
        return sb.toString();
    }

    private static String contentHash(Entry e) {
        try {
            if (!KIND_JAR.equals(e.kind())) {
                Path f = folder().resolve(e.file());
                if (Files.isRegularFile(f)) {
                    return com.yuneko.foliocleaner.UnifyManifest.sha1(Files.readAllBytes(f));
                }
            }
        } catch (Throwable ignored) {
        }
        // jar：拿不到稳定的内容指纹，用 id（它带 jar 名/版本）
        return e.id();
    }

    /** 记下"新增的 jar 需要重启"（reload 时比对已注册的 jar） */
    public static void noteJarChange() {
        jarNeedsRestart = true;
    }

    // ---------------------------------------------------------------- 内部

    private static Entry readJson(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) return null;
            JsonObject obj = root.getAsJsonObject();
            String id = str(obj, "id", stem(file));
            String name = str(obj, "name", id);
            String tip = str(obj, "comment", "");
            StringBuilder detail = new StringBuilder();
            if (obj.has("scope") && obj.get("scope").isJsonObject()) {
                JsonObject scope = obj.getAsJsonObject("scope");
                if (scope.has("menu")) detail.append("menu=").append(str(scope, "menu", "?"));
                if (scope.has("block")) detail.append(" block=").append(str(scope, "block", "?"));
            }
            if (obj.has("probe") && obj.get("probe").isJsonObject()) {
                JsonObject probe = obj.getAsJsonObject("probe");
                detail.append(" · 结果槽=").append(probe.has("result") ? probe.get("result").toString() : "?");
                detail.append(" 输入槽=").append(probe.has("inputs") ? probe.get("inputs").toString() : "?");
            }
            return new Entry(KIND_JSON, id, name, file.getFileName().toString(),
                    !ClientSettings.disabledGuards.contains(id), tip, detail.toString());
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 适配器 json 解析失败：{}", file, t);
            return null;
        }
    }

    private static String stem(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static String str(JsonObject obj, String key, String def) {
        try {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) return obj.get(key).getAsString();
        } catch (Throwable ignored) {
        }
        return def;
    }
}
