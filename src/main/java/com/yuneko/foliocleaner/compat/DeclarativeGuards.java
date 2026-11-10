package com.yuneko.foliocleaner.compat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.yuneko.foliocleaner.ClientSettings;
import com.yuneko.foliocleaner.FolioCleaner;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * **声明式（json）适配器的执行层**。
 *
 * <p>一个 json 描述「哪个菜单 → 哪些结果槽 / 输入槽 → 命中规则就清空结果」：
 * <pre>
 * {
 *   "id": "arcane_anvil",
 *   "name": "奥术铁砧",
 *   "comment": "悬浮提示里显示这句话",
 *   "scope": { "menu": "irons_spellbooks.ArcaneAnvilMenu" },
 *   "probe": { "result": [2], "inputs": [0, 1] },
 *   "action": "clearResult"
 * }
 * </pre>
 *
 * <p>执行点只有两个（都不需要新 mixin 配置外的东西）：
 * <ul>
 *   <li>{@code AbstractContainerMenu#broadcastChanges} 的 TAIL（每 tick，覆盖绝大多数容器型机器）</li>
 *   <li>将来可加 {@code slotsChanged}（更即时，可选）</li>
 * </ul>
 * 没声明过任何 json 适配器时，第一次检查就返回，开销是一次静态字段读取。
 *
 * <p><b>安全策略</b>：任何异常 → 放过（fail-open）+ warn；槽位越界 → 该条适配器本次跳过。
 */
public final class DeclarativeGuards {

    /** 一条解析好的声明式适配器 */
    public static final class Spec {
        final String id;
        final String name;
        final String menu;          // 全限定名或简名，null = 不限
        final String block;         // 方块 id（v1 只用于展示/未来扩展）
        final int[] result;
        final int[] inputs;
        final String comment;

        Spec(String id, String name, String menu, String block, int[] result, int[] inputs, String comment) {
            this.id = id;
            this.name = name;
            this.menu = menu;
            this.block = block;
            this.result = result;
            this.inputs = inputs;
            this.comment = comment;
        }

        public String id() {
            return id;
        }

        public String name() {
            return name;
        }
    }

    private static List<Spec> specs = List.of();
    private static Path loadedFrom;
    /** 重入闸门：命中后会调 menu.broadcastChanges()，那会再次回到 {@link #check} */
    private static volatile boolean checking;
    /** 已经打过日志的 (菜单类#槽位#适配器) ，避免每 tick 刷屏 */
    private static final Set<String> LOGGED = new HashSet<>();

    private DeclarativeGuards() {
    }

    public static List<Spec> all() {
        return specs;
    }

    /** 重新读目录里的 json（界面点「同步加载」时调） */
    public static int reload() {
        Path dir = AdapterDir.folder();
        List<Spec> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : ds) {
                Spec spec = parse(f);
                if (spec != null) out.add(spec);
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 读取声明式适配器失败", t);
        }
        specs = out;
        loadedFrom = dir;
        LOGGED.clear();
        if (!out.isEmpty()) {
            FolioCleaner.LOGGER.info("[Folio Cleaner] 声明式适配器：{} 条（目录 {}）", out.size(), dir);
        }
        return out.size();
    }

    /**
     * 每 tick 由 {@code ContainerMenuMixin} 调。只看「声明过」的菜单，没声明直接返回。
     *
     * <p>★重入保护：命中后我们为了立刻同步客户端会调 {@code menu.broadcastChanges()}，
     * 那会**再次回到本方法**（同一个注入点）。没有这道闸门时：
     * <ul>
     *   <li>json 适配器：嵌套一层，白跑一遍</li>
     *   <li>lua 适配器：脚本如果无脑 {@code return true}，就会**无限递归 → StackOverflowError 崩服**</li>
     * </ul>
     */
    public static void check(AbstractContainerMenu menu) {
        if (menu == null || checking) return;
        checking = true;
        try {
            check0(menu);
        } finally {
            checking = false;
        }
    }

    private static void check0(AbstractContainerMenu menu) {
        // Lua 适配器和 json 共用一个注入点
        try {
            LuaGuards.check(menu);
        } catch (Throwable ignored) {
        }
        if (specs.isEmpty()) return;
        String className = menu.getClass().getName();
        String simple = menu.getClass().getSimpleName();
        for (Spec spec : specs) {
            try {
                if (!matches(spec, className, simple)) continue;
                if (ClientSettings.disabledGuards.contains(spec.id)) continue;
                step(spec, menu, className);
            } catch (Throwable t) {
                FolioCleaner.LOGGER.debug("[Folio Cleaner] 声明式适配器 {} 执行失败", spec.id, t);
            }
        }
    }

    private static boolean matches(Spec spec, String className, String simple) {
        if (spec.menu == null || spec.menu.isBlank()) return false;
        return spec.menu.equals(className) || spec.menu.equals(simple);
    }

    private static void step(Spec spec, AbstractContainerMenu menu, String className) {
        // 1) 结果槽里没东西 → 不用管
        boolean hasResult = false;
        for (int idx : spec.result) {
            if (idx < 0 || idx >= menu.slots.size()) continue;
            if (!menu.getSlot(idx).getItem().isEmpty()) {
                hasResult = true;
                break;
            }
        }
        if (!hasResult) return;

        // 2) 用输入槽拼一个只读容器，交给现有的规则判断（复用 CraftGuards 那套 remove+item 逻辑）
        Container view = new MenuView(menu, spec.inputs);
        if (!CraftGuards.isBlocked(view)) return;

        // 3) 命中：记日志；非演练模式才真的清
        String key = className + "#" + spec.id;
        if (LOGGED.size() > 512) LOGGED.clear();    // 兜底：别让这个集合无限长
        if (LOGGED.add(key)) {
            FolioCleaner.LOGGER.info("[Folio Cleaner] 声明式适配器「{}」命中：{}（结果槽 {}，输入槽 {}）",
                    spec.name, className, java.util.Arrays.toString(spec.result),
                    java.util.Arrays.toString(spec.inputs));
        }
        if (ClientSettings.adapterDryRun) return;

        for (int idx : spec.result) {
            if (idx < 0 || idx >= menu.slots.size()) continue;
            Slot slot = menu.getSlot(idx);
            if (!slot.getItem().isEmpty()) slot.set(ItemStack.EMPTY);
        }
        menu.broadcastChanges();
    }

    // ---------------------------------------------------------------- 解析

    private static Spec parse(Path file) {
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonElement root = JsonParser.parseString(text);
            if (!root.isJsonObject()) return null;
            JsonObject obj = root.getAsJsonObject();

            String id = str(obj, "id", stem(file));
            String name = str(obj, "name", id);
            String comment = str(obj, "comment", "");
            String menu = null;
            String block = null;
            if (obj.has("scope") && obj.get("scope").isJsonObject()) {
                JsonObject scope = obj.getAsJsonObject("scope");
                menu = str(scope, "menu", null);
                block = str(scope, "block", null);
            }
            int[] result = slots(obj, "result");
            int[] inputs = slots(obj, "inputs");
            if (menu == null && block == null) {
                FolioCleaner.LOGGER.warn("[Folio Cleaner] 适配器 {} 缺少 scope.menu / scope.block，已跳过", id);
                return null;
            }
            if (result.length == 0) {
                FolioCleaner.LOGGER.warn("[Folio Cleaner] 适配器 {} 缺少 probe.result，已跳过", id);
                return null;
            }
            return new Spec(id, name, menu, block, result, inputs, comment);
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 适配器 json 解析失败：{}", file, t);
            return null;
        }
    }

    private static int[] slots(JsonObject obj, String key) {
        if (!obj.has("probe") || !obj.get("probe").isJsonObject()) return new int[0];
        JsonObject probe = obj.getAsJsonObject("probe");
        if (!probe.has(key)) return new int[0];
        JsonElement el = probe.get(key);
        List<Integer> list = new ArrayList<>();
        try {
            if (el.isJsonArray()) {
                JsonArray arr = el.getAsJsonArray();
                for (JsonElement e : arr) {
                    if (e.isJsonPrimitive()) list.add(e.getAsInt());
                }
            } else if (el.isJsonPrimitive()) {
                list.add(el.getAsInt());
            }
        } catch (Throwable ignored) {
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
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

    /** 把菜单里指定的槽位当成一个只读 Container 看 */
    private static final class MenuView implements Container {
        private final AbstractContainerMenu menu;
        private final int[] indexes;

        MenuView(AbstractContainerMenu menu, int[] indexes) {
            this.menu = menu;
            this.indexes = indexes;
        }

        @Override
        public int getContainerSize() {
            return indexes.length;
        }

        @Override
        public boolean isEmpty() {
            for (int i : indexes) {
                if (i >= 0 && i < menu.slots.size() && !menu.getSlot(i).getItem().isEmpty()) return false;
            }
            return true;
        }

        @Override
        public ItemStack getItem(int slot) {
            if (slot < 0 || slot >= indexes.length) return ItemStack.EMPTY;
            int idx = indexes[slot];
            return idx >= 0 && idx < menu.slots.size() ? menu.getSlot(idx).getItem() : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItem(int slot, int amount) {
            return ItemStack.EMPTY;   // 只读
        }

        @Override
        public ItemStack removeItemNoUpdate(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void setItem(int slot, ItemStack stack) {
            // 只读
        }

        @Override
        public void setChanged() {
            // 只读
        }

        @Override
        public boolean stillValid(net.minecraft.world.entity.player.Player player) {
            return true;
        }

        @Override
        public void clearContent() {
            // 只读
        }
    }
}
