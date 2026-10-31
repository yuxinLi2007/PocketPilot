package com.pocketpilot.app.plan;

/**
 * 云端规划占位实现。
 *
 * 这里刻意不返回任何计划：没有配置 API 时，编造一个“看起来合理”的计划比直接失败危险得多。
 * 真正接入云端模型时，必须保留 PlanValidator 这一层，并且模型输出仍然只能是
 * 白名单内的结构化步骤，不能是代码、脚本或坐标。
 */
public final class CloudPlannerStub implements Planner {
    public static final String ID = "CLOUD_STUB_V1";

    private final boolean configured;

    /** @param configured 是否已配置可用的云端模型 API（密钥、端点等）。 */
    public CloudPlannerStub(boolean configured) {
        this.configured = configured;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public PlanResult plan(String utterance, ContextFacts facts) {
        if (!configured) {
            return PlanResult.unavailable("NO_API_KEY",
                    "未配置云端模型 API；不返回编造的计划");
        }
        return PlanResult.unavailable("NOT_IMPLEMENTED", "云端规划尚未接入");
    }

    public boolean isConfigured() {
        return configured;
    }
}
