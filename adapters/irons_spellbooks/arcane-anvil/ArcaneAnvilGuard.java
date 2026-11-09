import com.yuneko.foliocleaner.compat.SimpleCraftGuard;

import java.util.List;

/**
 * 示例适配器：Iron's Spellbooks 的「奥术铁砧」。
 *
 * <p>只用纯 Java（不引用任何 Minecraft 类），所以编译只需要：
 * <pre>javac -cp foliocleaner-forge-1.20.1-x.jar ArcaneAnvilGuard.java</pre>
 *
 * <p>逻辑：只有当界面是奥术铁砧（菜单类名含 ArcaneAnvil）、且输入里出现「删除」规则
 * 指定的物品时，才拦截这次合成 —— 这样不会误伤原版铁砧和其它 2 格工作台。
 */
public final class ArcaneAnvilGuard implements SimpleCraftGuard {

    @Override
    public String name() {
        return "奥术铁砧";
    }

    @Override
    public boolean blocks(List<String> slotIds, String menuClass, List<String> removeTargets) {
        if (menuClass == null || !menuClass.contains("ArcaneAnvil")) return false;
        if (slotIds == null || removeTargets == null || removeTargets.isEmpty()) return false;
        for (String id : slotIds) {
            if (id == null || id.isEmpty()) continue;
            if (removeTargets.contains(id)) return true;
        }
        return false;
    }
}
