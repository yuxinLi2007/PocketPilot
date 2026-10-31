package com.pocketpilot.app.exec;

import com.pocketpilot.app.core.CommandResult;
import com.pocketpilot.app.core.CommandType;
import com.pocketpilot.app.core.PocketCommand;

/** 每个命令一个执行器。新增命令 = 新增 executor + 注册 + 补测试记录。 */
public interface CommandExecutor {
    CommandType type();

    CommandResult execute(PocketCommand command);
}
