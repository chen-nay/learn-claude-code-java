package com.learn.cc.s15_integrated_harness;

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
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * s15_integrated_harness - 主实现 (【整合示范】)
 *
 * s14 → s15 的核心变化: 【不加新机制,把 s01~s14 全部机制串在【同一个 agent loop】里】
 *
 * ⚠️ 学习提示: Python 原版 3291 行, 是把之前所有能力集大成的"综合运行时"。
 * 完全对齐要 3000+ 行 Java, 学习价值反而下降(重复 s01-s14 的实现细节)。
 * 本 Java 版做【整合示范】: 把每个机制的"最小骨架"挂到一个循环里,
 * 让你看清"多机制如何在同一个 while(true) 中协同"。
 *
 * ═══════════════════════════════════════════════════════════════════
 *  ★ 整合的机制清单 (来自哪一节 → 在主循环的哪个位置)
 * ═══════════════════════════════════════════════════════════════════
 *
 *   ┌─ user 输入 ─┐
 *   │             │
 *   │        [s04] UserPromptSubmit hook
 *   │             │
 *   │        [s12] cron queue: 到点的 prompt 注入
 *   │        [s11] background 通知: <task_notification> 注入
 *   │        [s13] team events: teammate 消息注入
 *   │             │
 *   │        [s08] compact history (简化版: char 阈值)
 *   │        [s09] memory recall: 相关 memory 注入 SYSTEM
 *   │        [s07] skill catalog 注入 SYSTEM
 *   │        [s14] MCP tools 动态加入工具池
 *   │             │
 *   │        LLM 调用
 *   │             │
 *   │        [s04] PreToolUse hook (permission)
 *   │        [s03] 硬拒绝列表
 *   │        [s14] MCP host policy
 *   │             │
 *   │        工具执行 (s02 基础 + s07 load_skill + s10 task + s11 bg + s12 cron
 *   │                  + s13 team + s14 MCP + s09 memory)
 *   │             │
 *   │        [s04] PostToolUse hook (大输出警告)
 *   │        [s05] rounds_since_todo 累加, ≥3 注入 reminder
 *   │             │
 *   │        循环回到 LLM 调用
 *   │             │
 *   │        stop_reason != tool_use → [s04] Stop hook → return
 *   │             │
 *   │        [s09] memory extract + consolidate
 *   └─ 打印结果 ──┘
 *
 * 教学取舍:
 *   ✓ 保留骨架: skill / memory / task / bg / cron / team / MCP / hooks / compact
 *   ✗ 简化: memory 用最小 recall/extract, compact 只做 char 阈值裁剪
 *   ✗ 省略: git worktree, plan version, subprocess 进程组, 完善的错误回滚
 *
 * 建议对照阅读: s07/s09/s10/s11/s12/s13/s14 的实现, s15 就是把它们
 *              叠在一起。看到重复的 static 变量/类别怕烦 - 那正是 s15 的教学点。
 */
public final class AgentLoop {

    // ══════════════════════════════════════════════════════════════════
    //  0. Config
    // ══════════════════════════════════════════════════════════════════
    private static final Dotenv DOTENV = Dotenv.configure().directory("./").ignoreIfMissing().load();
    private static final String API_KEY  = DOTENV.get("ANTHROPIC_API_KEY");
    private static final String BASE_URL = DOTENV.get("ANTHROPIC_BASE_URL");
    private static final String MODEL    = DOTENV.get("MODEL_ID");
    /** 设 DEBUG_HTTP=1 (环境变量或 .env) 时, 每轮打印发给模型的请求体和响应 JSON，便于对照 HTTP 线上格式。 */
    private static final boolean DEBUG_HTTP = "1".equals(DOTENV.get("DEBUG_HTTP"));
    private static final Path WORKDIR = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private static final Path SKILLS_DIR = WORKDIR.resolve("skills");
    private static final Path MEMORY_DIR = WORKDIR.resolve(".memory");
    private static final Path TASKS_DIR = WORKDIR.resolve(".tasks");
    private static final Path MAILBOX_DIR = WORKDIR.resolve(".mailboxes");
    private static final Path CRON_FILE = WORKDIR.resolve(".scheduled_tasks.json");
    private static final AnthropicClient CLIENT = buildClient();
    private static final Scanner USER_INPUT = new Scanner(System.in);
    private static final SecureRandom RNG = new SecureRandom();

    private static AnthropicClient buildClient() {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder();
        if (API_KEY  != null && !API_KEY.isBlank())  b.apiKey(API_KEY);
        if (BASE_URL != null && !BASE_URL.isBlank()) b.baseUrl(BASE_URL);
        return b.build();
    }

    // ══════════════════════════════════════════════════════════════════
    //  1. [s07] Skill Loading - 启动扫 skills/, 加进 SYSTEM catalog
    // ══════════════════════════════════════════════════════════════════
    public record SkillEntry(String name, String description, String content) {}
    private static final Map<String, SkillEntry> SKILL_REGISTRY = new TreeMap<>();

    static { scanSkills(); }

    private static void scanSkills() {
        if (!Files.isDirectory(SKILLS_DIR)) return;
        try (Stream<Path> s = Files.list(SKILLS_DIR)) {
            s.filter(Files::isDirectory).sorted().forEach(dir -> {
                Path md = dir.resolve("SKILL.md");
                if (!Files.isRegularFile(md)) return;
                try {
                    String raw = Files.readString(md);
                    String name = dir.getFileName().toString();
                    String desc = "";
                    if (raw.startsWith("---")) {
                        String[] parts = raw.split("---", 3);
                        if (parts.length >= 3) {
                            try {
                                Object p = new Yaml().load(parts[1]);
                                if (p instanceof Map<?, ?> m) {
                                    if (m.get("name") != null) name = String.valueOf(m.get("name"));
                                    if (m.get("description") != null) desc = String.valueOf(m.get("description")).trim();
                                }
                            } catch (Exception ignore) {}
                        }
                    }
                    SKILL_REGISTRY.put(name, new SkillEntry(name, desc, raw));
                } catch (IOException ignore) {}
            });
        } catch (IOException ignore) {}
    }

