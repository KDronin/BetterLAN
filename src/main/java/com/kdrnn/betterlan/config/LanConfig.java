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
        NODE_IP = BUILDER.comment("节点服务器 IP 或域名").define("nodeIp", "www.example.com");
        NODE_PORT = BUILDER.comment("节点服务器端口").defineInRange("nodePort", 7654, 1, 65535);
        GROUP = BUILDER.comment("N2N 组名 (Community)").define("group", "OurWorld");
        PASSWORD = BUILDER.comment("连接密码").define("password", "123456");
        BUILDER.pop();

        BUILDER.push("GUI Settings");
        BUTTON_X = BUILDER.comment("主菜单按钮的 X 坐标").defineInRange("buttonX", 10, 0, 9999);
        BUTTON_Y = BUILDER.comment("主菜单按钮的 Y 坐标").defineInRange("buttonY", 10, 0, 9999);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }
}