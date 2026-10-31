package com.pocketpilot.app.core;

/** 命令执行的最终结果。detail 给人看，evidence 用于复核。 */
public final class CommandResult {
    private final PocketCommand command;
    private final ExecutionStatus status;
    private final String detail;
    private final String evidence;
    private final long elapsedMillis;
    private final long finishedAtMillis;

    public CommandResult(PocketCommand command, ExecutionStatus status, String detail, String evidence,
                         long elapsedMillis, long finishedAtMillis) {
        this.command = command;
        this.status = status;
        this.detail = detail == null ? "" : detail;
        this.evidence = evidence == null ? "" : evidence;
        this.elapsedMillis = elapsedMillis;
        this.finishedAtMillis = finishedAtMillis;
    }

    public PocketCommand command() { return command; }
    public ExecutionStatus status() { return status; }
    public String detail() { return detail; }
    public String evidence() { return evidence; }
    public long elapsedMillis() { return elapsedMillis; }
    public long finishedAtMillis() { return finishedAtMillis; }

    /** 只允许把“已执行·未确认”升级为“已确认”，并用新的证据说明原因。 */
    public CommandResult confirm(String confirmationDetail, String confirmationEvidence) {
        return new CommandResult(command, ExecutionStatus.CONFIRMED,
                confirmationDetail, evidence + "; " + confirmationEvidence,
                elapsedMillis, System.currentTimeMillis());
    }
}
