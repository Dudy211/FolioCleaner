package com.yuneko.foliocleaner;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundUpdateRecipesPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 在服务端真正把配方从 {@link RecipeManager} 里摘掉。
 *
 * <p>这是「删除」模式的实现：配方不存在 → 同步给客户端的配方包里也没有 →
 * 所有玩家都看不到、也合成不了，客户端装不装模组都一样。
 *
 * <p>服务端没有 JEI，所以这里用「配方类型 id 字符串」做匹配，不依赖 JEI 的 RecipeType。
 *
 * <p><b>1.20.1 的实现注意：</b>{@code RecipeManager} 的 {@code byName}（f_199900_）和
 * {@code recipes}（f_44007_）两个字段在运行时都是 Guava {@code ImmutableMap}
 * （构造时 {@code ImmutableMap.of()}，数据重载时 {@code ImmutableMap.copyOf / builder.build()}），
 * 直接 {@code keySet().removeAll()} 会抛 {@code UnsupportedOperationException}。
 * 所以这里是把字段整体替换成可变副本，并保留原始引用以便还原。
 */
public final class RecipeRemover {

    /** 原始字段（ImmutableMap），还原时原样放回 */
    private static Map<ResourceLocation, Recipe<?>> originalByName;
    private static Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> originalByType;

    /** 我们自己装进去的可变副本：用来判断字段是否已被 /reload 换成新数据 */
    private static Map<ResourceLocation, Recipe<?>> installedByName;
    private static Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> installedByType;

    private static boolean applied;

    private RecipeRemover() {
    }

    public static boolean applied() {
        return applied;
    }

    public static synchronized void apply(MinecraftServer server) {
        if (server == null) return;
        try {
            RecipeManager manager = server.getRecipeManager();

            // 先还原上一次的改动，避免规则变了以后旧配方回不来
            restore(server);

            List<Rule> rules = Rules.serverRules();
            if (rules.isEmpty()) return;

            Set<ResourceLocation> targets = collectTargets(server, rules);
            if (targets.isEmpty()) return;

            Field byNameField = ObfuscationReflectionHelper.findField(RecipeManager.class, "f_199900_");
            Field byTypeField = ObfuscationReflectionHelper.findField(RecipeManager.class, "f_44007_");
            byNameField.setAccessible(true);
            byTypeField.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<ResourceLocation, Recipe<?>> byName =
                    (Map<ResourceLocation, Recipe<?>>) byNameField.get(manager);
            if (byName == null) return;

            @SuppressWarnings("unchecked")
            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> byType =
                    (Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>>) byTypeField.get(manager);

            // 先全部构造好再写字段：任何一步失败都不会留下半改状态
            Map<ResourceLocation, Recipe<?>> newByName = new LinkedHashMap<>(byName);
            newByName.keySet().removeAll(targets);

            Map<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> newByType = null;
            if (byType != null) {
                newByType = new LinkedHashMap<>();
                for (Map.Entry<RecipeType<?>, Map<ResourceLocation, Recipe<?>>> entry : byType.entrySet()) {
                    Map<ResourceLocation, Recipe<?>> inner = new LinkedHashMap<>(entry.getValue());
                    inner.keySet().removeAll(targets);
                    newByType.put(entry.getKey(), inner);
                }
            }

            originalByName = byName;
            originalByType = byType;
            installedByName = newByName;
            installedByType = newByType;

            byNameField.set(manager, newByName);
            if (newByType != null) {
                byTypeField.set(manager, newByType);
            }

            applied = true;
            // 按命名空间统计：方便确认某个模组的配方到底有没有被删掉
            Map<String, Integer> byMod = new java.util.TreeMap<>();
            for (ResourceLocation id : targets) {
                byMod.merge(id.getNamespace(), 1, Integer::sum);
            }
            StringBuilder detail = new StringBuilder();
            for (Map.Entry<String, Integer> e : byMod.entrySet()) {
                if (detail.length() > 0) detail.append(" / ");
                detail.append(e.getKey()).append(' ').append(e.getValue());
            }
            FolioCleaner.LOGGER.info("[Folio Cleaner] 服务端已删除 {} 条配方（规则 {} 条）：{}",
                    targets.size(), rules.size(), detail);

            // 在线玩家手里还是旧配方表，重发一次
            resync(server);
        } catch (Throwable t) {
            applied = false;
            originalByName = null;
            originalByType = null;
            installedByName = null;
            installedByType = null;
            FolioCleaner.LOGGER.error("[Folio Cleaner] 服务端删除配方失败（只影响删除模式，隐藏仍然可用）", t);
        }
    }

