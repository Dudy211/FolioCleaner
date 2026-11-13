package com.yuneko.foliocleaner.viewer;

import com.yuneko.foliocleaner.Cleaner;
import mezz.jei.api.gui.IRecipeLayoutDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.IFocus;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * JEI 后端：把原本散在 {@code Cleaner} / {@code CleanerScreen} / {@code CategoryStats}
 * 里的 JEI 调用收拢到这里。
 *
 * <p>行为与 2.0.0-test56 完全一致（这一步是纯搬移，不改逻辑）。
 */
public final class JeiBackend implements RecipeViewerBackend {

    private final Map<String, Preview> previewCache = new java.util.HashMap<>();

    @Override
    public String id() {
        return "jei";
    }

    @Override
    public String label() {
        return "JEI";
    }

    @Override
    public boolean ready() {
        return Cleaner.RUNTIME != null;
    }

    @Override
    public Caps caps() {
        return Caps.FULL;
    }

    // ------------------------------------------------------------ 浏览

    @Override
    public List<Category> categories() {
        IJeiRuntime runtime = Cleaner.RUNTIME;
        List<Category> out = new ArrayList<>();
        if (runtime == null) return out;

        IRecipeManager manager = runtime.getRecipeManager();
        try {
            manager.createRecipeCategoryLookup().includeHidden().get().forEach(category -> {
                try {
                    RecipeType<?> type = category.getRecipeType();
                    long count = manager.createRecipeLookup(type).includeHidden().get().count();
                    if (count <= 0) return;

                    ItemStack icon = ItemStack.EMPTY;
                    try {
                        icon = manager.createRecipeCatalystLookup(type).getItemStack()
                                .filter(s -> s != null && !s.isEmpty())
                                .findFirst().orElse(ItemStack.EMPTY);
                    } catch (Throwable ignored) {
                    }
                    out.add(new Category(type.getUid().toString(), category.getTitle(), icon, count));
                } catch (Throwable ignored) {
                }
            });
        } catch (Throwable ignored) {
        }

        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }

