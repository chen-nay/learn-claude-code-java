# learn-claude-code-java

> **本项目完全移植自 [shareAI-lab/learn-claude-code](https://github.com/shareAI-lab/learn-claude-code)。**
> 课程结构、章节划分、每章要讲的 harness 机制、以及各章 `README.md` 与配图，都来自原项目。
> 这里只做了一件事：把每章的 `code.py` 用 **Java** 重写了一遍，方便 Java 开发者对照学习 Agent 内核。
>
> 想读原始教程、看作者的完整讲解，请以上游仓库为准。本仓库不声称任何原创性，全部功劳归 shareAI Lab。

---

## 这是什么

一门「从 0 到 1 搭 Agent Harness」的教学项目。17 章，每章在同一个 agent loop 上叠加一个机制：

```
while (stopReason == TOOL_USE) {
    response = LLM(messages, tools)
    execute tools
    append results
}
```

循环本身始终不变，变的是它周围的工具、知识、上下文管理、权限、协作。

每一章对应一个包目录，里面是：

| 文件 | 说明 |
|---|---|
| `AgentLoop.java` | 该章的可运行实现（对应上游的 `code.py`） |
| `README.md` | 该章讲解（中文，译自上游 `README.zh.md`） |
| `images/` | 配图 |

> 各章 `README.md` 正文里的代码片段仍是 Python（沿用上游以突出机制）；对应的 Java 实现看同目录的 `AgentLoop.java`。

## 环境要求

- JDK 17+
- Maven 3.8+
- 一个 Anthropic 或兼容 Anthropic Messages API 的 key

## 配置

```sh
cp .env.example .env
```

然后编辑 `.env`，至少填这两项：

| 变量 | 必填 | 说明 |
|---|---|---|
| `ANTHROPIC_API_KEY` | 是 | 你的 key，[console.anthropic.com](https://console.anthropic.com/) 获取；用兼容端点时填对应厂商的 key |
| `MODEL_ID` | 是 | 模型 id，例如 `claude-sonnet-4-6` |
| `ANTHROPIC_BASE_URL` | 否 | 用 GLM / Kimi / DeepSeek / MiniMax 等 Anthropic 兼容端点时填，`.env.example` 里有各家对照表 |
| `DEBUG_HTTP` | 否 | 设为 `1` 时，每轮打印发给模型的请求体和响应 JSON，方便对照 HTTP 线上格式 |

`.env` 已在 `.gitignore` 里，不会被提交；`.env.example` 只是模板，不含真实 key。

## 运行某一章

```sh
mvn -q compile

# 例：运行 s01
mvn -q compile exec:java -Dexec.mainClass=com.learn.cc.s01_agent_loop.AgentLoop
```

把 `s01_agent_loop` 换成任意章的包名即可（`s02_tool_use` … `s17_goal_loop`）。
程序是交互式的：输入问题回车发送，输入 `q` 退出。

> **安全提示**：s01 / s02 会直接执行模型生成的 shell 命令。建议在一个临时测试目录里运行，别在重要项目上跑。s03 起加入权限控制。

## 章节

| 章 | 主题 | 机制 |
|---|---|---|
| s01 | Agent Loop | 一个循环 + bash |
| s02 | Tool Use | 工具分发表，加工具不动循环 |
| s03 | Permission | deny-list / 规则匹配 / 人工确认三道门 |
| s04 | Hooks | PreToolUse / PostToolUse 等扩展点 |
| s05 | TodoWrite | 先列计划再执行 + 定期提醒 |
| s06 | Subagent | 用全新 `messages[]` 跑子任务，只回传最终文本 |
| s07 | Skill Loading | system prompt 里只放目录，用到再 `load_skill` 拉全文 |
| s08 | Context Compact | 4 级压缩：tool_result_budget → snip → micro → summarize |
| s09 | Memory | 跨会话记忆：selection / extraction / consolidation |
| s10 | Task System | 磁盘持久化的任务依赖图，环检测 |
| s11 | Background Tasks | 慢命令丢后台线程，完成时注入通知 |
| s12 | Cron Scheduler | 按时间触发 prompt |
| s13 | Agent Teams | 常驻 teammate 线程 + 文件邮箱 + Plan/Shutdown 协议 + git worktree 任务隔离 |
| s14 | MCP Plugin | 外部工具发现，并入同一个工具池 |
| s15 | Integrated Harness | s01–s14 各机制挂在同一个循环上 |
| s16 | Workflow Runtime | 固定编排写成脚本，journal 支持续跑，parallel / pipeline |
| s17 | Goal Loop | 独立评估器决定「循环何时真正可以停」 |

## 与 Python 原版的差异

大部分章节是逐行忠实移植。以下几章做了教学取舍，细节见各自 `AgentLoop.java` 顶部注释：

- **s06 / s07** —— 保留核心机制，砍掉从 s04 一路带过来的部分非核心 hook（`log` / `large_output` / `context_inject` / `summary`）；s07 未搬整个 hook 框架，只留内联 deny-list。
- **s13** —— 教学骨架 + git worktree。跨进程 fcntl 锁改用 JVM 内 `ReentrantLock`；plan `work_version` 追踪、完善的部分失败回滚未搬。
- **s15** —— 整合示范骨架。压缩退化成单一字符阈值（非 5 段式）；无 retry / 信号处理；memory 只读；MCP 只 mock 1 个 server。
- **s17** —— 核心完整；未搬 `restore()`（重启后从历史 transcript 重建目标状态）、`MAX_TURNS` 环境变量、命令行 Goal 参数。

## 许可证

沿用上游的 **MIT License**（Copyright (c) 2024 shareAI Lab），见 [`LICENSE`](LICENSE)。
