package com.learn.cc.s11_background_tasks;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * s11_background_tasks - 主实现
 *
 * s09/s10 → s11 的核心变化: 【bash 工具支持后台异步执行】
 *
 * 心智模型:
 *   模型调 bash 时可以传 run_in_background=true, 命令扔到后台线程跑,
 *   工具立即返回 "Background task bg_0001 started",
 *   agent 继续干别的事。
 *   下一轮 LLM 调用前, 主循环把已完成的后台结果格式化成 <task_notification>
 *   塞进 user 消息, 模型下一轮就能看到 "bg_0001 已完成, 输出是 X"。
 *
 * 5 个核心机制:
 *
 *   1) bash schema 新增 run_in_background: boolean
 *      模型自主决定 "这个命令跑得久,我先扔后台"
 *
 *   2) BackgroundManager (类静态单例)
 *      tasks:   Map<id, TaskInfo>   -- 正在跑的
 *      results: Map<id, String>     -- 已完成的结果
 *      ready:   List<id>            -- 新完成待收集的
 *      counter                      -- 生成 bg_0001 递增 id
 *      lock: ReentrantLock          -- 保护并发访问
 *
 *   3) start(block) -> 起 daemon 线程跑 _runBashProcess
 *      立即返回 bg_XXXX id, 不阻塞
 *      线程完成后把结果放进 results, 把 id 放进 ready
 *
 *   4) collect() -> 主循环每轮调用
 *      从 ready 队列取出所有已完成任务
 *      格式化成 <task_notification>...</task_notification> XML 字符串
 *      主循环把这些 append 到最后一条 user 消息, 让模型下一轮看到
 *
 *   5) 进程清理 (关键: 防僵尸进程)
 *      Runtime.addShutdownHook 注册程序退出时的清理
 *      Process.descendants() 列出所有子进程 (Java 9+)
 *      Process.destroyForcibly() 强杀
 *
 * ★ Java 独有的优雅: ProcessHandle API (Java 9+)
 *    Python 要 os.killpg + start_new_session + SIGTERM 手动管进程组;
 *    Java 用 ProcessHandle.destroy() 一行搞定 + descendants() 递归杀子进程。
 *
 * 保留 s04 的 hook 系统 + JSON 打印。为独立可读, 不含 s09 memory / s10 tasks。
 */
public final class AgentLoop {

    // ── Config ─────────────────────────────────────────────────────
    private static final Dotenv DOTENV = Dotenv.configure().directory("./").ignoreIfMissing().load();
    private static final String API_KEY  = DOTENV.get("ANTHROPIC_API_KEY");
    private static final String BASE_URL = DOTENV.get("ANTHROPIC_BASE_URL");
    private static final String MODEL    = DOTENV.get("MODEL_ID");
    /** 设 DEBUG_HTTP=1 (环境变量或 .env) 时, 每轮打印发给模型的请求体和响应 JSON，便于对照 HTTP 线上格式。 */
    private static final boolean DEBUG_HTTP = "1".equals(DOTENV.get("DEBUG_HTTP"));
    private static final Path WORKDIR = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private static final AnthropicClient CLIENT = buildClient();
    private static final Scanner USER_INPUT = new Scanner(System.in);

    // system prompt 明确告诉模型: 只对独立命令用后台
    private static final String SYSTEM =
            "You are a coding agent at " + WORKDIR + ". Use tools to solve tasks. "
                    + "Set run_in_background to true only for independent Bash commands.";

    // ══════════════════════════════════════════════════════════════════
    //  ★ s11 核心 1: 通用 bash 执行 (被前台/后台共用)
    //   返回 (输出字符串, 退出码 Integer 可能为 null 表示超时)
    // ══════════════════════════════════════════════════════════════════

    /** 追踪所有活跃子进程, 程序退出时统一清理防僵尸 */
    private static final Set<Process> LIVE_PROCESSES = Collections.synchronizedSet(new LinkedHashSet<>());

