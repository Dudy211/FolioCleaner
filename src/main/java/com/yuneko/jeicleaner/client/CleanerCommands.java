package com.yuneko.jeicleaner.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.yuneko.jeicleaner.Cleaner;
import com.yuneko.jeicleaner.JeiCleaner;
import com.yuneko.jeicleaner.Rule;
import com.yuneko.jeicleaner.Rules;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * 客户端指令。
 *
 * <pre>
 * /jeicleaner                                    打开图形界面
 * /jeicleaner add item &lt;物品id&gt; [配方类型]        隐藏该物品参与的所有配方
 * /jeicleaner add itemonly &lt;物品id&gt;              只在 JEI 物品列表里隐藏这个物品
 * /jeicleaner add mod &lt;modid&gt; [配方类型]          隐藏某个模组的全部配方
 * /jeicleaner add recipe &lt;配方id&gt;                隐藏某一条配方
 * /jeicleaner list                               列出当前规则
 * /jeicleaner remove &lt;序号&gt;                      删除一条规则
 * /jeicleaner toggle &lt;序号&gt;                      启用/停用一条规则
 * /jeicleaner export                             把规则 JSON 复制到剪贴板
 * /jeicleaner clear                              清空全部规则
 * /jeicleaner reload                             重新读取 config/jeicleaner.json
 * /jeicleaner apply                              立即重新应用
 * </pre>
 *
 * <p>配方类型例如 {@code minecraft:crafting}，省略表示不限。
 */
public final class CleanerCommands {

