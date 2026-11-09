package com.yuneko.foliocleaner.client;

import com.yuneko.foliocleaner.DupOverrides;
import com.yuneko.foliocleaner.DuplicateGroups;
import net.minecraft.client.Minecraft;

import java.util.List;
import java.util.Map;

/**
 * 「重复组」扫描的**客户端入口**。
 *
 * <p>{@link DuplicateGroups} 本身不引客户端类（这样专用服务器也能用它扫 tag），
 * 但客户端那几处调用要自己把 {@code Minecraft.getInstance().level} 递进去 ——
 * 这个包装类就是为了不让每个调用方都写一遍。
 *
 * <p>⚠️ 不要在 common / 服务端代码里引用本类（会拖进 {@code net.minecraft.client}）。
 */
public final class DupScan {

    private DupScan() {
    }

    /** 用磁盘上的覆盖层（{@code config/foliocleaner/dup-overrides.json}）扫一遍 */
    public static List<DuplicateGroups.Group> groups() {
        return groups(DupOverrides.load());
    }

    public static List<DuplicateGroups.Group> groups(Map<String, DuplicateGroups.Override> overrides) {
        return DuplicateGroups.scan(Minecraft.getInstance().level, overrides);
    }
}
