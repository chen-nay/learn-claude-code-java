package com.learn.cc.s10_task_system;

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
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * s10_task_system - 主实现
 *
 * s09 → s10 的核心变化: 【引入持久化任务图 + 依赖检查】
 * (跟 s09 memory 是【独立的两个方向】,可以互相独立)
 *
 * 心智模型:
 *   agent 拥有一个 .tasks/ 目录, 每个任务是一个 JSON 文件。
 *   每个任务有状态 (pending/in_progress/completed) + 依赖 (blockedBy 列表)。
 *   模型通过 6 个 task 工具管理它们, 系统自动做依赖检查/循环检测/解锁通知。
 *
 * 5 个核心机制:
 *
 *   1) 任务的持久化表示
 *      Task record: id / subject / description / status / owner / blockedBy
 *      .tasks/task_a1b2c3d4.json 一个文件一条任务
 *      id 由系统生成 (8 位 hex, 类似 UUID), 模型不能自选 id
 *
 *   2) 依赖图与循环检测
 *      添加依赖时 (给 A 加"blocked by B"):
 *        - B 必须已存在
 *        - B 不能等于 A (不能自依赖)
 *        - 用 DFS 检查 B 是否传递依赖 A (若是 → 会构成环 → 拒绝)
 *
 *   3) 状态机
 *      pending → [claim_task] → in_progress → [complete_task] → completed
 *      claim 要求: status=pending 且所有 blockedBy 依赖都 completed
 *      complete 要求: status=in_progress 且 owner=agent 自己
 *
 *   4) 解锁通知
 *      complete 时对比"完成前和完成后哪些任务的 can_start 状态变了"
 *      → 告诉模型 "Unblocked: 任务 X, Y" (让它知道下一步能干啥)
 *
 *   5) 原子创建 (防 id 冲突)
 *      用 Files.createFile() + FileAlreadyExistsException 保证同名文件不会覆盖
 *      最多重试 100 次 (SecureRandom 8 hex 空间 40 亿,几乎不会真撞)
 *
 * 跟 s05 todo_write 的区别:
 *   s05 一整个 list 覆盖式更新, 无依赖概念, 无状态机, 每次替换
 *   s10 每个任务独立文件, 有依赖图 + 循环检测, 状态机严格, 有 owner
 *   两者不冲突, 甚至可以共存
 *
 * 保留 s04 的 hook 系统, 基础 5 工具, JSON 打印。
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
    private static final Path TASKS_DIR = WORKDIR.resolve(".tasks");
    private static final AnthropicClient CLIENT = buildClient();
    private static final Scanner USER_INPUT = new Scanner(System.in);
    private static final SecureRandom RNG = new SecureRandom();

    // system prompt: 明确告诉模型任务图协议
    private static final String SYSTEM =
            "You are a coding agent at " + WORKDIR + ". "
                    + "Use task tools to track dependencies and progress. "
                    + "Create all task nodes first. After create_task returns runtime-generated IDs, "
                    + "use update_task with those exact IDs to add dependencies.";

    // ══════════════════════════════════════════════════════════════════
    //  ★ s10 核心 1: Task 数据结构 & TaskStore 存储
    // ══════════════════════════════════════════════════════════════════

    /** 任务 id 格式: task_{8 位 hex}, 由系统生成,模型不能自选 */
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("^task_[0-9a-f]{8}$");

    /** 一条任务 = record, 但字段 blockedBy 是可变 List (跟 Python dataclass 一致) */
    public record Task(String id, String subject, String description,
                       String status, String owner, List<String> blockedBy) {}

    /**
     * 任务持久化存储。 .tasks/task_xxx.json 一个任务一个文件。
     * 所有方法都走这个类, 保证路径安全 (防越界) + 状态检查 + 原子写。
     */
    static final class TaskStore {

        private final Path directory;

        TaskStore(Path directory) { this.directory = directory; }

        /** 路径校验: 不能逃出 WORKDIR */
        private Path root(boolean create) throws IOException {
            if (create) Files.createDirectories(directory);
            Path r = directory.toAbsolutePath().normalize();
            if (!r.startsWith(WORKDIR)) {
                throw new IllegalArgumentException("Task store escapes the workspace");
            }
            return r;
        }

        /** 校验 task_id 格式 + 返回文件路径 */
        private Path pathOf(String taskId, boolean createRoot) throws IOException {
            if (taskId == null || !TASK_ID_PATTERN.matcher(taskId).matches()) {
                throw new IllegalArgumentException("Invalid task ID: " + taskId);
            }
            Path r = root(createRoot);
            Path p = r.resolve(taskId + ".json").toAbsolutePath().normalize();
            if (!p.startsWith(r)) throw new IllegalArgumentException("Invalid task ID: " + taskId);
            return p;
        }

        boolean exists(String taskId) {
            try { return Files.isRegularFile(pathOf(taskId, false)); }
            catch (Exception e) { return false; }
        }

        /**
         * 原子创建: SecureRandom 生成 8 hex id, Files.createFile 保证不会覆盖同名文件。
         * 40 亿 id 空间, 撞车概率极低, 100 次重试足够。
         */
        Task create(String subject, String description) throws IOException {
            String s = subject == null ? "" : subject.strip();
            if (s.isEmpty()) throw new IllegalArgumentException("Task subject cannot be empty");
            root(true);

            for (int attempt = 0; attempt < 100; attempt++) {
                byte[] bytes = new byte[4];
                RNG.nextBytes(bytes);
                StringBuilder hex = new StringBuilder("task_");
                for (byte b : bytes) hex.append(String.format("%02x", b));
                String id = hex.toString();
                Path p = pathOf(id, true);
                try {
                    // Files.createFile() = O_CREAT | O_EXCL, 已存在会抛 FileAlreadyExistsException
                    Files.createFile(p);
                    Task task = new Task(id, s, description == null ? "" : description,
                            "pending", null, new ArrayList<>());
                    Files.writeString(p, taskToJson(task));
                    return task;
                } catch (FileAlreadyExistsException e) {
                    // 撞了 → 换 id 重试
                }
            }
            throw new IOException("Could not allocate a unique task ID");
        }

        /** 反序列化 JSON → Task。校验 id 匹配 + 状态合法 */
        Task load(String taskId) throws IOException {
            Path p = pathOf(taskId, false);
            String json = Files.readString(p);
            Map<String, Object> data = JSON_MAPPER.readValue(json, new TypeReference<>() {});
            String id = String.valueOf(data.get("id"));
            String subject = String.valueOf(data.getOrDefault("subject", ""));
            String description = String.valueOf(data.getOrDefault("description", ""));
            String status = String.valueOf(data.getOrDefault("status", "pending"));
            Object ownerObj = data.get("owner");
            String owner = ownerObj == null ? null : String.valueOf(ownerObj);
            @SuppressWarnings("unchecked")
            List<String> blockedBy = (List<String>) data.getOrDefault("blockedBy", new ArrayList<>());
            if (!id.equals(taskId)) throw new IllegalArgumentException("Task file ID does not match " + taskId);
            if (!Set.of("pending", "in_progress", "completed").contains(status)) {
                throw new IllegalArgumentException("Invalid task status: " + status);
            }
            return new Task(id, subject, description, status, owner, new ArrayList<>(blockedBy));
        }

        void save(Task task) throws IOException {
            Files.writeString(pathOf(task.id(), true), taskToJson(task));
        }

        List<Task> listAll() {
            List<Task> out = new ArrayList<>();
            if (!Files.isDirectory(directory)) return out;
            try (Stream<Path> walk = Files.list(directory)) {
                List<Path> files = walk
                        .filter(p -> p.getFileName().toString().matches("task_[0-9a-f]{8}\\.json"))
                        .sorted(Comparator.naturalOrder())
                        .toList();
                for (Path p : files) {
                    String id = p.getFileName().toString().replace(".json", "");
                    try { out.add(load(id)); } catch (Exception ignore) {}
                }
            } catch (IOException ignore) {}
            return out;
        }

        /**
         * ★ 循环检测: task_id 是否传递依赖 target_id
         * DFS 迭代版 (跟 Python 原版一样, 用 list 当栈)
         */
        boolean dependsOn(String taskId, String targetId) throws IOException {
            Deque<String> pending = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            pending.push(taskId);
            while (!pending.isEmpty()) {
                String current = pending.pop();
                if (current.equals(targetId)) return true;
                if (!visited.add(current)) continue;
                if (!exists(current)) continue;
                Task t = load(current);
                for (String dep : t.blockedBy()) pending.push(dep);
            }
            return false;
        }

        /**
         * ★ 添加依赖: 校验 + 循环检测 + 保存
         * Python 原版限制: 只有 pending && owner=null 的任务能改依赖 (防运行时改)
         */
        Task updateDependencies(String taskId, List<String> addBlockedBy) throws IOException {
            if (addBlockedBy == null) throw new IllegalArgumentException("addBlockedBy must be a list");
            Task task = load(taskId);
            if (!"pending".equals(task.status()) || task.owner() != null) {
                throw new IllegalArgumentException(
                        "Task " + taskId + " dependencies can only be updated while pending and unowned");
            }
            // 去重 (保序)
            List<String> deps = new ArrayList<>(new LinkedHashSet<>(addBlockedBy));
            for (String dep : deps) {
                if (dep.equals(taskId)) throw new IllegalArgumentException("Task cannot depend on itself");
                if (!exists(dep)) throw new IllegalArgumentException("Dependency not found: " + dep);
                // 循环检测: 只对未加过的检查 (加过的说明之前已过关)
                if (!task.blockedBy().contains(dep) && dependsOn(dep, taskId)) {
                    throw new IllegalArgumentException("Dependency cycle detected: " + taskId + " -> " + dep);
                }
            }
            List<String> newBlockedBy = new ArrayList<>(task.blockedBy());
            for (String dep : deps) if (!newBlockedBy.contains(dep)) newBlockedBy.add(dep);
            Task updated = new Task(task.id(), task.subject(), task.description(),
                    task.status(), task.owner(), newBlockedBy);
            save(updated);
            return updated;
        }
    }

    private static final TaskStore TASKS = new TaskStore(TASKS_DIR);

    /** Task → JSON string (Jackson) */
    private static String taskToJson(Task t) throws JsonProcessingException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id());
        m.put("subject", t.subject());
        m.put("description", t.description());
        m.put("status", t.status());
        m.put("owner", t.owner());
        m.put("blockedBy", t.blockedBy());
        return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(m);
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s10 核心 2: 依赖 / claim / complete 的业务逻辑
    // ══════════════════════════════════════════════════════════════════

    /** 找出没完成的依赖 (查不到的也算未完成) */
    private static List<String> incompleteDependencies(Task task) {
        List<String> incomplete = new ArrayList<>();
        for (String dep : task.blockedBy()) {
            try {
                if (!"completed".equals(TASKS.load(dep).status())) incomplete.add(dep);
            } catch (Exception e) {
                incomplete.add(dep);
            }
        }
        return incomplete;
    }

    /** 该任务当前能不能启动 (所有依赖都 completed) */
    private static boolean canStart(String taskId) throws IOException {
        return incompleteDependencies(TASKS.load(taskId)).isEmpty();
    }

    /** claim: pending → in_progress (若依赖没完就拒) */
    private static String claimTask(String taskId, String owner) {
        try {
            Task task = TASKS.load(taskId);
            if (!"pending".equals(task.status())) {
                return "Task " + taskId + " is " + task.status() + ", cannot claim";
            }
            List<String> incomplete = incompleteDependencies(task);
            if (!incomplete.isEmpty()) return "Blocked by: " + incomplete;
            Task updated = new Task(task.id(), task.subject(), task.description(),
                    "in_progress", owner, task.blockedBy());
            TASKS.save(updated);
            System.out.println("  [claim] " + updated.subject() + " -> in_progress (owner: " + owner + ")");
            return "Claimed " + updated.id() + " (" + updated.subject() + ")";
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    /**
     * complete: in_progress → completed
     * ★ 关键机制: 完成前后对比 "哪些 pending 任务的 can_start 变化了"
     *              → 报告 "Unblocked: X, Y" 给模型看
     */
    private static String completeTask(String taskId, String owner) {
        try {
            Task task = TASKS.load(taskId);
            if (!"in_progress".equals(task.status())) {
                return "Task " + taskId + " is " + task.status() + ", cannot complete";
            }
            if (!owner.equals(task.owner())) {
                return "Task " + taskId + " is owned by " + task.owner() + ", not " + owner;
            }
            // 完成前: 找出所有"有依赖但已经可以启动"的 pending 任务 id (作为基准)
            Set<String> readyBefore = new HashSet<>();
            for (Task t : TASKS.listAll()) {
                if ("pending".equals(t.status()) && !t.blockedBy().isEmpty()) {
                    try { if (canStart(t.id())) readyBefore.add(t.id()); }
                    catch (Exception ignore) {}
                }
            }
            // 完成任务
            Task updated = new Task(task.id(), task.subject(), task.description(),
                    "completed", owner, task.blockedBy());
            TASKS.save(updated);
            // 完成后: 找"变成可启动"的
            List<String> unblocked = new ArrayList<>();
            for (Task t : TASKS.listAll()) {
                if ("pending".equals(t.status()) && !t.blockedBy().isEmpty()
                        && !readyBefore.contains(t.id())) {
                    try { if (canStart(t.id())) unblocked.add(t.subject()); }
                    catch (Exception ignore) {}
                }
            }
            System.out.println("  [complete] " + updated.subject());
            String msg = "Completed " + updated.id() + " (" + updated.subject() + ")";
            if (!unblocked.isEmpty()) {
                msg += "\nUnblocked: " + String.join(", ", unblocked);
                System.out.println("  [unblocked] " + String.join(", ", unblocked));
            }
            return msg;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Task 工具的 Handler 包装 (供模型调用)
    // ══════════════════════════════════════════════════════════════════

    private static String runCreateTask(String subject, String description) {
        try {
            Task task = TASKS.create(subject, description == null ? "" : description);
            System.out.println("  [create] " + task.subject());
            return "Created " + task.id() + ": " + task.subject();
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    @SuppressWarnings("unchecked")
    private static String runUpdateTask(String taskId, Object addBlockedByObj) {
        try {
            if (!(addBlockedByObj instanceof List<?> list)) {
                return "Error: addBlockedBy must be a list";
            }
            List<String> deps = new ArrayList<>();
            for (Object o : list) deps.add(String.valueOf(o));
            Task task = TASKS.updateDependencies(taskId, deps);
            String depsStr = task.blockedBy().isEmpty() ? "(none)" : String.join(", ", task.blockedBy());
            System.out.println("  [update] " + task.subject() + " blockedBy: " + depsStr);
            return "Updated " + task.id() + " blockedBy: " + depsStr;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runListTasks() {
        List<Task> tasks = TASKS.listAll();
        if (tasks.isEmpty()) return "No tasks. Use create_task to add some.";
        StringBuilder sb = new StringBuilder();
        for (Task t : tasks) {
            String marker = switch (t.status()) {
                case "pending"     -> "[ ]";
                case "in_progress" -> "[>]";
                case "completed"   -> "[x]";
                default            -> "[?]";
            };
            String deps = t.blockedBy().isEmpty() ? "" : " (blockedBy: " + String.join(", ", t.blockedBy()) + ")";
            String owner = t.owner() == null ? "" : " [" + t.owner() + "]";
            sb.append(marker).append(" ").append(t.id()).append(": ").append(t.subject())
              .append(" [").append(t.status()).append("]").append(owner).append(deps).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String runGetTask(String taskId) {
        try { return taskToJson(TASKS.load(taskId)); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    // ── Tool definitions: s04 五件套 + 6 个 task 工具 ────────────
    private static final List<Tool> TOOLS = List.of(
            tool("bash", "Run a shell command.",
                    Map.of("command", Map.of("type", "string")), List.of("command")),
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
                    Map.of("pattern", Map.of("type", "string")), List.of("pattern")),

            // ★ s10 新增 6 个任务工具
            tool("create_task", "Create a task and return its runtime-generated ID.",
                    Map.of("subject", Map.of("type", "string"),
                           "description", Map.of("type", "string")),
                    List.of("subject")),
            tool("update_task", "Add dependencies using IDs returned by create_task.",
                    Map.of("task_id", Map.of("type", "string", "pattern", "^task_[0-9a-f]{8}$"),
                           "addBlockedBy", Map.of("type", "array",
                                   "items", Map.of("type", "string", "pattern", "^task_[0-9a-f]{8}$"),
                                   "minItems", 1)),
                    List.of("task_id", "addBlockedBy")),
            tool("list_tasks", "List tasks with status, owner, and dependencies.",
                    Map.of(), List.of()),
            tool("get_task", "Get a task by ID.",
                    Map.of("task_id", Map.of("type", "string")), List.of("task_id")),
            tool("claim_task", "Claim a pending task whose dependencies are complete.",
                    Map.of("task_id", Map.of("type", "string")), List.of("task_id")),
            tool("complete_task", "Complete the task claimed by this agent.",
                    Map.of("task_id", Map.of("type", "string")), List.of("task_id"))
    );

    private AgentLoop() {}

    private static Tool tool(String name, String desc, Map<String, ?> props, List<String> required) {
        Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder()
                .properties(JsonValue.from(props));
        // list_tasks 的 required 是空,写空数组也 OK,但 required=[] JSON Schema 有些实现不喜欢
        if (!required.isEmpty()) {
            schemaBuilder.putAdditionalProperty("required", JsonValue.from(required));
        }
        return Tool.builder().name(name).description(desc)
                .inputSchema(schemaBuilder.build())
                .build();
    }

    private static AnthropicClient buildClient() {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder();
        if (API_KEY  != null && !API_KEY.isBlank())  b.apiKey(API_KEY);
        if (BASE_URL != null && !BASE_URL.isBlank()) b.baseUrl(BASE_URL);
        return b.build();
    }

    // ── 基础 5 工具 impl (与 s04 一致) ───────────────────────────
    private static String runBash(String command) {
        try {
            Process p = new ProcessBuilder("bash", "-c", command)
                    .directory(new File(WORKDIR.toString())).redirectErrorStream(true).start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return "Error: Timeout (120s)"; }
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (out.isEmpty()) return "(no output)";
            return out.length() > 50_000 ? out.substring(0, 50_000) : out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "Error: " + e.getMessage();
        }
    }

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

    /** 统一的分发: 基础工具 + 6 个 task 工具 */
    private static String dispatchTool(String name, Map<String, Object> input) {
        return switch (name) {
            case "bash"          -> runBash((String) input.get("command"));
            case "read_file"     -> runRead((String) input.get("path"), asInteger(input.get("limit")));
            case "write_file"    -> runWrite((String) input.get("path"), (String) input.get("content"));
            case "edit_file"     -> runEdit((String) input.get("path"),
                                            (String) input.get("old_text"),
                                            (String) input.get("new_text"));
            case "glob"          -> runGlob((String) input.get("pattern"));
            case "create_task"   -> runCreateTask((String) input.get("subject"),
                                                  (String) input.get("description"));
            case "update_task"   -> runUpdateTask((String) input.get("task_id"),
                                                  input.get("addBlockedBy"));
            case "list_tasks"    -> runListTasks();
            case "get_task"      -> runGetTask((String) input.get("task_id"));
            case "claim_task"    -> claimTask((String) input.get("task_id"), "agent");
            case "complete_task" -> completeTask((String) input.get("task_id"), "agent");
            default              -> "Unknown tool: " + name;
        };
    }

    private static Integer asInteger(Object v) { return (v instanceof Number n) ? n.intValue() : null; }

    // ── Hooks (与 s04 一致的最简版) ────────────────────────────────
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
    //  Agent Loop (骨架与 s04 一致, 无任何 s09 memory 代码)
    // ══════════════════════════════════════════════════════════════════

    private static Message agentLoop(MessageCreateParams.Builder paramsBuilder) {
        while (true) {
            MessageCreateParams params = paramsBuilder.build();
            if (DEBUG_HTTP) System.out.println("\n\n===============>>>>>>>>>>");
            if (DEBUG_HTTP) System.out.println("request is : ");
            if (DEBUG_HTTP) System.out.println(prettyJson(params._body()));
            Message response = CLIENT.messages().create(params);
            if (DEBUG_HTTP) System.out.println("response is : ");
            if (DEBUG_HTTP) System.out.println(prettyJson(response));

            paramsBuilder.addMessage(response);
            StopReason stop = response.stopReason().orElse(null);
            if (stop == null || !stop.equals(StopReason.TOOL_USE)) return response;

            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                Optional<ToolUseBlock> mtu = block.toolUse();
                if (mtu.isEmpty()) continue;
                ToolUseBlock tb = mtu.get();
                Map<String, Object> input = parseInput(tb);
                System.out.println("\033[36m> " + tb.name() + " " + input + "\033[0m");

                String blocked = triggerHooks(HookEvent.PRE_TOOL_USE, tb, input);
                String output = (blocked != null) ? blocked : dispatchTool(tb.name(), input);
                System.out.println(output.length() > 200 ? output.substring(0, 200) : output);
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(tb.id()).content(output).build()));
            }
            paramsBuilder.addMessage(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(results)
                    .build());
        }
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
        System.out.println("s10: Task System — dependencies and task state");
        System.out.println("Tasks dir: " + TASKS_DIR);
        System.out.println("已有 task: " + TASKS.listAll().size());
        System.out.println("输入问题, 回车发送。输入 q 退出。\n");

        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(MODEL).system(SYSTEM).maxTokens(8000).temperature(0.3);
        TOOLS.forEach(paramsBuilder::addTool);

        while (true) {
            System.out.print("\033[36ms10v >> \033[0m");
            if (!USER_INPUT.hasNextLine()) break;
            String q = USER_INPUT.nextLine().trim();
            if (q.isEmpty() || q.equalsIgnoreCase("q") || q.equalsIgnoreCase("exit")) break;

            paramsBuilder.addUserMessage(q);
            Message r = agentLoop(paramsBuilder);
            for (ContentBlock cb : r.content()) {
                Optional<TextBlock> mt = cb.text();
                if (mt.isPresent()) System.out.println(mt.get().text());
            }
            System.out.println();
        }
    }
}
