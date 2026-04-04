package com.kdrnn.betterlan.config;

import net.minecraftforge.common.ForgeConfigSpec;

public class LanConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<String> NODE_IP;
    public static final ForgeConfigSpec.IntValue NODE_PORT;

    public static final ForgeConfigSpec.ConfigValue<String> GROUP;
    public static final ForgeConfigSpec.ConfigValue<String> PASSWORD;

    public static final ForgeConfigSpec.IntValue BUTTON_X;
    public static final ForgeConfigSpec.IntValue BUTTON_Y;

    static {
        BUILDER.push("Network Settings");
        NODE_IP = BUILDER.comment("Node server IP or domain").define("nodeIp", "www.example.com");
        NODE_PORT = BUILDER.comment("Node server port").defineInRange("nodePort", 45678, 1, 65535);
        GROUP = BUILDER.comment("N2N group name (Community)").define("group", "OurWorld");
        PASSWORD = BUILDER.comment("Connection password").define("password", "123456");
        BUILDER.pop();

        BUILDER.push("GUI Settings");
        BUTTON_X = BUILDER.comment("Main menu button X coordinate").defineInRange("buttonX", 10, 0, 9999);
        BUTTON_Y = BUILDER.comment("Main menu button Y coordinate").defineInRange("buttonY", 10, 0, 9999);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}