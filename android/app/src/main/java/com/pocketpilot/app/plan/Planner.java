package com.pocketpilot.app.plan;

/**
 * 规划器接口。实现方只能产出受约束的结构化计划，
 * 不允许返回自由文本指令、代码片段或屏幕坐标。
 */
public interface Planner {
    /** 规划器标识，会写进日志和 results.jsonl，便于复核计划是谁生成的。 */
    String id();

    PlanResult plan(String utterance, ContextFacts facts);
}