    @Override
    public List<Catalyst> catalysts() {
        IJeiRuntime runtime = Cleaner.RUNTIME;
        if (runtime == null) return List.of();

        Map<Item, LinkedHashSet<String>> map = new LinkedHashMap<>();
        try {
            IRecipeManager manager = runtime.getRecipeManager();
            List<IRecipeCategory<?>> categories = manager.createRecipeCategoryLookup().get().toList();
            for (IRecipeCategory<?> category : categories) {
                RecipeType<?> type = category.getRecipeType();
                try {
                    manager.createRecipeCatalystLookup(type).getItemStack().forEach(stack -> {
                        if (stack == null || stack.isEmpty()) return;
                        map.computeIfAbsent(stack.getItem(), k -> new LinkedHashSet<>())
                                .add(type.getUid().toString());
                    });
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            return List.of();
        }

        List<Catalyst> list = new ArrayList<>();
        for (Map.Entry<Item, LinkedHashSet<String>> e : map.entrySet()) {
            ItemStack stack = new ItemStack(e.getKey());
            list.add(new Catalyst(stack, stack.getHoverName().getString(), String.join(",", e.getValue())));
        }
        list.sort(Comparator.comparing(Catalyst::name));
        return list;
    }

    @Override
    public Preview preview(String recipeId) {
        IJeiRuntime runtime = Cleaner.RUNTIME;
        if (runtime == null || Minecraft.getInstance().level == null) return null;

        Preview cached = previewCache.get(recipeId);
        if (cached != null) return cached;

        Preview built = buildPreview(runtime, recipeId);
        if (built == null) return null;
        if (previewCache.size() > 24) previewCache.clear();
        previewCache.put(recipeId, built);
        return built;
    }

    /** 配方表变了要作废（对应原来 CleanerScreen.previewCache.clear()） */
    @Override
    public void invalidate() {
        previewCache.clear();
    }

    private Preview buildPreview(IJeiRuntime runtime, String entryId) {
        try {
            ResourceLocation id = ResourceLocation.tryParse(entryId);
            var level = Minecraft.getInstance().level;
            if (id == null || level == null) return null;

            Recipe<?> recipe = level.getRecipeManager().byKey(id).orElse(null);
            if (recipe == null) return null;

            ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
            if (typeId == null) return null;

            IRecipeManager jeiRecipes = runtime.getRecipeManager();
            var typeOpt = jeiRecipes.getRecipeType(typeId);
            if (typeOpt.isEmpty()) return null;

            IRecipeLayoutDrawable<?> drawable = createDrawable(jeiRecipes, typeOpt.get(), recipe);
            if (drawable == null) return null;

            IRecipeCategory<?> category = drawable.getRecipeCategory();
            int w = category.getWidth();
            int h = category.getHeight();
            if (w <= 0 || h <= 0) return null;

            return new DrawablePreview(drawable, w, h);
        } catch (Throwable t) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static IRecipeLayoutDrawable<?> createDrawable(IRecipeManager jeiRecipes,
                                                          RecipeType<?> type,
                                                          Recipe<?> recipe) {
        IRecipeCategory category = jeiRecipes.getRecipeCategory(type);
        if (category == null) return null;
        Object drawable = jeiRecipes.createRecipeLayoutDrawable(category, recipe, EMPTY_FOCUS).orElse(null);
        return (IRecipeLayoutDrawable<?>) drawable;
    }

    /** 空 focus：构建预览布局时不需要任何搜索焦点 */
    private static final IFocusGroup EMPTY_FOCUS = new IFocusGroup() {
        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public List<IFocus<?>> getAllFocuses() {
            return List.of();
        }

        @Override
        public Stream<IFocus<?>> getFocuses(RecipeIngredientRole role) {
            return Stream.empty();
        }

        @Override
        public <T> Stream<IFocus<T>> getFocuses(IIngredientType<T> type) {
            return Stream.empty();
        }

        @Override
        public <T> Stream<IFocus<T>> getFocuses(IIngredientType<T> type, RecipeIngredientRole role) {
            return Stream.empty();
        }
    };

    /** 真正的 JEI 布局预览：直接把 JEI 自己的画面平移过去 */
    private record DrawablePreview(IRecipeLayoutDrawable<?> drawable, int width, int height)
            implements Preview {

        @Override
        public void render(GuiGraphics gg, int x, int y, int mouseX, int mouseY) {
            drawable.setPosition(x, y);
            drawable.drawRecipe(gg, mouseX, mouseY);
            drawable.drawOverlays(gg, mouseX, mouseY);
        }
    }

    // ------------------------------------------------------------ 规则引擎（转给 Cleaner）

    @Override
    public void tick() {
        Cleaner.tick();
    }

    @Override
    public PlanPreview planPreview() {
        Cleaner.Preview p = Cleaner.previewNow();
        if (p == null) return null;
        return new PlanPreview(p.recipes(), p.items(), p.categories(), p.byMod(), p.customJob());
    }

    @Override
    public void requestApplyDelayed(int ticks) {
        Cleaner.requestApplyDelayed(ticks);
    }

    @Override
    public void requestApply() {
        Cleaner.requestApply();
    }

    @Override
    public void applyNow() {
        Cleaner.applyPending();
    }

    @Override
    public boolean hasPendingChanges() {
        return Cleaner.hasPendingChanges();
    }

    @Override
    public boolean isSyncing() {
        return Cleaner.isSyncing();
    }

    @Override
    public float progress() {
        return Cleaner.progress();
    }

    @Override
    public boolean expectSlowSync() {
        return Cleaner.expectSlowSync();
    }

    @Override
    public List<String> report() {
        return Cleaner.report();
    }

    @Override
    public Status status() {
        return new Status(Cleaner.lastHiddenRecipes(), Cleaner.lastHiddenItems(),
                Cleaner.lastApplyMs(), Cleaner.lastApplyOk());
    }
}