    private static final SuggestionProvider<CommandSourceStack> ITEM_SUGGESTIONS = (ctx, builder) -> {
        List<String> ids = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) ids.add(id.toString());
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> RECIPE_SUGGESTIONS = (ctx, builder) -> {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return builder.buildFuture();
        List<String> ids = new ArrayList<>();
        mc.level.getRecipeManager().getRecipes().forEach(r -> ids.add(r.getId().toString()));
        return SharedSuggestionProvider.suggest(ids, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> MOD_SUGGESTIONS = (ctx, builder) -> {
        List<String> mods = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if (!mods.contains(id.getNamespace())) mods.add(id.getNamespace());
        }
        mods.sort(String::compareTo);
        return SharedSuggestionProvider.suggest(mods, builder);
    };

    private static final SuggestionProvider<CommandSourceStack> TYPE_SUGGESTIONS = (ctx, builder) -> {
        List<String> types = new ArrayList<>();
        for (ResourceLocation id : BuiltInRegistries.RECIPE_TYPE.keySet()) types.add(id.toString());
        types.sort(String::compareTo);
        return SharedSuggestionProvider.suggest(types, builder);
    };

    private CleanerCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        RequiredArgumentBuilder<CommandSourceStack, ResourceLocation> itemId =
                Commands.argument("id", ResourceLocationArgument.id()).suggests(ITEM_SUGGESTIONS);

        LiteralCommandNode<CommandSourceStack> root = dispatcher.register(
                Commands.literal("jeicleaner")
                        .executes(ctx -> openGui())
                        .then(Commands.literal("gui").executes(ctx -> openGui()))
                        .then(Commands.literal("add")
                                .then(Commands.literal("item")
                                        .then(itemId
                                                .executes(ctx -> addItem(ResourceLocationArgument.getId(ctx, "id"),
                                                        "", true, true, false))
                                                .then(Commands.argument("type", ResourceLocationArgument.id())
                                                        .suggests(TYPE_SUGGESTIONS)
                                                        .executes(ctx -> addItem(
                                                                ResourceLocationArgument.getId(ctx, "id"),
                                                                ResourceLocationArgument.getId(ctx, "type").toString(),
                                                                true, true, false)))))
                                .then(Commands.literal("itemonly")
                                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                                .suggests(ITEM_SUGGESTIONS)
                                                .executes(ctx -> addItem(ResourceLocationArgument.getId(ctx, "id"),
                                                        "", false, false, true))))
                                .then(Commands.literal("mod")
                                        .then(Commands.argument("modid", ModIdArgument.modId())
                                                .suggests(MOD_SUGGESTIONS)
                                                .executes(ctx -> addMod(ctx.getArgument("modid", String.class), ""))
                                                .then(Commands.argument("type", ResourceLocationArgument.id())
                                                        .suggests(TYPE_SUGGESTIONS)
                                                        .executes(ctx -> addMod(ctx.getArgument("modid", String.class),
                                                                ResourceLocationArgument.getId(ctx, "type").toString())))))
                                .then(Commands.literal("keep")
                                        .then(Commands.argument("modid", ModIdArgument.modId())
                                                .suggests(MOD_SUGGESTIONS)
                                                .executes(ctx -> addKeep(ctx.getArgument("modid", String.class)))))
                                .then(Commands.literal("type")
                                        .then(Commands.argument("type", ResourceLocationArgument.id())
                                                .suggests(TYPE_SUGGESTIONS)
                                                .executes(ctx -> addCategory(
                                                        ResourceLocationArgument.getId(ctx, "type")))))
                                .then(Commands.literal("recipe")
                                        .then(Commands.argument("id", ResourceLocationArgument.id())
                                                .suggests(RECIPE_SUGGESTIONS)
                                                .executes(ctx -> addRecipe(ResourceLocationArgument.getId(ctx, "id"))))))
                        .then(Commands.literal("list").executes(ctx -> list(ctx)))
                        .then(Commands.literal("diag")
                                .then(Commands.argument("id", ResourceLocationArgument.id())
                                        .suggests(ITEM_SUGGESTIONS)
                                        .executes(ctx -> diag(ResourceLocationArgument.getId(ctx, "id")))))
                        .then(Commands.literal("widen").executes(ctx -> widen()))
                        .then(Commands.literal("verify").executes(ctx -> verify()))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                        .executes(ctx -> remove(IntegerArgumentType.getInteger(ctx, "index")))))
                        .then(Commands.literal("toggle")
                                .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                        .executes(ctx -> toggle(IntegerArgumentType.getInteger(ctx, "index")))))
                        .then(Commands.literal("export").executes(ctx -> export()))
                        .then(Commands.literal("clear").executes(ctx -> clear()))
                        .then(Commands.literal("reload").executes(ctx -> reload()))
                        .then(Commands.literal("apply").executes(ctx -> apply()))
        );

        dispatcher.register(Commands.literal("jc").redirect(root));
    }

    private static int openGui() {
        Minecraft.getInstance().setScreen(new CleanerScreen());
        return Command.SINGLE_SUCCESS;
    }

    /** 指令改完不会自动生效（默认手动同步），统一在回执里提示一句 */
    private static final String APPLY_HINT = " §7（/jeicleaner apply 生效）";

    private static int addItem(ResourceLocation id, String type, boolean hideOutput, boolean hideIngredient,
                               boolean hideItem) {
        Rule rule = new Rule(Rule.TYPE_ITEM, id.toString());
        rule.hideOutput = hideOutput;
        rule.hideIngredient = hideIngredient;
        rule.hideItem = hideItem;
        rule.recipeType = type;
        Rules.add(rule);
        return feedback("已添加：" + rule.describe() + APPLY_HINT);
    }

    private static int addMod(String modid, String type) {
        Rule rule = new Rule(Rule.TYPE_MOD, modid);
        rule.recipeType = type;
        Rules.add(rule);
        return feedback("已添加：" + rule.describe() + APPLY_HINT);
    }

    private static int addRecipe(ResourceLocation id) {
        Rule rule = new Rule(Rule.TYPE_RECIPE, id.toString());
        Rules.add(rule);
        return feedback("已添加：" + rule.describe() + APPLY_HINT);
    }

    private static int addKeep(String modid) {
        Rule rule = new Rule(Rule.TYPE_KEEP, modid);
        Rules.add(rule);
        return feedback("已添加：" + rule.describe() + " §7（白名单模式下其余模组配方都会被隐藏）" + APPLY_HINT);
    }

    private static int addCategory(ResourceLocation type) {
        Rule rule = new Rule(Rule.TYPE_CATEGORY, type.toString());
        Rules.add(rule);
        return feedback("已添加：" + rule.describe() + APPLY_HINT);
    }

    private static int verify() {
        var report = Cleaner.report();
        if (report.isEmpty()) return feedback("还没有隐藏任何配方（或者还没进世界）。");
        var player = Minecraft.getInstance().player;
        if (player == null) return Command.SINGLE_SUCCESS;
        player.displayClientMessage(Component.literal("§6[JEI Cleaner] §f隐藏自检："), false);
        for (String line : report) {
            player.displayClientMessage(Component.literal(line), false);
        }
        player.displayClientMessage(Component.literal(
                "§7✔ = 该类型已经清空；§c✘ = 调用了隐藏但 JEI 里还剩配方（实例对不上）"), false);
        return Math.max(1, report.size());
    }

    private static int widen() {
        int n = Rules.clearTypeFilters();
        if (n == 0) return feedback("所有规则本来就不限工作台，无需修改。");
        return feedback("已把 " + n + " 条规则的「范围」改成不限类型，重新应用后在 JEI 里试按 U。");
    }

    private static int diag(ResourceLocation id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return feedback("需要先进入世界。");
        if (Cleaner.RUNTIME == null) return feedback("JEI 还没就绪，稍等一下再试。");

        net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.get(id);
        if (item == net.minecraft.world.item.Items.AIR) return feedback("找不到物品：" + id);

        var recipeManager = mc.level.getRecipeManager();
        var registryAccess = mc.level.registryAccess();
        var jei = Cleaner.RUNTIME.getRecipeManager();
        var selector = com.yuneko.jeicleaner.Selector.of(Rules.all(), jei);
        var probe = new net.minecraft.world.item.ItemStack(item);

        int total = 0, asIngredient = 0, asOutput = 0, resolved = 0, matched = 0;
        List<String> samples = new ArrayList<>();
        for (var recipe : recipeManager.getRecipes()) {
            total++;
            boolean uses = false;
            try {
                for (var ingredient : recipe.getIngredients()) {
                    if (ingredient != null && !ingredient.isEmpty() && ingredient.test(probe)) {
                        uses = true;
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            boolean produces = false;
            try {
                var result = recipe.getResultItem(registryAccess);
                produces = result != null && !result.isEmpty() && result.getItem() == item;
            } catch (Throwable ignored) {
            }
            if (!uses && !produces) continue;

            if (uses) asIngredient++;
            if (produces) asOutput++;

            var type = com.yuneko.jeicleaner.Selector.jeiTypeFor(jei, recipe);
            if (type != null) resolved++;

            boolean hit = false;
            try {
                hit = selector.matches(recipe, registryAccess);
            } catch (Throwable ignored) {
            }
            if (hit) matched++;

            if (samples.size() < 10) {
                samples.add("§8· §f" + recipe.getId() + " §7["
                        + (type == null ? "§c未解析" : type.getUid().toString()) + "§7]"
                        + (hit ? " §a命中" : " §c未命中"));
            }
        }

        var player = mc.player;
        if (player == null) return Command.SINGLE_SUCCESS;
        player.displayClientMessage(Component.literal("§6[JEI Cleaner] §f诊断 " + id), false);
        player.displayClientMessage(Component.literal("§7配方总数 " + total
                + " §8| §7作为原料 §f" + asIngredient + " §7作为产物 §f" + asOutput), false);
        player.displayClientMessage(Component.literal("§7JEI 类型可解析 §f" + resolved
                + " §8| §7当前规则命中 §f" + matched), false);
        for (String line : samples) {
            player.displayClientMessage(Component.literal(line), false);
        }
        if (matched == 0 && resolved > 0) {
            player.displayClientMessage(Component.literal(
                    "§e→ 有配方但规则没命中：检查「原料」开关是否开了、范围是否选对。"), false);
        } else if (resolved == 0 && asIngredient + asOutput > 0) {
            player.displayClientMessage(Component.literal(
                    "§c→ 这些配方在 JEI 里没有对应分类（可能是动态生成的配方）。"), false);
        }
        return Math.max(1, matched);
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {        if (Rules.size() == 0) {
            return feedback("当前没有任何隐藏规则。用 /jeicleaner 打开界面添加。");
        }
        CommandSourceStack source = ctx.getSource();
        source.sendSuccess(() -> Component.literal("§6[JEI Cleaner] §f共 " + Rules.size() + " 条规则："), false);
        List<Rule> all = Rules.all();
        for (int i = 0; i < all.size(); i++) {
            String line = "§7" + (i + 1) + ". §f" + all.get(i).describe();
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return Math.max(1, all.size());
    }

    private static int remove(int index) {
        Rule rule = Rules.get(index - 1);
        if (rule == null) return feedback("序号 " + index + " 不存在。");
        Rules.remove(index - 1);
        return feedback("已删除：" + rule.describe() + APPLY_HINT);
    }

    private static int toggle(int index) {
        Rule rule = Rules.get(index - 1);
        if (rule == null) return feedback("序号 " + index + " 不存在。");
        Rules.toggleEnabled(index - 1);
        return feedback((rule.enabled ? "已启用：" : "已停用：") + rule.target + APPLY_HINT);
    }

    private static int export() {
        String json = Rules.exportJson();
        Minecraft.getInstance().keyboardHandler.setClipboard(json);
        return feedback("规则 JSON 已复制到剪贴板（" + json.length() + " 字符）。");
    }

    private static int clear() {
        int n = Rules.size();
        Rules.clear();
        return feedback("已清空 " + n + " 条规则。" + APPLY_HINT);
    }

    private static int reload() {
        Rules.load();
        return feedback("已重新读取配置，共 " + Rules.size() + " 条规则。");
    }

    private static int apply() {
        int hidden = Cleaner.applyNow();
        if (hidden < 0) {
            return feedback("现在还不能应用（需要先进入世界，且 JEI 已加载）。");
        }
        return feedback("已应用规则：隐藏 " + hidden + " 条配方、" + Cleaner.lastHiddenItems() + " 个物品，用时 "
                + Cleaner.lastApplyMs() + "ms。");
    }

    private static int feedback(String message) {
        var player = Minecraft.getInstance().player;
        Component component = Component.literal("§6[JEI Cleaner] §f" + message);
        if (player != null) {
            player.displayClientMessage(component, false);
        } else {
            JeiCleaner.LOGGER.info(message);
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * modid 允许 {@code -} 和 {@code .}，Brigadier 自带的 word() 不够用，这里自己读。
     */
    public static final class ModIdArgument implements ArgumentType<String> {

        public static ModIdArgument modId() {
            return new ModIdArgument();
        }

        @Override
        public String parse(com.mojang.brigadier.StringReader reader) throws CommandSyntaxException {
            String value = reader.readUnquotedString();
            if (value.isEmpty()) {
                throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownArgument()
                        .createWithContext(reader);
            }
            return value;
        }
    }
}