    public static synchronized void restore(MinecraftServer server) {
        if (!applied || server == null) return;
        boolean changed = false;
        try {
            RecipeManager manager = server.getRecipeManager();
            Field byNameField = ObfuscationReflectionHelper.findField(RecipeManager.class, "f_199900_");
            Field byTypeField = ObfuscationReflectionHelper.findField(RecipeManager.class, "f_44007_");
            byNameField.setAccessible(true);
            byTypeField.setAccessible(true);

            // 只有当字段仍然是我们装进去的副本时才还原。
            // 如果已经被 /reload 换成新数据（配方已从文件重新加载），就不要再写旧数据回去。
            if (originalByName != null && byNameField.get(manager) == installedByName) {
                byNameField.set(manager, originalByName);
                changed = true;
            }
            if (originalByType != null && byTypeField.get(manager) == installedByType) {
                byTypeField.set(manager, originalByType);
                changed = true;
            }
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 还原服务端配方失败", t);
        } finally {
            originalByName = null;
            originalByType = null;
            installedByName = null;
            installedByType = null;
            applied = false;
        }

        if (changed) {
            // 配方回来了，也要让在线玩家看到
            resync(server);
        }
    }

    /** 把当前配方表重发给所有在线玩家（删除 / 还原之后） */
    private static void resync(MinecraftServer server) {
        try {
            if (server == null || !server.isRunning()) return;
            List<ServerPlayer> players = server.getPlayerList().getPlayers();
            if (players.isEmpty()) return;

            Collection<Recipe<?>> recipes = server.getRecipeManager().getRecipes();
            ClientboundUpdateRecipesPacket packet = new ClientboundUpdateRecipesPacket(recipes);
            for (ServerPlayer player : players) {
                player.connection.send(packet);
            }
            FolioCleaner.LOGGER.info("[Folio Cleaner] 已向 {} 名玩家重发配方表（{} 条）", players.size(), recipes.size());
        } catch (Throwable t) {
            FolioCleaner.LOGGER.warn("[Folio Cleaner] 重发配方表失败（玩家重连后会自然同步）", t);
        }
    }

    private static Set<ResourceLocation> collectTargets(MinecraftServer server, List<Rule> rules) {
        Set<ResourceLocation> targets = new LinkedHashSet<>();
        RecipeManager manager = server.getRecipeManager();
        RegistryAccess registryAccess = server.registryAccess();

        for (Recipe<?> recipe : manager.getRecipes()) {
            try {
                if (matches(recipe, rules, registryAccess)) {
                    targets.add(recipe.getId());
                }
            } catch (Throwable ignored) {
            }
        }
        return targets;
    }

    public static boolean matches(Recipe<?> recipe, List<Rule> rules, RegistryAccess registryAccess) {
        return matches(recipe, rules, registryAccess, false);
    }

    /**
     * @param includeHide true 时把「隐藏」规则也算命中。
     *                    客户端统计「还有哪些配方看得见」时要传 true —— 否则用户藏了东西，
     *                    排行榜的数字却纹丝不动，看着像没生效。
     */
    public static boolean matches(Recipe<?> recipe, List<Rule> rules, RegistryAccess registryAccess,
                                  boolean includeHide) {
        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        String namespace = recipe.getId().getNamespace();
        ItemStack result = safeResult(recipe, registryAccess);

        for (Rule rule : rules) {
            if (rule == null || !rule.enabled) continue;
            // 服务端只处理「删除」规则；「隐藏」交给客户端 JEI
            if (!Rule.ACTION_REMOVE.equals(rule.action)
                    && !(includeHide && Rule.ACTION_HIDE.equals(rule.action))) continue;

            if (Rule.TYPE_RECIPE.equals(rule.type)) {
                if (recipe.getId().toString().equals(rule.target)) return true;
                continue;
            }
            if (Rule.TYPE_KEEP.equals(rule.type)) {
                // 白名单在客户端隐藏层处理，服务端不做删除
                continue;
            }
            if (Rule.TYPE_CATEGORY.equals(rule.type)) {
                if (typeId != null && typeId.toString().equals(rule.target)) return true;
                continue;
            }

            // 多组范围：任意一组命中即可（服务端只能按类型 id 字符串比）
            for (Rule.Range range : rule.effectiveRanges()) {
                if (!typeMatches(typeId, range.type)) continue;

                if (Rule.TYPE_MOD.equals(rule.type)) {
                    if (namespace.equals(rule.target) && partnerAllows(recipe, range.groups())) {
                        return true;
                    }
                    continue;
                }

                if (Rule.TYPE_ITEM.equals(rule.type)) {
                    ResourceLocation itemId = ResourceLocation.tryParse(rule.target);
                    if (itemId == null) continue;
                    Item item = BuiltInRegistries.ITEM.get(itemId);
                    // Registry.get() 对未知 id 返回 AIR 而不是 null
                    if (item == null || item == Items.AIR) continue;
                    if (rule.hideOutput && result != null && !result.isEmpty() && result.getItem() == item) {
                        return true;
                    }
                    if (rule.hideIngredient && matchesIngredient(recipe, item, range.groups())) return true;
                }
            }
        }
        return false;
    }

