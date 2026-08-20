package dev.skillshare;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

/**
 * SkillShare 独立 mod 入口（@Mod）。
 *
 * <p>与主 MICx-toolkit（modid=micxtoolkit）互不冲突，可共存。
 * 职责仅：注册 {@link SkillShareCore} 并拉起技能共享。
 */
@Mod(modid = SkillShareMod.MODID, name = "SkillShare", version = SkillShareMod.VERSION,
        clientSideOnly = true)
public class SkillShareMod {

    public static final String MODID = "skillshare_micx";
    public static final String VERSION = "1.3.0";

    @Mod.Instance(MODID)
    public static SkillShareMod instance;

    private SkillShareCore core;

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        core = new SkillShareCore();
        core.init();
    }
}