    /** 给 SYSTEM prompt 用的 skill 简介列表 */
    private static String skillCatalog() {
        if (SKILL_REGISTRY.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Skills available:\n");
        for (SkillEntry s : SKILL_REGISTRY.values()) {
            String d = s.description().split("\n", 2)[0].trim();
            sb.append("- ").append(s.name()).append(": ").append(d).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String runLoadSkill(String name) {
        SkillEntry s = SKILL_REGISTRY.get(name);
        return s == null ? "Skill not found: " + name : s.content();
    }

    // ══════════════════════════════════════════════════════════════════
    //  2. [s09] Memory - 简化版: 无 LLM 召回, 全量注入 catalog + 关键词匹配 recall
    // ══════════════════════════════════════════════════════════════════
    public record MemoryRecord(String filename, String name, String description, String body) {}

    private static List<MemoryRecord> listMemoryFiles() {
        List<MemoryRecord> out = new ArrayList<>();
        if (!Files.isDirectory(MEMORY_DIR)) return out;
        try (Stream<Path> s = Files.list(MEMORY_DIR)) {
            s.filter(p -> p.getFileName().toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().equals("MEMORY.md"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            String raw = Files.readString(p);
                            String name = p.getFileName().toString().replace(".md", "");
                            String desc = "";
                            String body = raw;
                            if (raw.startsWith("---")) {
                                String[] parts = raw.split("---", 3);
                                if (parts.length >= 3) {
                                    try {
                                        Object x = new Yaml().load(parts[1]);
                                        if (x instanceof Map<?, ?> m) {
                                            if (m.get("name") != null) name = String.valueOf(m.get("name"));
                                            if (m.get("description") != null) desc = String.valueOf(m.get("description"));
                                        }
                                        body = parts[2].strip();
                                    } catch (Exception ignore) {}
                                }
                            }
                            out.add(new MemoryRecord(p.getFileName().toString(), name, desc, body));
                        } catch (IOException ignore) {}
                    });
        } catch (IOException ignore) {}
        return out;
    }

    private static String memoryCatalog() {
        List<MemoryRecord> recs = listMemoryFiles();
        if (recs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Memory catalog:\n");
        for (MemoryRecord r : recs) {
            sb.append("- ").append(r.name()).append(": ").append(r.description()).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** 简化版 recall: 关键词匹配 (原 s09 是先 LLM 召回, 失败降级关键词) */
    private static String memoryRecall(String query) {
        if (query == null || query.isBlank()) return "";
        List<MemoryRecord> recs = listMemoryFiles();
        if (recs.isEmpty()) return "";
        Set<String> words = new LinkedHashSet<>();
        var m = Pattern.compile("[a-z0-9_]{3,}|[\\u4e00-\\u9fff]{2,}").matcher(query.toLowerCase());
        while (m.find()) words.add(m.group());
        List<Map.Entry<Integer, MemoryRecord>> ranked = new ArrayList<>();
        for (MemoryRecord r : recs) {
            String text = (r.name() + " " + r.description() + " " + r.body()).toLowerCase();
            int score = 0;
            for (String w : words) if (text.contains(w)) score++;
            if (score > 0) ranked.add(Map.entry(score, r));
        }
        ranked.sort((a, b) -> Integer.compare(b.getKey(), a.getKey()));
        if (ranked.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("Relevant memory:\n");
        int limit = Math.min(3, ranked.size());
        for (int i = 0; i < limit; i++) {
            MemoryRecord r = ranked.get(i).getValue();
            sb.append("- ").append(r.name()).append(": ").append(r.body()).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    // ══════════════════════════════════════════════════════════════════
    //  3. [s10] Task System (共享的持久化任务, 简化: 无 owner assignment 追踪)
    // ══════════════════════════════════════════════════════════════════
    private static final Pattern TASK_ID = Pattern.compile("^task_[0-9a-f]{8}$");
    public record Task(String id, String subject, String description,
                       String status, String owner, List<String> blockedBy) {}
    private static final ReentrantLock TASK_LOCK = new ReentrantLock();

    private static Path taskPath(String id) {
        if (!TASK_ID.matcher(id).matches()) throw new IllegalArgumentException("Invalid task ID: " + id);
        return TASKS_DIR.resolve(id + ".json").toAbsolutePath().normalize();
    }

    private static Task createTask(String subject, String description) throws IOException {
        TASK_LOCK.lock();
        try {
            Files.createDirectories(TASKS_DIR);
            for (int i = 0; i < 100; i++) {
                byte[] b = new byte[4]; RNG.nextBytes(b);
                StringBuilder h = new StringBuilder("task_");
                for (byte x : b) h.append(String.format("%02x", x));
                String id = h.toString();
                Path p = taskPath(id);
                try {
                    Files.createFile(p);
                    Task t = new Task(id, subject.trim(), description == null ? "" : description,
                            "pending", null, new ArrayList<>());
                    saveTask(t);
                    return t;
                } catch (java.nio.file.FileAlreadyExistsException e) { /* retry */ }
            }
            throw new IOException("Cannot allocate task id");
        } finally { TASK_LOCK.unlock(); }
    }

    private static void saveTask(Task t) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id()); m.put("subject", t.subject()); m.put("description", t.description());
        m.put("status", t.status()); m.put("owner", t.owner()); m.put("blockedBy", t.blockedBy());
        Files.writeString(taskPath(t.id()), JSON_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(m));
    }

    private static Task loadTask(String id) throws IOException {
        Map<String, Object> d = JSON_MAPPER.readValue(Files.readString(taskPath(id)), new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<String> deps = (List<String>) d.getOrDefault("blockedBy", new ArrayList<>());
        Object owner = d.get("owner");
        return new Task(String.valueOf(d.get("id")),
                String.valueOf(d.getOrDefault("subject", "")),
                String.valueOf(d.getOrDefault("description", "")),
                String.valueOf(d.getOrDefault("status", "pending")),
                owner == null ? null : String.valueOf(owner),
                new ArrayList<>(deps));
    }

    private static List<Task> listTasksAll() {
        List<Task> out = new ArrayList<>();
        if (!Files.isDirectory(TASKS_DIR)) return out;
        try (Stream<Path> s = Files.list(TASKS_DIR)) {
            s.filter(p -> p.getFileName().toString().matches("task_[0-9a-f]{8}\\.json"))
                    .sorted().forEach(p -> {
                        try { out.add(loadTask(p.getFileName().toString().replace(".json", ""))); }
                        catch (Exception ignore) {}
                    });
        } catch (IOException ignore) {}
        return out;
    }

    private static String runCreateTask(String subject, String description) {
        try { Task t = createTask(subject, description); return "Created " + t.id() + ": " + t.subject(); }
        catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runListTasks() {
        List<Task> ts = listTasksAll();
        if (ts.isEmpty()) return "No tasks.";
        StringBuilder sb = new StringBuilder();
        for (Task t : ts) {
            String icon = switch (t.status()) {
                case "pending" -> "[ ]"; case "in_progress" -> "[~]"; case "completed" -> "[x]"; default -> "[?]";
            };
            String o = t.owner() == null ? "" : " [" + t.owner() + "]";
            sb.append(icon).append(" ").append(t.id()).append(": ").append(t.subject())
              .append(" [").append(t.status()).append("]").append(o).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String runClaimTask(String id) {
        TASK_LOCK.lock();
        try {
            Task t = loadTask(id);
            if (!"pending".equals(t.status())) return "Not pending: " + t.status();
            saveTask(new Task(t.id(), t.subject(), t.description(), "in_progress", "agent", t.blockedBy()));
            return "Claimed " + id;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
        finally { TASK_LOCK.unlock(); }
    }

    private static String runCompleteTask(String id) {
        TASK_LOCK.lock();
        try {
            Task t = loadTask(id);
            if (!"in_progress".equals(t.status())) return "Not in_progress";
            saveTask(new Task(t.id(), t.subject(), t.description(), "completed", t.owner(), t.blockedBy()));
            return "Completed " + id;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
        finally { TASK_LOCK.unlock(); }
    }

    // ══════════════════════════════════════════════════════════════════
    //  4. [s05] TodoWrite - 简化: 全局 List, 每次覆盖, 3 轮 reminder
    // ══════════════════════════════════════════════════════════════════
    private static final List<Map<String, String>> TODOS = new ArrayList<>();

    @SuppressWarnings("unchecked")
    private static String runTodoWrite(Object todos) {
        if (!(todos instanceof List<?> l)) return "Error: todos must be a list";
        TODOS.clear();
        for (Object o : l) {
            if (o instanceof Map<?, ?> m) {
                TODOS.add(Map.of("content", String.valueOf(m.get("content")),
                        "status", String.valueOf(m.get("status"))));
            }
        }
        StringBuilder sb = new StringBuilder("\033[33m## Current Tasks\033[0m\n");
        for (Map<String, String> t : TODOS) {
            String marker = switch (t.get("status")) {
                case "pending" -> "[ ]"; case "in_progress" -> "[>]"; case "completed" -> "[x]"; default -> "[?]";
            };
            sb.append(marker).append(" ").append(t.get("content")).append("\n");
        }
        System.out.println(sb);
        return "Updated " + TODOS.size() + " todos";
    }

    // ══════════════════════════════════════════════════════════════════
    //  5. [s11] Background bash - daemon 线程 + 通知注入
    // ══════════════════════════════════════════════════════════════════
    static final class BgTask {
        final String toolUseId; final String command; String status = "running";
        BgTask(String id, String cmd) { this.toolUseId = id; this.command = cmd; }
    }
    private static final Map<String, BgTask> BG_TASKS = new LinkedHashMap<>();
    private static final Map<String, String> BG_RESULTS = new LinkedHashMap<>();
    private static final List<String> BG_READY = new ArrayList<>();
    private static int BG_COUNTER = 0;
    private static final ReentrantLock BG_LOCK = new ReentrantLock();

    private static String bgStart(ToolUseBlock block, String command) {
        String id;
        BG_LOCK.lock();
        try {
            id = String.format("bg_%04d", ++BG_COUNTER);
            BG_TASKS.put(id, new BgTask(block.id(), command));
        } finally { BG_LOCK.unlock(); }
        Thread t = new Thread(() -> {
            String result; String status;
            try {
                Process p = new ProcessBuilder("bash", "-c", command)
                        .directory(new File(WORKDIR.toString())).redirectErrorStream(true).start();
                boolean finished = p.waitFor(120, TimeUnit.SECONDS);
                if (!finished) { p.destroyForcibly(); result = "Timeout"; status = "failed"; }
                else {
                    String out = new String(p.getInputStream().readAllBytes()).trim();
                    result = out.isEmpty() ? "(no output)" : (out.length() > 5000 ? out.substring(0, 5000) : out);
                    status = p.exitValue() == 0 ? "completed" : "failed";
                }
            } catch (Exception e) { result = "Error: " + e.getMessage(); status = "failed"; }
            BG_LOCK.lock();
            try {
                BgTask bt = BG_TASKS.get(id);
                if (bt != null) { bt.status = status; BG_RESULTS.put(id, result); BG_READY.add(id); }
            } finally { BG_LOCK.unlock(); }
        }, "bg-" + id);
        t.setDaemon(true);
        t.start();
        System.out.println("  \033[35m[bg]\033[0m started " + id + ": " + command);
        return id;
    }

    /** 每轮 LLM 之前调用: 收集已完成的后台任务, 返回 <task_notification> 字符串列表 */
    private static List<String> bgCollect() {
        BG_LOCK.lock();
        try {
            List<String> out = new ArrayList<>();
            for (String id : BG_READY) {
                BgTask t = BG_TASKS.remove(id);
                String r = BG_RESULTS.remove(id);
                if (t != null) {
                    out.add("<task_notification>\n"
                            + "  <task_id>" + id + "</task_id>\n"
                            + "  <status>" + t.status + "</status>\n"
                            + "  <command>" + t.command + "</command>\n"
                            + "  <summary>" + (r == null ? "" : (r.length() > 300 ? r.substring(0, 300) : r)) + "</summary>\n"
                            + "</task_notification>");
                    System.out.println("  \033[35m[bg]\033[0m collected " + id + ": " + t.status);
                }
            }
            BG_READY.clear();
            return out;
        } finally { BG_LOCK.unlock(); }
    }

    // ══════════════════════════════════════════════════════════════════
    //  6. [s12] Cron - 简化版: 内存 only, 无持久化, 后台每秒扫
    // ══════════════════════════════════════════════════════════════════
    static final class CronJob {
        final String id; final String cron; final String prompt;
        String lastFired;
        CronJob(String id, String cron, String prompt) { this.id = id; this.cron = cron; this.prompt = prompt; }
    }
    private static final Map<String, CronJob> CRON_JOBS = new LinkedHashMap<>();
    private static final List<CronJob> CRON_QUEUE = new ArrayList<>();
    private static final ReentrantLock CRON_LOCK = new ReentrantLock();
    private static final AtomicBoolean CRON_STOP = new AtomicBoolean(false);

    private static String runScheduleCron(String cron, String prompt) {
        String[] fields = cron.strip().split("\\s+");
        if (fields.length != 5) return "Error: cron needs 5 fields";
        String id = "cron_" + String.format("%06x", RNG.nextInt(0xFFFFFF));
        CRON_LOCK.lock();
        try { CRON_JOBS.put(id, new CronJob(id, cron, prompt)); }
        finally { CRON_LOCK.unlock(); }
        return "Scheduled " + id + ": " + cron;
    }

    private static boolean cronMatches(String cronExpr, LocalDateTime m) {
        String[] f = cronExpr.strip().split("\\s+");
        int cw = m.getDayOfWeek().getValue() % 7;
        return fieldMatches(f[0], m.getMinute()) && fieldMatches(f[1], m.getHour())
                && fieldMatches(f[3], m.getMonthValue())
                && (("*".equals(f[2]) && "*".equals(f[4]))
                    || ("*".equals(f[2]) ? fieldMatches(f[4], cw)
                    : ("*".equals(f[4]) ? fieldMatches(f[2], m.getDayOfMonth())
                    : (fieldMatches(f[2], m.getDayOfMonth()) || fieldMatches(f[4], cw)))));
    }

    private static boolean fieldMatches(String f, int v) {
        if ("*".equals(f)) return true;
        if (f.startsWith("*/")) return v % Integer.parseInt(f.substring(2)) == 0;
        if (f.contains(",")) for (String p : f.split(",")) if (fieldMatches(p.trim(), v)) return true;
        if (f.contains(",")) return false;
        if (f.contains("-")) {
            String[] r = f.split("-", 2);
            return Integer.parseInt(r[0]) <= v && v <= Integer.parseInt(r[1]);
        }
        try { return v == Integer.parseInt(f); } catch (Exception e) { return false; }
    }

    private static void cronSchedulerLoop() {
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        while (!CRON_STOP.get()) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            LocalDateTime now = LocalDateTime.now();
            String marker = now.format(fmt);
            CRON_LOCK.lock();
            try {
                for (CronJob j : CRON_JOBS.values()) {
                    if (marker.equals(j.lastFired)) continue;
                    try {
                        if (cronMatches(j.cron, now)) {
                            j.lastFired = marker;
                            CRON_QUEUE.add(j);
                            System.out.println("  \033[35m[cron]\033[0m due " + j.id + ": " + j.prompt);
                        }
                    } catch (Exception ignore) {}
                }
            } finally { CRON_LOCK.unlock(); }
        }
    }

    private static List<CronJob> consumeCronQueue() {
        CRON_LOCK.lock();
        try { List<CronJob> out = new ArrayList<>(CRON_QUEUE); CRON_QUEUE.clear(); return out; }
        finally { CRON_LOCK.unlock(); }
    }

    // ══════════════════════════════════════════════════════════════════
    //  7. [s13] Team - 简化 MessageBus + spawn_teammate (只做基础邮箱)
    // ══════════════════════════════════════════════════════════════════
    static final class MessageBus {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();

        void send(String from, String to, String content, String type, Map<String, Object> meta) {
            lock.lock();
            try {
                Files.createDirectories(MAILBOX_DIR);
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("from", from); msg.put("to", to); msg.put("content", content);
                msg.put("type", type); msg.put("metadata", meta == null ? Map.of() : meta);
                Files.writeString(MAILBOX_DIR.resolve(to + ".jsonl"),
                        JSON_MAPPER.writeValueAsString(msg) + "\n",
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                changed.signalAll();
                System.out.println("  \033[36m[bus]\033[0m " + from + " -> " + to + " (" + type + ")");
            } catch (Exception e) { /* ignore */ }
            finally { lock.unlock(); }
        }

        List<Map<String, Object>> readInbox(String agent) {
            lock.lock();
            try {
                Path p = MAILBOX_DIR.resolve(agent + ".jsonl");
                if (!Files.exists(p)) return List.of();
                List<Map<String, Object>> out = new ArrayList<>();
                for (String line : Files.readAllLines(p)) {
                    if (line.isBlank()) continue;
                    try { out.add(JSON_MAPPER.readValue(line, new TypeReference<>() {})); }
                    catch (Exception ignore) {}
                }
                Files.deleteIfExists(p);
                return out;
            } catch (IOException e) { return List.of(); }
            finally { lock.unlock(); }
        }

        boolean peek(String agent) {
            Path p = MAILBOX_DIR.resolve(agent + ".jsonl");
            try { return Files.exists(p) && Files.size(p) > 0; } catch (IOException e) { return false; }
        }
    }
    private static final MessageBus BUS = new MessageBus();
    private static final Map<String, String> ACTIVE_TEAMMATES = new ConcurrentHashMap<>();

    /** 简化 teammate: 单轮工作,读 task 描述做完发 result 给 lead 就退出 */
    private static String runSpawnTeammate(String name, String role, String prompt, String taskId) {
        if (ACTIVE_TEAMMATES.containsKey(name)) return "Teammate '" + name + "' already exists";
        ACTIVE_TEAMMATES.put(name, "working");
        Thread t = new Thread(() -> {
            try {
                String initPrompt = prompt;
                if (taskId != null) {
                    runClaimTask(taskId);
                    Task tk = loadTask(taskId);
                    initPrompt += "\n[Assigned task " + tk.id() + "] " + tk.subject() + "\n" + tk.description();
                }
                // 极简: 单轮 LLM 调用 + 报告结果
                MessageCreateParams params = MessageCreateParams.builder()
                        .model(MODEL).system("You are '" + name + "', a " + role
                                + ". Do the task briefly and report back concisely.")
                        .maxTokens(2000).temperature(0.3)
                        .addUserMessage(initPrompt).build();
                Message resp = CLIENT.messages().create(params);
                String text = resp.content().stream().flatMap(cb -> cb.text().stream())
                        .map(TextBlock::text).reduce("", (a, b) -> a + b);
                if (taskId != null) runCompleteTask(taskId);
                BUS.send(name, "lead", text.isEmpty() ? "(done)" : text, "result", null);
            } catch (Exception e) {
                BUS.send(name, "lead", e.getMessage(), "error", null);
            } finally {
                ACTIVE_TEAMMATES.remove(name);
                System.out.println("  \033[35m[teammate]\033[0m " + name + " finished");
            }
        }, "teammate-" + name);
        t.setDaemon(true);
        t.start();
        System.out.println("  \033[35m[teammate]\033[0m " + name + " spawned as " + role);
        return "Teammate '" + name + "' spawned. End turn; events will arrive via mailbox.";
    }

    private static String runListTeammates() {
        if (ACTIVE_TEAMMATES.isEmpty()) return "No active teammates.";
        StringBuilder sb = new StringBuilder();
        ACTIVE_TEAMMATES.forEach((n, s) -> sb.append(n).append(": ").append(s).append("\n"));
        return sb.toString().stripTrailing();
    }

    /** 主循环每轮之前: 拿 lead 邮箱里的 team events */
    private static List<Map<String, Object>> consumeLeadInbox() { return BUS.readInbox("lead"); }

    private static String formatTeamEvents(List<Map<String, Object>> msgs) {
        StringBuilder sb = new StringBuilder("[Team events]\n");
        for (Map<String, Object> m : msgs) {
            sb.append("[").append(m.get("type")).append("] ").append(m.get("from"))
              .append(": ").append(m.get("content")).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    // ══════════════════════════════════════════════════════════════════
    //  8. [s14] MCP - 动态工具池 (完整继承 s14 简化)
    // ══════════════════════════════════════════════════════════════════
    public record McpToolDef(String name, String description, Map<String, Object> inputSchema) {}
    public interface McpHandler extends Function<Map<String, Object>, String> {}

    static final class MCPClient {
        final String name;
        final List<McpToolDef> tools = new ArrayList<>();
        final Map<String, McpHandler> handlers = new LinkedHashMap<>();
        MCPClient(String name) { this.name = name; }
        String callTool(String tn, Map<String, Object> args) {
            McpHandler h = handlers.get(tn);
            if (h == null) return "MCP error: unknown tool";
            try { return h.apply(args); } catch (Exception e) { return "MCP error: " + e.getMessage(); }
        }
    }

    private static MCPClient mockDocs() {
        MCPClient s = new MCPClient("docs");
        s.tools.add(new McpToolDef("search", "Search docs.",
                Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")),
                       "required", List.of("query"))));
        s.handlers.put("search", args -> "[docs] Found for '" + args.get("query") + "'");
        return s;
    }

    private static final Map<String, Supplier<MCPClient>> MOCK_SERVERS = Map.of("docs", AgentLoop::mockDocs);
    private static final Map<String, MCPClient> MCP_CLIENTS = new LinkedHashMap<>();

    private static String runConnectMcp(String name) {
        if (MCP_CLIENTS.containsKey(name)) return "Already connected: " + name;
        Supplier<MCPClient> f = MOCK_SERVERS.get(name);
        if (f == null) return "Unknown server. Available: " + String.join(", ", MOCK_SERVERS.keySet());
        MCPClient s = f.get();
        MCP_CLIENTS.put(name, s);
        return "Connected " + name + ": " + s.tools.size() + " tools discovered";
    }

    // ══════════════════════════════════════════════════════════════════
    //  9. [s08] Compact - 极简: char 数 > 阈值就删中间的 tool_result 保 head+tail
    // ══════════════════════════════════════════════════════════════════
    private static final int COMPACT_THRESHOLD = 40_000;

    private static int estimateChars(List<MessageParam> messages) {
        int total = 0;
        for (MessageParam m : messages) {
            var c = m.content();
            if (c.string().isPresent()) total += c.string().get().length();
            else if (c.blockParams().isPresent()) {
                for (ContentBlockParam b : c.blockParams().get()) {
                    b.text().ifPresent(t -> {});   // 不好精确算,估个
                    total += 200;   // 每个 block 平均估
                }
            }
        }
        return total;
    }

    private static List<MessageParam> maybeCompact(List<MessageParam> history) {
        if (estimateChars(history) <= COMPACT_THRESHOLD || history.size() <= 10) return history;
        // 极简: 保留头 3 + 尾 5, 中间替换成一条 "[N messages elided by compaction]"
        int head = 3, tailStart = history.size() - 5;
        if (head >= tailStart) return history;
        List<MessageParam> out = new ArrayList<>(history.subList(0, head));
        out.add(MessageParam.builder().role(MessageParam.Role.USER)
                .content("[" + (tailStart - head) + " earlier messages elided by compaction]").build());
        out.addAll(history.subList(tailStart, history.size()));
        System.out.println("\033[35m[compact]\033[0m elided " + (tailStart - head) + " messages");
        return out;
    }

    // ══════════════════════════════════════════════════════════════════
    //  10. 基础工具 (与 s04 一致)
    // ══════════════════════════════════════════════════════════════════
    private static String runBash(String command) {
        try {
            Process p = new ProcessBuilder("bash", "-c", command)
                    .directory(new File(WORKDIR.toString())).redirectErrorStream(true).start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return "Timeout"; }
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
            Path f = WORKDIR.resolve(path);
            if (f.getParent() != null) Files.createDirectories(f.getParent());
            Files.writeString(f, content);
            return "Wrote " + content.length() + " bytes to " + path;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runEdit(String path, String oldT, String newT) {
        try {
            Path f = WORKDIR.resolve(path);
            String text = Files.readString(f);
            int i = text.indexOf(oldT);
            if (i < 0) return "Error: text not found";
            Files.writeString(f, text.substring(0, i) + newT + text.substring(i + oldT.length()));
            return "Edited " + path;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runGlob(String pattern) {
        try {
            var matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            try (Stream<Path> w = Files.walk(WORKDIR)) {
                List<String> r = w.filter(p -> p.startsWith(WORKDIR))
                        .map(WORKDIR::relativize)
                        .filter(rel -> !rel.toString().isEmpty())
                        .filter(matcher::matches).sorted().map(Path::toString).toList();
                return r.isEmpty() ? "(no matches)" : String.join("\n", r);
            }
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static Integer asInt(Object v) { return (v instanceof Number n) ? n.intValue() : null; }

    // ══════════════════════════════════════════════════════════════════
    //  11. [s04] Hooks
    // ══════════════════════════════════════════════════════════════════
    private static final List<String> DENY_LIST = List.of("rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=");

    /** permission: 硬拒绝 + MCP policy */
    @SuppressWarnings("unchecked")
    private static String permissionCheck(ToolUseBlock block, Map<String, Object> input) {
        String name = block.name();
        if ("bash".equals(name)) {
            String cmd = (String) input.getOrDefault("command", "");
            for (String p : DENY_LIST) if (cmd.contains(p)) {
                System.out.println("\n\033[31m[blocked] '" + p + "'\033[0m");
                return "Permission denied by deny list";
            }
        }
        if (name.startsWith("mcp__")) {
            // 简化: 所有 MCP 工具都放行 (s14 有 host policy 表)
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    //  12. 工具池组装 (基础 + task + team + skill + cron + MCP + connect_mcp)
    // ══════════════════════════════════════════════════════════════════

    private static final List<Tool> BUILTIN_TOOLS = List.of(
            tool("bash", "Run a shell command. Set run_in_background=true for long-running commands.",
                    Map.of("command", Map.of("type", "string"),
                           "run_in_background", Map.of("type", "boolean")), List.of("command")),
            tool("read_file", "Read file contents.",
                    Map.of("path", Map.of("type", "string"),
                           "limit", Map.of("type", "integer")), List.of("path")),
            tool("write_file", "Write content to a file.",
                    Map.of("path", Map.of("type", "string"),
                           "content", Map.of("type", "string")), List.of("path", "content")),
            tool("edit_file", "Replace exact text once.",
                    Map.of("path", Map.of("type", "string"),
                           "old_text", Map.of("type", "string"),
                           "new_text", Map.of("type", "string")),
                    List.of("path", "old_text", "new_text")),
            tool("glob", "Find files by pattern.",
                    Map.of("pattern", Map.of("type", "string")), List.of("pattern")),

            // [s05] todo
            tool("todo_write", "Update task checklist.",
                    Map.of("todos", Map.of("type", "array",
                            "items", Map.of("type", "object",
                                    "properties", Map.of(
                                            "content", Map.of("type", "string"),
                                            "status", Map.of("type", "string",
                                                    "enum", List.of("pending", "in_progress", "completed"))),
                                    "required", List.of("content", "status")))),
                    List.of("todos")),

            // [s07] skill loading
            tool("load_skill", "Load full content of a skill.",
                    Map.of("name", Map.of("type", "string")), List.of("name")),

            // [s10] task tools
            tool("create_task", "Create a task, return its ID.",
                    Map.of("subject", Map.of("type", "string"),
                           "description", Map.of("type", "string")), List.of("subject")),
            tool("list_tasks", "List all tasks.", Map.of(), List.of()),
            tool("claim_task", "Claim a pending task.",
                    Map.of("task_id", Map.of("type", "string")), List.of("task_id")),
            tool("complete_task", "Complete an in-progress task.",
                    Map.of("task_id", Map.of("type", "string")), List.of("task_id")),

            // [s12] cron
            tool("schedule_cron", "Schedule a prompt with cron expression (5 fields).",
                    Map.of("cron", Map.of("type", "string"),
                           "prompt", Map.of("type", "string")), List.of("cron", "prompt")),

            // [s13] team
            tool("spawn_teammate", "Spawn a teammate to work on a task.",
                    Map.of("name", Map.of("type", "string"),
                           "role", Map.of("type", "string"),
                           "prompt", Map.of("type", "string"),
                           "task_id", Map.of("type", "string")),
                    List.of("name", "role", "prompt")),
            tool("list_teammates", "List active teammates.", Map.of(), List.of()),

            // [s14] MCP
            tool("connect_mcp", "Connect an MCP server (available: docs).",
                    Map.of("name", Map.of("type", "string",
                            "enum", List.of("docs"))), List.of("name"))
    );

    /**
     * ★ 动态工具池: 每轮组装 (基础 + MCP)
     * MCP 工具名 mcp__<server>__<tool>, handler 分发到 server.callTool
     */
    public record ToolPool(List<Tool> tools, Map<String, Function<Map<String, Object>, String>> handlers) {}

    private static ToolPool assembleToolPool() {
        List<Tool> tools = new ArrayList<>(BUILTIN_TOOLS);
        Map<String, Function<Map<String, Object>, String>> handlers = new LinkedHashMap<>();
        // 基础 handler
        handlers.put("bash", args -> {
            // ★ [s11] background bash: 通过 dispatchAgent 处理 (这里作占位, agent loop 单独处理)
            return runBash((String) args.get("command"));
        });
        handlers.put("read_file", args -> runRead((String) args.get("path"), asInt(args.get("limit"))));
        handlers.put("write_file", args -> runWrite((String) args.get("path"), (String) args.get("content")));
        handlers.put("edit_file", args -> runEdit((String) args.get("path"),
                (String) args.get("old_text"), (String) args.get("new_text")));
        handlers.put("glob", args -> runGlob((String) args.get("pattern")));
        handlers.put("todo_write", args -> runTodoWrite(args.get("todos")));
        handlers.put("load_skill", args -> runLoadSkill((String) args.get("name")));
        handlers.put("create_task", args -> runCreateTask((String) args.get("subject"),
                (String) args.getOrDefault("description", "")));
        handlers.put("list_tasks", args -> runListTasks());
        handlers.put("claim_task", args -> runClaimTask((String) args.get("task_id")));
        handlers.put("complete_task", args -> runCompleteTask((String) args.get("task_id")));
        handlers.put("schedule_cron", args -> runScheduleCron(
                (String) args.get("cron"), (String) args.get("prompt")));
        handlers.put("spawn_teammate", args -> runSpawnTeammate(
                (String) args.get("name"), (String) args.get("role"),
                (String) args.get("prompt"), (String) args.get("task_id")));
        handlers.put("list_teammates", args -> runListTeammates());
        handlers.put("connect_mcp", args -> runConnectMcp((String) args.get("name")));

        // ★ MCP 工具动态加入
        for (Map.Entry<String, MCPClient> e : MCP_CLIENTS.entrySet()) {
            String serverName = e.getKey();
            MCPClient server = e.getValue();
            for (McpToolDef td : server.tools) {
                String prefixed = "mcp__" + serverName + "__" + td.name();
                Tool.InputSchema.Builder isb = Tool.InputSchema.builder()
                        .properties(JsonValue.from(td.inputSchema().getOrDefault("properties", Map.of())));
                Object req = td.inputSchema().get("required");
                if (req != null) isb.putAdditionalProperty("required", JsonValue.from(req));
                tools.add(Tool.builder().name(prefixed).description(td.description()).inputSchema(isb.build()).build());
                String tn = td.name();
                handlers.put(prefixed, args -> server.callTool(tn, args));
            }
        }
        return new ToolPool(tools, handlers);
    }

    // ══════════════════════════════════════════════════════════════════
    //  13. SYSTEM prompt 组装 (skill catalog + memory catalog + relevant memory)
    // ══════════════════════════════════════════════════════════════════

    private static String assembleSystem(String recallHint) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are the Lead coding agent at ").append(WORKDIR).append(". Act, don't explain.\n");
        sb.append("You have s01-s14 abilities: tools, permissions, todos, skills, memory, tasks, "
                + "background bash, cron, teammates, MCP.\n");
        String sc = skillCatalog();
        if (!sc.isEmpty()) sb.append("\n").append(sc);
        String mc = memoryCatalog();
        if (!mc.isEmpty()) sb.append("\n").append(mc);
        if (!recallHint.isEmpty()) sb.append("\n").append(recallHint);
        if (!MCP_CLIENTS.isEmpty()) sb.append("\nConnected MCP servers: ").append(String.join(", ", MCP_CLIENTS.keySet()));
        return sb.toString();
    }

    private static Tool tool(String name, String desc, Map<String, ?> props, List<String> required) {
        Tool.InputSchema.Builder isb = Tool.InputSchema.builder().properties(JsonValue.from(props));
        if (!required.isEmpty()) isb.putAdditionalProperty("required", JsonValue.from(required));
        return Tool.builder().name(name).description(desc).inputSchema(isb.build()).build();
    }

    // ══════════════════════════════════════════════════════════════════
    //  14. ★★★ Agent Loop - 所有机制在这里集成
    // ══════════════════════════════════════════════════════════════════

    private static final ReentrantLock AGENT_LOCK = new ReentrantLock();
    private static int ROUNDS_SINCE_TODO = 0;

    private static Message agentLoop(List<MessageParam> history, String activeRequest) {
        while (true) {
            // ==================== 每轮 LLM 之前的准备 ====================

            // (a) [s11] 收集后台任务通知
            List<String> bgNotifications = bgCollect();
            // (b) [s12] cron 队列消费 (作为 user 消息插入)
            List<CronJob> cronFired = consumeCronQueue();
            // (c) [s13] team events 消费
            List<Map<String, Object>> teamEvents = consumeLeadInbox();

            List<ContentBlockParam> injected = new ArrayList<>();
            if (!bgNotifications.isEmpty()) {
                for (String n : bgNotifications) {
                    injected.add(ContentBlockParam.ofText(TextBlockParam.builder().text(n).build()));
                }
            }
            if (!cronFired.isEmpty()) {
                for (CronJob j : cronFired) {
                    injected.add(ContentBlockParam.ofText(TextBlockParam.builder()
                            .text("[Scheduled] " + j.prompt).build()));
                }
            }
            if (!teamEvents.isEmpty()) {
                injected.add(ContentBlockParam.ofText(TextBlockParam.builder()
                        .text(formatTeamEvents(teamEvents)).build()));
            }
            if (!injected.isEmpty()) {
                // 塞进最后一条 user 消息, 或新造一条
                if (!history.isEmpty() && history.get(history.size() - 1).role() == MessageParam.Role.USER) {
                    MessageParam last = history.get(history.size() - 1);
                    List<ContentBlockParam> merged = new ArrayList<>();
                    var c = last.content();
                    if (c.string().isPresent())
                        merged.add(ContentBlockParam.ofText(TextBlockParam.builder().text(c.string().get()).build()));
                    else if (c.blockParams().isPresent()) merged.addAll(c.blockParams().get());
                    merged.addAll(injected);
                    history.set(history.size() - 1, MessageParam.builder().role(MessageParam.Role.USER)
                            .contentOfBlockParams(merged).build());
                } else {
                    history.add(MessageParam.builder().role(MessageParam.Role.USER)
                            .contentOfBlockParams(injected).build());
                }
                System.out.println("\033[35m[inject]\033[0m bg=" + bgNotifications.size()
                        + " cron=" + cronFired.size() + " team=" + teamEvents.size());
            }

            // (d) [s08] compact
            List<MessageParam> compacted = maybeCompact(history);
            if (compacted != history) { history.clear(); history.addAll(compacted); }

            // (e) [s09] recall + [s07] skill catalog: 组装 SYSTEM
            String recall = memoryRecall(activeRequest);
            String system = assembleSystem(recall);

            // (f) [s14] 动态组装工具池
            ToolPool pool = assembleToolPool();

            // ==================== LLM 调用 ====================
            MessageCreateParams.Builder pb = MessageCreateParams.builder()
                    .model(MODEL).system(system).maxTokens(8000).temperature(0.3);
            pool.tools().forEach(pb::addTool);
            history.forEach(pb::addMessage);
            MessageCreateParams params = pb.build();

            if (DEBUG_HTTP) System.out.println("\n\n===============>>>>>>>>>>");
            System.out.println("[integrated] tools=" + pool.tools().size()
                    + " history=" + history.size()
                    + " skills=" + SKILL_REGISTRY.size()
                    + " memories=" + listMemoryFiles().size()
                    + " tasks=" + listTasksAll().size()
                    + " teammates=" + ACTIVE_TEAMMATES.size()
                    + " mcp=" + MCP_CLIENTS.size());
            if (DEBUG_HTTP) System.out.println("request is : ");
            if (DEBUG_HTTP) System.out.println(prettyJson(params._body()));

            Message response;
            try { response = CLIENT.messages().create(params); }
            catch (Exception e) {
                System.out.println("  [error] " + e.getMessage());
                return null;
            }
            if (DEBUG_HTTP) System.out.println("response is : ");
            if (DEBUG_HTTP) System.out.println(prettyJson(response));

            history.add(assistantToParam(response));

            StopReason stop = response.stopReason().orElse(null);
            if (stop == null || !stop.equals(StopReason.TOOL_USE)) {
                // Stop hook 简化: 打一条日志就好
                System.out.println("\033[90m[hook] Stop: turn end\033[0m");
                return response;
            }

            // ==================== 工具执行 ====================
            List<ContentBlockParam> results = new ArrayList<>();
            boolean usedTodo = false;
            for (ContentBlock block : response.content()) {
                Optional<ToolUseBlock> mtu = block.toolUse();
                if (mtu.isEmpty()) continue;
                ToolUseBlock tb = mtu.get();
                Map<String, Object> input = JSON_MAPPER.convertValue(tb._input(), new TypeReference<>() {});
                System.out.println("\033[36m> " + tb.name() + " " + input + "\033[0m");

                // (g) [s04] PreToolUse hook: permission
                String blocked = permissionCheck(tb, input);
                String output;
                if (blocked != null) {
                    output = blocked;
                } else if ("bash".equals(tb.name()) && Boolean.TRUE.equals(input.get("run_in_background"))) {
                    // ★ [s11] background bash: 特殊处理
                    output = "Background task " + bgStart(tb, (String) input.get("command"))
                            + " started. Result arrives as <task_notification>.";
                } else {
                    // 普通分发
                    var handler = pool.handlers().get(tb.name());
                    output = handler == null ? "Unknown tool: " + tb.name() : handler.apply(input);
                }
                System.out.println(output.length() > 200 ? output.substring(0, 200) : output);

                // (h) [s04] PostToolUse hook: 大输出警告
                if (output.length() > 100_000) {
                    System.out.println("\033[33m[hook] Large output: " + output.length() + " chars\033[0m");
                }
                if ("todo_write".equals(tb.name())) usedTodo = true;
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(tb.id()).content(output).build()));
            }

            // (i) [s05] TodoWrite reminder 累积
            if (usedTodo) ROUNDS_SINCE_TODO = 0;
            else ROUNDS_SINCE_TODO++;
            if (ROUNDS_SINCE_TODO >= 3) {
                results.add(ContentBlockParam.ofText(TextBlockParam.builder()
                        .text("<reminder>Update your todos.</reminder>").build()));
                System.out.println("\033[35m[nag]\033[0m reminder injected");
                ROUNDS_SINCE_TODO = 0;
            }

            history.add(MessageParam.builder().role(MessageParam.Role.USER)
                    .contentOfBlockParams(results).build());
        }
    }

    private static MessageParam assistantToParam(Message response) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ContentBlock cb : response.content()) {
            cb.text().ifPresent(t -> blocks.add(ContentBlockParam.ofText(
                    TextBlockParam.builder().text(t.text()).build())));
            cb.toolUse().ifPresent(tu -> {
                Map<String, Object> input = JSON_MAPPER.convertValue(tu._input(), new TypeReference<>() {});
                blocks.add(ContentBlockParam.ofToolUse(
                        com.anthropic.models.messages.ToolUseBlockParam.builder()
                                .id(tu.id()).name(tu.name()).input(JsonValue.from(input)).build()));
            });
        }
        return MessageParam.builder().role(MessageParam.Role.ASSISTANT).contentOfBlockParams(blocks).build();
    }

    private static final JsonMapper JSON_MAPPER = ObjectMappers.jsonMapper()
            .rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();

    private static String prettyJson(Object obj) {
        try { return JSON_MAPPER.writeValueAsString(obj); }
        catch (JsonProcessingException e) { return "<serialize error: " + e.getMessage() + ">"; }
    }

    // ══════════════════════════════════════════════════════════════════
    //  15. 入口: 启动 cron 后台线程, 主循环等 stdin 或 mailbox
    // ══════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        if (MODEL == null || MODEL.isBlank()) { System.err.println("MODEL_ID missing"); System.exit(1); }

        System.out.println("s15: Integrated Harness");
        System.out.println("集成: s02 tools + s03 permission + s04 hooks + s05 todo + s07 skill");
        System.out.println("     + s08 compact + s09 memory + s10 tasks + s11 bg + s12 cron");
        System.out.println("     + s13 team + s14 mcp");
        System.out.println("Skills=" + SKILL_REGISTRY.size() + " Memories=" + listMemoryFiles().size()
                + " Tasks=" + listTasksAll().size());
        System.out.println("输入问题, 回车发送。输入 q 退出。\n");

        // 启动 cron 后台线程
        Thread cronThread = new Thread(AgentLoop::cronSchedulerLoop, "cron-scheduler");
        cronThread.setDaemon(true);
        cronThread.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> CRON_STOP.set(true)));

        List<MessageParam> history = new ArrayList<>();

        while (true) {
            System.out.print("\033[36ms15v >> \033[0m");
            // 输入之前先看有没有 lead 邮箱消息
            if (BUS.peek("lead")) {
                AGENT_LOCK.lock();
                try {
                    List<Map<String, Object>> inbox = consumeLeadInbox();
                    if (!inbox.isEmpty()) {
                        history.add(MessageParam.builder().role(MessageParam.Role.USER)
                                .content(formatTeamEvents(inbox)).build());
                        System.out.println("\n[wake: " + inbox.size() + " team event(s)]");
                        Message r = agentLoop(history, "team events");
                        if (r != null) for (ContentBlock cb : r.content())
                            cb.text().ifPresent(t -> System.out.println(t.text()));
                        System.out.println();
                    }
                } finally { AGENT_LOCK.unlock(); }
                continue;
            }
            if (!USER_INPUT.hasNextLine()) break;
            String q = USER_INPUT.nextLine().trim();
            if (q.isEmpty() || q.equalsIgnoreCase("q") || q.equalsIgnoreCase("exit")) break;

            // (前置) [s04] UserPromptSubmit hook
            System.out.println("\033[90m[hook] UserPromptSubmit: " + q.substring(0, Math.min(40, q.length())) + "\033[0m");

            AGENT_LOCK.lock();
            try {
                history.add(MessageParam.builder().role(MessageParam.Role.USER).content(q).build());
                Message r = agentLoop(history, q);
                if (r != null) for (ContentBlock cb : r.content())
                    cb.text().ifPresent(t -> System.out.println(t.text()));
                System.out.println();
            } finally { AGENT_LOCK.unlock(); }
        }
        CRON_STOP.set(true);
        System.out.println("[shutting down]");
    }
}
