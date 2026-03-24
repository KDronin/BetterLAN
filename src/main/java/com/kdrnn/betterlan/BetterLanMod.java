package com.kdrnn.betterlan;

import com.kdrnn.betterlan.config.LanConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

@Mod(BetterLanMod.MODID)
public class BetterLanMod {
    public static final String MODID = "betterlan";
    public static boolean uuidFixEnabled = false;

    public BetterLanMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, LanConfig.SPEC);
    }
}