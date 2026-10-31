package com.pocketpilot.app.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** 一份受约束的执行计划。plannerId 用于说明计划是谁生成的，便于复核。 */
public final class TaskPlan {
    private final String id;
    private final String utterance;
    private final String plannerId;
    private final List<TaskStep> steps;

    public TaskPlan(String plannerId, String utterance, List<TaskStep> steps) {
        this.id = "TASK-" + UUID.randomUUID().toString().substring(0, 8);
        this.plannerId = plannerId == null ? "UNKNOWN" : plannerId;
        this.utterance = utterance == null ? "" : utterance;
        List<TaskStep> copy = new ArrayList<>();
        if (steps != null) {
            for (TaskStep step : steps) {
                if (step != null) {
                    copy.add(step);
                }
            }
        }
        this.steps = Collections.unmodifiableList(copy);
    }

    public String id() { return id; }

    public String utterance() { return utterance; }

    public String plannerId() { return plannerId; }

    public List<TaskStep> steps() { return steps; }

    public int stepCount() { return steps.size(); }

    public boolean isEmpty() { return steps.isEmpty(); }

    public String describeSteps() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            if (i > 0) {
                sb.append(" -> ");
            }
            sb.append(i + 1).append('.').append(steps.get(i).describe());
        }
        return sb.toString();
    }
}