    static {
        // Java 等价 Python 的 atexit.register
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (LIVE_PROCESSES) {
                for (Process p : LIVE_PROCESSES) {
                    // 先杀子进程 (递归), 再杀自己
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                }
            }
        }, "bash-cleanup"));
    }

    /** 一次 bash 执行的结果 */
    private record BashResult(String output, Integer exitCode) {}

    /**
     * 通用 bash 执行, 有超时 + 进程追踪 + 输出限长。
     * 前台/后台都用这个。
     */
    private static BashResult runBashProcess(String command) {
        Process p = null;
        try {
            p = new ProcessBuilder("bash", "-c", command)
                    .directory(new File(WORKDIR.toString()))
                    .redirectErrorStream(true)
                    .start();
            LIVE_PROCESSES.add(p);
            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return new BashResult("Error: Timeout (120s)", null);
            }
            String out = new String(p.getInputStream().readAllBytes()).trim();
            String output = out.isEmpty() ? "(no output)"
                    : (out.length() > 50_000 ? out.substring(0, 50_000) : out);
            return new BashResult(output, p.exitValue());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new BashResult("Error: " + e.getClass().getSimpleName() + ": " + e.getMessage(), null);
        } finally {
            if (p != null) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
                LIVE_PROCESSES.remove(p);
            }
        }
    }

    /** 结果格式化: 非零退出码 → 前缀"Error:" */
    private static String formatBashResult(BashResult r) {
        if (r.exitCode() == null || r.exitCode() == 0) return r.output();
        return "Error: command exited with status " + r.exitCode() + "\n" + r.output();
    }

    /** 前台 bash (模型没设 run_in_background 或 false 走这个) */
    private static String runBash(String command, Boolean runInBackground) {
        // runInBackground 参数在这里不起作用 (背景是 dispatch 层判断的)
        // 保留参数是为了 schema 兼容, 实际后台由 BackgroundManager.start 触发
        return formatBashResult(runBashProcess(command));
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s11 核心 2: BackgroundManager -- 后台任务追踪
    // ══════════════════════════════════════════════════════════════════

    /** 后台任务的元信息 */
    static final class BgTask {
        final String toolUseId;   // 关联到发起的 tool_use.id
        final String command;
        String status;             // running / completed / failed

        BgTask(String toolUseId, String command) {
            this.toolUseId = toolUseId;
            this.command = command;
            this.status = "running";
        }
    }

    /** 单例, 线程安全 */
    static final class BackgroundManager {
        private final Map<String, BgTask> tasks = new LinkedHashMap<>();
        private final Map<String, String> results = new LinkedHashMap<>();
        private final List<String> ready = new ArrayList<>();
        private int counter = 0;
        private final ReentrantLock lock = new ReentrantLock();

        /** 起一个后台任务, 立即返回 id */
        String start(ToolUseBlock block, String command) {
            if (command == null || command.strip().isEmpty()) {
                throw new IllegalArgumentException("Bash command cannot be empty");
            }
            String taskId;
            lock.lock();
            try {
                counter++;
                taskId = String.format("bg_%04d", counter);
                tasks.put(taskId, new BgTask(block.id(), command));
            } finally { lock.unlock(); }

            Thread t = new Thread(() -> runInBackground(taskId, command),
                    "bg-" + taskId);
            t.setDaemon(true);   // daemon 线程: 主程序退出时不阻拦
            t.start();
            System.out.println("  [background] started " + taskId + ": "
                    + (command.length() > 60 ? command.substring(0, 60) : command));
            return taskId;
        }

        /** 后台线程的实际执行体 */
        private void runInBackground(String taskId, String command) {
            String result;
            String status;
            try {
                BashResult br = runBashProcess(command);
                result = formatBashResult(br);
                status = (br.exitCode() != null && br.exitCode() == 0) ? "completed" : "failed";
            } catch (Exception e) {
                result = "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                status = "failed";
            }
            lock.lock();
            try {
                BgTask t = tasks.get(taskId);
                if (t == null) return;  // 被清理了
                t.status = status;
                results.put(taskId, result);
                ready.add(taskId);
            } finally { lock.unlock(); }
        }

        /**
         * 主循环每轮调用: 收集已完成的后台任务, 格式化成 XML 通知字符串。
         * 收集后从 ready/tasks/results 三个 map 清掉 (只报告一次)。
         */
        List<String> collect() {
            List<Object[]> readyList;   // [id, BgTask, result]
            lock.lock();
            try {
                readyList = new ArrayList<>();
                for (String id : ready) {
                    BgTask t = tasks.remove(id);
                    String r = results.remove(id);
                    if (t != null) readyList.add(new Object[]{id, t, r});
                }
                ready.clear();
            } finally { lock.unlock(); }

            List<String> notifications = new ArrayList<>();
            for (Object[] entry : readyList) {
                String id = (String) entry[0];
                BgTask t = (BgTask) entry[1];
                String r = (String) entry[2];
                String summary = r == null ? "" : (r.length() > 500 ? r.substring(0, 500) : r);
                notifications.add(
                        "<task_notification>\n"
                        + "  <task_id>" + id + "</task_id>\n"
                        + "  <status>" + t.status + "</status>\n"
                        + "  <command>" + t.command + "</command>\n"
                        + "  <summary>" + summary + "</summary>\n"
                        + "</task_notification>");
                System.out.println("  [background] collected " + id + ": " + t.status);
            }
            return notifications;
        }
    }

    private static final BackgroundManager BACKGROUND = new BackgroundManager();

    /** 判断是不是后台调用 */
    private static boolean shouldRunBackground(String toolName, Map<String, Object> input) {
        return "bash".equals(toolName) && Boolean.TRUE.equals(input.get("run_in_background"));
    }

    // ══════════════════════════════════════════════════════════════════
    //  基础工具 (与 s04 一致)
    // ══════════════════════════════════════════════════════════════════

    private static String runRead(String path, Integer limit) {
        try {
            List<String> lines = Files.readAllLines(WORKDIR.resolve(path));
            if (limit != null && limit < lines.size()) {
                List<String> t = new ArrayList<>(lines.subList(0, limit));
                t.add("... (" + (lines.size() - limit) + " more lines)");
                lines = t;
            }
            return String.join("\n", lines);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runWrite(String path, String content) {
        try {
            Path file = WORKDIR.resolve(path);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            return "Wrote " + content.length() + " bytes to " + path;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runEdit(String path, String oldText, String newText) {
        try {
            Path file = WORKDIR.resolve(path);
            String text = Files.readString(file);
            int idx = text.indexOf(oldText);
            if (idx < 0) return "Error: text not found in " + path;
            Files.writeString(file, text.substring(0, idx) + newText + text.substring(idx + oldText.length()));
            return "Edited " + path;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runGlob(String pattern) {
        try {
            var matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            try (Stream<Path> walk = Files.walk(WORKDIR)) {
                List<String> results = walk.filter(p -> p.startsWith(WORKDIR))
                        .map(WORKDIR::relativize)
                        .filter(rel -> !rel.toString().isEmpty())
                        .filter(matcher::matches)
                        .sorted(Comparator.naturalOrder())
                        .map(Path::toString)
                        .toList();
                return results.isEmpty() ? "(no matches)" : String.join("\n", results);
            }
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    /** 前台 dispatch (后台情况在 executeToolCall 里已经拦截,这里不会收到 bash+bg=true) */
    private static String dispatchTool(String name, Map<String, Object> input) {
        return switch (name) {
            case "bash"       -> runBash((String) input.get("command"),
                                         (Boolean) input.get("run_in_background"));
            case "read_file"  -> runRead((String) input.get("path"), asInteger(input.get("limit")));
            case "write_file" -> runWrite((String) input.get("path"), (String) input.get("content"));
            case "edit_file"  -> runEdit((String) input.get("path"),
                                         (String) input.get("old_text"),
                                         (String) input.get("new_text"));
            case "glob"       -> runGlob((String) input.get("pattern"));
            default           -> "Unknown tool: " + name;
        };
    }

    private static Integer asInteger(Object v) { return (v instanceof Number n) ? n.intValue() : null; }

    // ── Tool definitions: bash 加了 run_in_background 参数 ────────
    private static final List<Tool> TOOLS = List.of(
            // ★ s11 关键改动: bash schema 加 run_in_background: boolean
            tool("bash", "Run a shell command.",
                    Map.of("command", Map.of("type", "string"),
                           "run_in_background", Map.of("type", "boolean")),
                    List.of("command")),
            tool("read_file", "Read file contents.",
                    Map.of("path", Map.of("type", "string"),
                           "limit", Map.of("type", "integer")), List.of("path")),
            tool("write_file", "Write content to a file.",
                    Map.of("path", Map.of("type", "string"),
                           "content", Map.of("type", "string")), List.of("path", "content")),
            tool("edit_file", "Replace exact text in a file once.",
                    Map.of("path", Map.of("type", "string"),
                           "old_text", Map.of("type", "string"),
                           "new_text", Map.of("type", "string")),
                    List.of("path", "old_text", "new_text")),
            tool("glob", "Find files matching a glob pattern; ** matches recursively.",
                    Map.of("pattern", Map.of("type", "string")), List.of("pattern"))
    );

    private AgentLoop() {}

    private static Tool tool(String name, String desc, Map<String, ?> props, List<String> required) {
        return Tool.builder().name(name).description(desc)
                .inputSchema(Tool.InputSchema.builder()
                        .properties(JsonValue.from(props))
                        .putAdditionalProperty("required", JsonValue.from(required))
                        .build())
                .build();
    }

    private static AnthropicClient buildClient() {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder();
        if (API_KEY  != null && !API_KEY.isBlank())  b.apiKey(API_KEY);
        if (BASE_URL != null && !BASE_URL.isBlank()) b.baseUrl(BASE_URL);
        return b.build();
    }

    // ── Hooks (与 s04 一致, 简化到只有 permission) ────────────────
    public enum HookEvent { USER_PROMPT_SUBMIT, PRE_TOOL_USE, POST_TOOL_USE, STOP }
    public interface HookCallback extends Function<Object[], String> {}
    private static final Map<HookEvent, List<HookCallback>> HOOKS = new EnumMap<>(HookEvent.class);
    static { for (HookEvent e : HookEvent.values()) HOOKS.put(e, new ArrayList<>()); }
    public static void registerHook(HookEvent e, HookCallback cb) { HOOKS.get(e).add(cb); }
    public static String triggerHooks(HookEvent event, Object... args) {
        for (HookCallback cb : HOOKS.get(event)) {
            String r = cb.apply(args);
            if (r != null) return r;
        }
        return null;
    }

    private static final List<String> DENY_LIST = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=");

    @SuppressWarnings("unchecked")
    private static String permissionHook(Object[] args) {
        ToolUseBlock block = (ToolUseBlock) args[0];
        Map<String, Object> input = (Map<String, Object>) args[1];
        if ("bash".equals(block.name())) {
            String cmd = (String) input.getOrDefault("command", "");
            for (String p : DENY_LIST) if (cmd.contains(p)) {
                System.out.println("\n\033[31m[blocked] '" + p + "'\033[0m");
                return "Permission denied by deny list";
            }
        }
        return null;
    }

    static { registerHook(HookEvent.PRE_TOOL_USE, AgentLoop::permissionHook); }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s11 核心 3: executeToolCall 里判断是不是后台调用
    // ══════════════════════════════════════════════════════════════════

    private static String executeToolCall(ToolUseBlock block, Map<String, Object> input) {
        String blocked = triggerHooks(HookEvent.PRE_TOOL_USE, block, input);
        if (blocked != null) return blocked;

        // ★ 后台分支: 立即返回, 不阻塞
        if (shouldRunBackground(block.name(), input)) {
            try {
                String taskId = BACKGROUND.start(block, (String) input.get("command"));
                return "[Background task " + taskId
                        + " started] The result will be collected on a later turn.";
            } catch (Exception e) {
                return "Error: " + e.getMessage();
            }
        }

        // 前台分支
        return dispatchTool(block.name(), input);
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s11 核心 4: 主循环 -- 每轮开头 inject 后台结果
    // ══════════════════════════════════════════════════════════════════

    /**
     * 每轮 LLM 之前调用: 把 collected 后台通知 append 到最后一条 user 消息。
     * 如果最后一条不是 user 消息, 新建一条。
     *
     * 我们用 List<MessageParam> 维护完整历史, 方便就地修改 (加 block)。
     */
    private static int injectBackgroundResults(List<MessageParam> history) {
        List<String> notifications = BACKGROUND.collect();
        if (notifications.isEmpty()) return 0;

        List<ContentBlockParam> newBlocks = new ArrayList<>();
        for (String n : notifications) {
            newBlocks.add(ContentBlockParam.ofText(TextBlockParam.builder().text(n).build()));
        }

        if (!history.isEmpty() && history.get(history.size() - 1).role() == MessageParam.Role.USER) {
            // append 到最后一条 user 消息
            MessageParam last = history.get(history.size() - 1);
            List<ContentBlockParam> merged = new ArrayList<>();
            var content = last.content();
            if (content.string().isPresent()) {
                merged.add(ContentBlockParam.ofText(TextBlockParam.builder()
                        .text(content.string().get()).build()));
            } else if (content.blockParams().isPresent()) {
                merged.addAll(content.blockParams().get());
            }
            merged.addAll(newBlocks);
            history.set(history.size() - 1, MessageParam.builder()
                    .role(MessageParam.Role.USER).contentOfBlockParams(merged).build());
        } else {
            history.add(MessageParam.builder()
                    .role(MessageParam.Role.USER).contentOfBlockParams(newBlocks).build());
        }
        return notifications.size();
    }

    private static Message agentLoop(List<MessageParam> history) {
        while (true) {
            // ★ 每轮 LLM 之前先收集后台结果
            int injected = injectBackgroundResults(history);
            if (injected > 0) {
                System.out.println("\033[35m[bg] injected " + injected + " background notifications\033[0m");
            }

            MessageCreateParams.Builder pb = MessageCreateParams.builder()
                    .model(MODEL).system(SYSTEM).maxTokens(8000).temperature(0.3);
            TOOLS.forEach(pb::addTool);
            history.forEach(pb::addMessage);

            MessageCreateParams params = pb.build();
            if (DEBUG_HTTP) System.out.println("\n\n===============>>>>>>>>>>");
            if (DEBUG_HTTP) System.out.println("request is : ");
            if (DEBUG_HTTP) System.out.println(prettyJson(params._body()));
            Message response = CLIENT.messages().create(params);
            if (DEBUG_HTTP) System.out.println("response is : ");
            if (DEBUG_HTTP) System.out.println(prettyJson(response));

            history.add(assistantMessageParam(response));
            StopReason stop = response.stopReason().orElse(null);
            if (stop == null || !stop.equals(StopReason.TOOL_USE)) return response;

            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                Optional<ToolUseBlock> mtu = block.toolUse();
                if (mtu.isEmpty()) continue;
                ToolUseBlock tb = mtu.get();
                Map<String, Object> input = parseInput(tb);
                System.out.println("\033[36m> " + tb.name() + " " + input + "\033[0m");

                String output = executeToolCall(tb, input);
                System.out.println(output.length() > 200 ? output.substring(0, 200) : output);
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(tb.id()).content(output).build()));
            }
            history.add(MessageParam.builder()
                    .role(MessageParam.Role.USER).contentOfBlockParams(results).build());
        }
    }

    /** 把 assistant Message 打包成 MessageParam 塞回 history */
    private static MessageParam assistantMessageParam(Message response) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ContentBlock cb : response.content()) {
            cb.text().ifPresent(t -> blocks.add(ContentBlockParam.ofText(
                    TextBlockParam.builder().text(t.text()).build())));
            cb.toolUse().ifPresent(tu -> {
                Map<String, Object> input = JSON_MAPPER.convertValue(
                        tu._input(), new TypeReference<>() {});
                blocks.add(ContentBlockParam.ofToolUse(
                        com.anthropic.models.messages.ToolUseBlockParam.builder()
                                .id(tu.id()).name(tu.name()).input(JsonValue.from(input)).build()));
            });
        }
        return MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(blocks).build();
    }

    private static Map<String, Object> parseInput(ToolUseBlock block) {
        return JSON_MAPPER.convertValue(block._input(), new TypeReference<>() {});
    }

    // ── Debug JSON helpers ─────────────────────────────────────────
    private static final JsonMapper JSON_MAPPER = ObjectMappers.jsonMapper()
            .rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();

    private static String prettyJson(Object obj) {
        try { return JSON_MAPPER.writeValueAsString(obj); }
        catch (JsonProcessingException e) { return "<serialize error: " + e.getMessage() + ">"; }
    }

    // ── Entry point ────────────────────────────────────────────────
    public static void main(String[] args) {
        if (MODEL == null || MODEL.isBlank()) {
            System.err.println("MODEL_ID is not set in .env"); System.exit(1);
        }
        System.out.println("s11: Background Tasks");
        System.out.println("bash 可以传 run_in_background=true 后台执行, 下一轮收集结果");
        System.out.println("输入问题, 回车发送。输入 q 退出。\n");

        List<MessageParam> history = new ArrayList<>();
        while (true) {
            System.out.print("\033[36ms11v >> \033[0m");
            if (!USER_INPUT.hasNextLine()) break;
            String q = USER_INPUT.nextLine().trim();
            if (q.isEmpty() || q.equalsIgnoreCase("q") || q.equalsIgnoreCase("exit")) break;

            history.add(MessageParam.builder()
                    .role(MessageParam.Role.USER).content(q).build());
            Message finalResponse = agentLoop(history);

            for (ContentBlock cb : finalResponse.content()) {
                Optional<TextBlock> mt = cb.text();
                if (mt.isPresent()) System.out.println(mt.get().text());
            }
            System.out.println();
        }
    }
}
