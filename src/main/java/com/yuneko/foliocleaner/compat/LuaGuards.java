package com.yuneko.foliocleaner.compat;

import com.yuneko.foliocleaner.ClientSettings;
import com.yuneko.foliocleaner.FolioCleaner;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * **Lua 适配器**：给"比 json 复杂、又不想写 Java"的场景。
 *
 * <p>脚本契约（{@code config/foliocleaner/adapters/*.lua}）：
 * <pre>
 * result = {2}                       -- 命中时要清空的结果槽（必填）
 *
 * function blocks(ctx)               -- 返回 true = 这次合成无效（必填）
 *   return ctx:slot(0) == "minecraft:iron_ingot"
 *      and ctx:slot(1) == "minecraft:diamond"
 * end
 * </pre>
 *
 * <p>{@code ctx} 只暴露**只读**的东西：
 * <ul>
 *   <li>{@code ctx:slot(i)} → 物品 id 字符串（空格返回 nil）</li>
 *   <li>{@code ctx:count(i)} → 数量</li>
 *   <li>{@code ctx:size()} → 菜单槽位总数</li>
 *   <li>{@code ctx:menu()} → 菜单类简名</li>
 *   <li>{@code ctx:dryrun()} → 当前是不是演练模式</li>
 * </ul>
 *
 * <p><b>沙箱</b>：删掉 {@code luajava / os / io / package / require / dofile / loadfile}，
 * 脚本拿不到文件系统、网络和反射。
 *
 * <p><b>预算</b>：每次调用计时，超过 {@link #BUDGET_MS} 毫秒就把这个脚本**停用**并 warn ——
 * Lua 线程没法安全中断，只能"事后熔断"，这是可接受的最坏情况（最多卡一次 20ms）。
 */
public final class LuaGuards {

    private static final long BUDGET_MS = 20;

    /** 一个已编译的脚本 */
    private static final class Script {
        final String id;
        final String name;
        final Globals globals;
        final LuaValue blocks;
        final int[] result;
        boolean broken;

        Script(String id, String name, Globals globals, LuaValue blocks, int[] result) {
            this.id = id;
            this.name = name;
            this.globals = globals;
            this.blocks = blocks;
            this.result = result;
        }
    }

    private static List<Script> scripts = List.of();

    private LuaGuards() {
    }

    public static int reload() {
        Path dir = AdapterDir.folder();
        List<Script> out = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.lua")) {
            for (Path f : ds) {
                Script s = load(f);
                if (s != null) out.add(s);
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 读取 Lua 适配器失败", t);
        }
        scripts = out;
        if (!out.isEmpty()) {
            FolioCleaner.LOGGER.info("[Folio Cleaner] Lua 适配器：{} 个", out.size());
        }
        return out.size();
    }

    public static List<String> names() {
        List<String> out = new ArrayList<>();
        for (Script s : scripts) out.add(s.name);
        return out;
    }

    /** 由 {@code DeclarativeGuards.check} 一起调用（同一个注入点） */
    public static void check(AbstractContainerMenu menu) {
        if (scripts.isEmpty() || menu == null) return;
        for (Script script : scripts) {
            if (script.broken) continue;
            if (ClientSettings.disabledGuards.contains(script.id)) continue;
            long t0 = System.nanoTime();
            boolean hit;
            try {
                hit = script.blocks.call(ctx(menu)).toboolean();
            } catch (LuaError e) {
                script.broken = true;
                FolioCleaner.LOGGER.warn("[Folio Cleaner] Lua 适配器「{}」出错，已停用：{}",
                        script.name, e.getMessage());
                continue;
            } catch (Throwable t) {
                script.broken = true;
                FolioCleaner.LOGGER.warn("[Folio Cleaner] Lua 适配器「{}」异常，已停用", script.name, t);
                continue;
            } finally {
                long ms = (System.nanoTime() - t0) / 1_000_000L;
                if (ms > BUDGET_MS && !script.broken) {
                    script.broken = true;
                    FolioCleaner.LOGGER.warn("[Folio Cleaner] Lua 适配器「{}」耗时 {}ms，超过预算 {}ms，已停用",
                            script.name, ms, BUDGET_MS);
                }
            }
            if (!hit) continue;
            if (ClientSettings.adapterDryRun) continue;
            for (int idx : script.result) {
                if (idx < 0 || idx >= menu.slots.size()) continue;
                Slot slot = menu.getSlot(idx);
                if (!slot.getItem().isEmpty()) slot.set(net.minecraft.world.item.ItemStack.EMPTY);
            }
            menu.broadcastChanges();
            return;
        }
    }

    // ---------------------------------------------------------------- 加载

    private static Script load(Path file) {
        String id = stem(file);
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Globals globals = JsePlatform.standardGlobals();
            sandbox(globals);
            globals.load(reader, id).call();          // 执行脚本体（定义 result / blocks）

            LuaValue fn = globals.get("blocks");
            if (!fn.isfunction()) {
                FolioCleaner.LOGGER.warn("[Folio Cleaner] Lua 适配器 {} 没有定义 blocks(ctx)，已跳过", id);
                return null;
            }
            int[] result = readResult(globals.get("result"));
            if (result.length == 0) {
                FolioCleaner.LOGGER.warn("[Folio Cleaner] Lua 适配器 {} 没有定义 result = {{...}}，已跳过", id);
                return null;
            }
            String name = globals.get("name").isstring() ? globals.get("name").tojstring() : id;
            return new Script(id, name, globals, fn, result);
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] Lua 适配器 {} 加载失败", file, t);
            return null;
        }
    }

    private static void sandbox(LuaValue globals) {
        for (String forbidden : new String[]{"luajava", "os", "io", "package", "require",
                "dofile", "loadfile", "load", "debug"}) {
            try {
                globals.set(forbidden, LuaValue.NIL);
            } catch (Throwable ignored) {
            }
        }
    }

    private static int[] readResult(LuaValue value) {
        if (value == null || !value.istable()) return new int[0];
        List<Integer> list = new ArrayList<>();
        for (int i = 1; ; i++) {
            LuaValue v = value.get(i);
            if (v.isnil()) break;
            list.add(v.toint());
            if (list.size() > 64) break;
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    /** 只读上下文传给脚本 */
    private static LuaValue ctx(AbstractContainerMenu menu) {
        LuaTable t = new LuaTable();
        t.set("slot", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                int i = arg.checkint();
                if (i < 0 || i >= menu.slots.size()) return LuaValue.NIL;
                var stack = menu.getSlot(i).getItem();
                if (stack.isEmpty()) return LuaValue.NIL;
                return LuaValue.valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
        });
        t.set("count", new OneArgFunction() {
            @Override
            public LuaValue call(LuaValue arg) {
                int i = arg.checkint();
                if (i < 0 || i >= menu.slots.size()) return LuaValue.ZERO;
                return LuaValue.valueOf(menu.getSlot(i).getItem().getCount());
            }
        });
        t.set("size", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(menu.slots.size());
            }
        });
        t.set("menu", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(menu.getClass().getSimpleName());
            }
        });
        t.set("dryrun", new ZeroArgFunction() {
            @Override
            public LuaValue call() {
                return LuaValue.valueOf(ClientSettings.adapterDryRun);
            }
        });
        return t;
    }

    private static String stem(Path file) {
        String n = file.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