    /** typeFilter 可能是逗号分隔的多个类型 id；空 = 不限 */
    private static boolean typeMatches(ResourceLocation typeId, String typeFilter) {
        if (typeFilter == null || typeFilter.isBlank()) return true;
        String actual = typeId == null ? "" : typeId.toString();
        for (String part : typeFilter.split(",")) {
            if (part.trim().equals(actual)) return true;
        }
        return false;
    }

    /** 搭配分组：外层「或」，内层「与」；空组 = 不限 */
    private static boolean partnerAllows(Recipe<?> recipe, List<List<String>> groups) {
        if (groups == null || groups.isEmpty()) return true;
        for (List<String> group : groups) {
            if (group.isEmpty()) return true;
            boolean all = true;
            for (String partner : group) {
                boolean found = false;
                for (Ingredient ingredient : recipe.getIngredients()) {
                    if (ingredient == null || ingredient.isEmpty()) continue;
                    if (ingredientAccepts(ingredient, partner)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    /**
     * 配方是否把 item 当原料；partner 非空时还要求另一个格子接受它
     * （与服务端无关的 JEI 类型，这里不引用 Selector，避免服务端加载 JEI 类）。
     */
    private static boolean matchesIngredient(Recipe<?> recipe, Item item, List<List<String>> groups) {
        ItemStack probe = new ItemStack(item);
        List<Ingredient> ingredients = recipe.getIngredients();

        int itemSlot = -1;
        for (int i = 0; i < ingredients.size(); i++) {
            Ingredient ingredient = ingredients.get(i);
            if (ingredient == null || ingredient.isEmpty()) continue;
            try {
                if (ingredient.test(probe)) {
                    itemSlot = i;
                    break;
                }
            } catch (Throwable ignored) {
            }
        }
        if (itemSlot < 0) return false;
        if (groups == null || groups.isEmpty()) return true;

        for (List<String> group : groups) {
            if (group.isEmpty()) return true;
            boolean all = true;
            for (String partner : group) {
                boolean found = false;
                for (int i = 0; i < ingredients.size(); i++) {
                    if (i == itemSlot) continue;
                    Ingredient ingredient = ingredients.get(i);
                    if (ingredient == null || ingredient.isEmpty()) continue;
                    if (ingredientAccepts(ingredient, partner)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        return false;
    }

    private static boolean ingredientAccepts(Ingredient ingredient, String partner) {
        try {
            if (partner.startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(partner.substring(1));
                if (id == null) return false;
                TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
                for (ItemStack stack : ingredient.getItems()) {
                    if (!stack.isEmpty() && stack.is(tag)) return true;
                }
                return false;
            }
            ResourceLocation id = ResourceLocation.tryParse(partner);
            if (id == null) return false;
            Item partnerItem = BuiltInRegistries.ITEM.get(id);
            if (partnerItem == null || partnerItem == Items.AIR) return false;
            return ingredient.test(new ItemStack(partnerItem));
        } catch (Throwable t) {
            return false;
        }
    }

    private static ItemStack safeResult(Recipe<?> recipe, RegistryAccess registryAccess) {
        try {
            ItemStack stack = recipe.getResultItem(registryAccess);
            return stack == null ? ItemStack.EMPTY : stack;
        } catch (Throwable t) {
            return ItemStack.EMPTY;
        }
    }
}
