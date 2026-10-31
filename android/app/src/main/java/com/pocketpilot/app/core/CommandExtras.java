package com.pocketpilot.app.core;

/** 传给替代目标应用的显式标记；真实微信/地图会忽略这些 extra。 */
public final class CommandExtras {
    public static final String COMMAND_ID = "pp_command_id";
    public static final String COMMAND_TYPE = "pp_command_type";
    public static final String ADDRESS = "pp_address";
    public static final String MOCK_PACKAGE = "com.pocketpilot.mocktarget";
    public static final String ACTION_TARGET_ACK = "com.pocketpilot.app.ACTION_TARGET_ACK";
    public static final String EXTRA_TARGET_PACKAGE = "target_package";
    public static final String EXTRA_STATUS = "target_status";
    public static final String EXTRA_DETAIL = "target_detail";

    private CommandExtras() {
    }
}
