package com.learn.cc.s12_cron_scheduler;

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
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * s12_cron_scheduler - 主实现
 *
 * s11 → s12 的核心变化: 【定时任务能力】
 * 跟 s11 background_tasks 是【独立的两个方向】:
 *   s11: 命令后台跑, 主动扔到后台线程, 完成后回喂
 *   s12: prompt 定时触发, 到点自动作为新的 user 消息启动 agent loop
 *
 * 心智模型:
 *   模型调 schedule_cron("0 9 * * *", "run tests") 挂一个定时 prompt
 *   后台 scheduler 线程每秒检查一次 → 到点 → 把 job 塞进 cron_queue
 *   queue processor 线程每 200ms 检查 → 抢到 agent_lock → 用 job.prompt 触发新一轮
 *
 * 6 个核心机制:
 *
 *   1) CronJob 数据结构
 *      id / cron / prompt / recurring / durable / pendingDelivery / lastFired
 *      recurring: 每次到点重复触发 (定时任务的常态)
 *      durable: 存到 .scheduled_tasks.json, 程序重启后加载
 *      pendingDelivery: 已到点但还没送达 agent, 崩溃恢复用
 *      lastFired: "2026-09-03 09:00" 分钟标记, 防同分钟重复触发
 *
 *   2) cron 表达式解析
 *      5 字段: minute hour day month weekday
 *      支持: 星号 / 星号斜杠N / a,b,c / a-b / 具体数字
 *      day + weekday 的 OR 语义 (POSIX cron 规范)
 *
 *   3) 3 个新工具
 *      schedule_cron / list_crons / cancel_cron
 *
 *   4) 两个后台线程 (daemon)
 *      scheduler: 每 1s 检查 datetime.now() 命中哪些 cron → 塞 cron_queue
 *      queue processor: 每 200ms 看 cron_queue, 抢 agent_lock 就跑一轮 agent
 *
 *   5) agent_lock 互斥
 *      用户输入 vs cron 触发不能同时跑 (共享 session_history)
 *      主线程用户输入: 阻塞 lock() 保证优先级
 *      cron processor: tryLock() 抢不到就下次
 *
 *   6) Ack + 恢复机制 (至少一次投递语义)
 *      consume_cron_queue: 取出待处理, 插入 messages
 *      成功响应 → acknowledge (recurring 清 pending, one-shot 删除)
 *      LLM 抛异常 → restore, 下次再试, 不丢任务
 *
 * ★ 关键并发问题: session_history 是共享状态,
 *   主线程和 cron 线程都可能修改, 用 agent_lock 严格互斥。
 *
 * 保留 s04 的 hook 系统 + JSON 打印。为独立可读, 不含 s09/s10/s11。
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
    private static final Path DURABLE_PATH = WORKDIR.resolve(".scheduled_tasks.json");
    private static final AnthropicClient CLIENT = buildClient();
    private static final Scanner USER_INPUT = new Scanner(System.in);
    private static final SecureRandom RNG = new SecureRandom();

    private static final String SYSTEM =
            "You are a coding agent at " + WORKDIR + ". Use tools to solve tasks. "
                    + "Use schedule_cron for work that should start at a future local time.";

    // ══════════════════════════════════════════════════════════════════
    //  ★ s12 核心 1: CronJob 数据 + 持久化
    // ══════════════════════════════════════════════════════════════════

    /**
     * CronJob 是可变对象 (pendingDelivery/lastFired 会被后台线程改)
     * 用普通 class 而不是 record, 因为 record 字段全 final 改不了。
     */
    static final class CronJob {
        String id;
        String cron;
        String prompt;
        boolean recurring;
        boolean durable;
        boolean pendingDelivery;
        String lastFired;   // "yyyy-MM-dd HH:mm" 或 null

        CronJob(String id, String cron, String prompt, boolean recurring, boolean durable) {
            this.id = id;
            this.cron = cron;
            this.prompt = prompt;
            this.recurring = recurring;
            this.durable = durable;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id); m.put("cron", cron); m.put("prompt", prompt);
            m.put("recurring", recurring); m.put("durable", durable);
            m.put("pending_delivery", pendingDelivery);
            m.put("last_fired", lastFired);
            return m;
        }

        static CronJob fromMap(Map<String, Object> m) {
            CronJob j = new CronJob(
                    String.valueOf(m.get("id")),
                    String.valueOf(m.get("cron")),
                    String.valueOf(m.get("prompt")),
                    Boolean.TRUE.equals(m.get("recurring")),
                    Boolean.TRUE.equals(m.get("durable")));
            j.pendingDelivery = Boolean.TRUE.equals(m.get("pending_delivery"));
            Object lf = m.get("last_fired");
            j.lastFired = lf == null || "null".equals(String.valueOf(lf)) ? null : String.valueOf(lf);
            return j;
        }
    }

    /** 所有 job 索引, 键是 job.id */
    private static final Map<String, CronJob> SCHEDULED_JOBS = new LinkedHashMap<>();
    /** 已到点待送达 agent 的队列 */
    private static final List<CronJob> CRON_QUEUE = new ArrayList<>();
    /** 保护 SCHEDULED_JOBS + CRON_QUEUE 的锁 */
    private static final ReentrantLock CRON_LOCK = new ReentrantLock();

    /** 生成一个不冲突的 cron id */
    private static String newCronId() {
        for (int i = 0; i < 100; i++) {
            byte[] bytes = new byte[4];
            RNG.nextBytes(bytes);
            StringBuilder hex = new StringBuilder("cron_");
            for (byte b : bytes) hex.append(String.format("%02x", b));
            String id = hex.toString();
            if (!SCHEDULED_JOBS.containsKey(id)) return id;
        }
        throw new RuntimeException("Could not allocate a cron job ID");
    }

    /** 原子写入持久化文件 (临时文件 + 原子重命名) */
    private static void saveDurableJobs() {
        CRON_LOCK.lock();
        try {
            List<Map<String, Object>> payload = new ArrayList<>();
            for (CronJob j : SCHEDULED_JOBS.values()) {
                if (j.durable) payload.add(j.toMap());
            }
            String json = JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(payload);
            Path tmp = DURABLE_PATH.resolveSibling(
                    DURABLE_PATH.getFileName() + "." + ProcessHandle.current().pid()
                    + "." + Thread.currentThread().getId() + ".tmp");
            try {
                Files.writeString(tmp, json);
                // ATOMIC_MOVE = POSIX rename(2), Windows 尽力而为
                Files.move(tmp, DURABLE_PATH, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignore) {}
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to save durable jobs: " + e.getMessage(), e);
        } finally { CRON_LOCK.unlock(); }
    }

    /** 启动时加载已持久化的 job */
    private static void loadDurableJobs() {
        if (!Files.exists(DURABLE_PATH)) return;
        try {
            String content = Files.readString(DURABLE_PATH);
            List<Map<String, Object>> payload = JSON_MAPPER.readValue(content, new TypeReference<>() {});
            int loaded = 0;
            CRON_LOCK.lock();
            try {
                for (Map<String, Object> item : payload) {
                    try {
                        CronJob j = CronJob.fromMap(item);
                        String err = validateCron(j.cron);
                        if (err != null) throw new IllegalArgumentException(err);
                        if (!j.id.startsWith("cron_")) throw new IllegalArgumentException("invalid job ID");
                        if (j.prompt == null || j.prompt.isBlank())
                            throw new IllegalArgumentException("prompt cannot be empty");
                        SCHEDULED_JOBS.put(j.id, j);
                        if (j.pendingDelivery) CRON_QUEUE.add(j);
                        loaded++;
                    } catch (Exception e) {
                        System.out.println("  [cron] skipped invalid saved job: " + e.getMessage());
                    }
                }
            } finally { CRON_LOCK.unlock(); }
            if (loaded > 0) System.out.println("  [cron] loaded " + loaded + " durable job(s)");
        } catch (Exception e) {
            System.out.println("  [cron] could not load " + DURABLE_PATH.getFileName() + ": " + e.getMessage());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s12 核心 2: cron 表达式解析
    // ══════════════════════════════════════════════════════════════════

    /**
     * 单个字段是否匹配某个值: 支持 星号 / N / 星号斜杠step / a,b,c / a-b
     * 例: fieldMatches("[star]/15", 0/15/30/45) 为 true; fieldMatches("[star]/15", 10) 为 false
     * (注释里避免使用 星号斜杠 因为会提前关闭多行注释)
     */
    private static boolean fieldMatches(String field, int value) {
        if ("*".equals(field)) return true;
        if (field.startsWith("*/")) return value % Integer.parseInt(field.substring(2)) == 0;
        if (field.contains(",")) {
            for (String part : field.split(",")) {
                if (fieldMatches(part.trim(), value)) return true;
            }
            return false;
        }
        if (field.contains("-")) {
            String[] pair = field.split("-", 2);
            int start = Integer.parseInt(pair[0]), end = Integer.parseInt(pair[1]);
            return start <= value && value <= end;
        }
        return value == Integer.parseInt(field);
    }

    /** 5 字段 cron 是否命中某个时刻 */
    private static boolean cronMatches(String cronExpr, LocalDateTime moment) {
        String[] fields = cronExpr.strip().split("\\s+");
        if (fields.length != 5) return false;
        String minute = fields[0], hour = fields[1], day = fields[2],
               month = fields[3], weekday = fields[4];

        // Python cron: 0=Sun, 1=Mon...6=Sat
        // Java DayOfWeek: MONDAY=1 ... SUNDAY=7
        // 转换: (java DayOfWeek % 7) → 0=Sun,1=Mon,...,6=Sat
        int cronWeekday = moment.getDayOfWeek().getValue() % 7;

        if (!fieldMatches(minute, moment.getMinute())) return false;
        if (!fieldMatches(hour, moment.getHour())) return false;
        if (!fieldMatches(month, moment.getMonthValue())) return false;

        boolean dayMatches = fieldMatches(day, moment.getDayOfMonth());
        boolean weekdayMatches = fieldMatches(weekday, cronWeekday);

        // POSIX cron: day 和 weekday 同时限制时用 OR
        if ("*".equals(day) && "*".equals(weekday)) return true;
        if ("*".equals(day)) return weekdayMatches;
        if ("*".equals(weekday)) return dayMatches;
        return dayMatches || weekdayMatches;
    }

    /** 校验一个字段, 返回 null 表示合法, 非 null 是错误信息 */
    private static String validateField(String field, int min, int max) {
        if ("*".equals(field)) return null;
        if (field.startsWith("*/")) {
            String step = field.substring(2);
            if (!step.matches("\\d+") || Integer.parseInt(step) <= 0) return "Invalid step: " + field;
            return null;
        }
        if (field.contains(",")) {
            for (String part : field.split(",")) {
                String err = validateField(part.trim(), min, max);
                if (err != null) return err;
            }
            return null;
        }
        if (field.contains("-")) {
            String[] pair = field.split("-", 2);
            if (!pair[0].matches("\\d+") || !pair[1].matches("\\d+")) return "Invalid range: " + field;
            int s = Integer.parseInt(pair[0]), e = Integer.parseInt(pair[1]);
            if (s > e) return "Range start > end: " + field;
            if (s < min || e > max) return "Range " + field + " outside [" + min + "-" + max + "]";
            return null;
        }
        if (!field.matches("\\d+")) return "Invalid field: " + field;
        int v = Integer.parseInt(field);
        if (v < min || v > max) return "Value " + v + " outside [" + min + "-" + max + "]";
        return null;
    }

    /** 校验整个 cron 表达式 */
    private static String validateCron(String cronExpr) {
        String[] fields = cronExpr.strip().split("\\s+");
        if (fields.length != 5) return "Expected 5 fields, got " + fields.length;
        String[] names = {"minute", "hour", "day-of-month", "month", "day-of-week"};
        int[][] ranges = {{0, 59}, {0, 23}, {1, 31}, {1, 12}, {0, 6}};
        for (int i = 0; i < 5; i++) {
            String err = validateField(fields[i], ranges[i][0], ranges[i][1]);
            if (err != null) return names[i] + ": " + err;
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s12 核心 3: schedule/cancel/enqueue
    // ══════════════════════════════════════════════════════════════════

    /** 挂新 job, 返回错误消息或成功的 job */
    private static Object scheduleJob(String cron, String prompt, boolean recurring, boolean durable) {
        String err = validateCron(cron);
        if (err != null) return err;
        if (prompt == null || prompt.isBlank()) return "Prompt cannot be empty";

        CRON_LOCK.lock();
        try {
            CronJob job = new CronJob(newCronId(), cron, prompt, recurring, durable);
            SCHEDULED_JOBS.put(job.id, job);
            try {
                if (durable) saveDurableJobs();
            } catch (Exception e) {
                SCHEDULED_JOBS.remove(job.id);
                throw e;
            }
            System.out.println("  [cron] scheduled " + job.id + ": " + cron + " -> "
                    + (prompt.length() > 60 ? prompt.substring(0, 60) : prompt));
            return job;
        } finally { CRON_LOCK.unlock(); }
    }

    /** 取消 job */
    private static String cancelJob(String jobId) {
        CRON_LOCK.lock();
        try {
            CronJob job = SCHEDULED_JOBS.get(jobId);
            if (job == null) return "Job " + jobId + " not found";

            List<CronJob> previousQueue = new ArrayList<>(CRON_QUEUE);
            SCHEDULED_JOBS.remove(jobId);
            CRON_QUEUE.removeIf(q -> q.id.equals(jobId));
            try {
                if (job.durable) saveDurableJobs();
            } catch (Exception e) {
                // 回滚
                SCHEDULED_JOBS.put(jobId, job);
                CRON_QUEUE.clear();
                CRON_QUEUE.addAll(previousQueue);
                throw e;
            }
            System.out.println("  [cron] cancelled " + jobId);
            return "Cancelled " + jobId;
        } finally { CRON_LOCK.unlock(); }
    }

    /**
     * 到点触发: 把 job 塞入 CRON_QUEUE, 设 pendingDelivery=true 并记录 lastFired。
     * 需要在 CRON_LOCK 保护下调用。
     */
    private static void enqueueDueJob(CronJob job, String minuteMarker) {
        boolean oldPending = job.pendingDelivery;
        String oldLastFired = job.lastFired;
        job.pendingDelivery = true;
        if (minuteMarker != null) job.lastFired = minuteMarker;
        try {
            if (job.durable) saveDurableJobs();
        } catch (Exception e) {
            job.pendingDelivery = oldPending;
            job.lastFired = oldLastFired;
            throw e;
        }
        CRON_QUEUE.add(job);
    }

    /** 每秒被 scheduler 线程调用一次: 找出所有到点的 job 塞入队列 */
    private static void pollDueJobs(LocalDateTime moment) {
        String minuteMarker = moment.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        CRON_LOCK.lock();
        try {
            for (CronJob job : new ArrayList<>(SCHEDULED_JOBS.values())) {
                try {
                    // 已 pending 或本分钟已 fire 过 → 跳过 (防重)
                    if (job.pendingDelivery || minuteMarker.equals(job.lastFired)) continue;
                    if (cronMatches(job.cron, moment)) {
                        enqueueDueJob(job, minuteMarker);
                        System.out.println("  [cron] due " + job.id + ": "
                                + (job.prompt.length() > 60 ? job.prompt.substring(0, 60) : job.prompt));
                    }
                } catch (Exception e) {
                    System.out.println("  [cron] could not enqueue " + job.id + ": " + e.getMessage());
                }
            }
        } finally { CRON_LOCK.unlock(); }
    }

    /** 主循环取出所有待送达 job, 返回并清空队列 */
    private static List<CronJob> consumeCronQueue() {
        CRON_LOCK.lock();
        try {
            List<CronJob> out = new ArrayList<>(CRON_QUEUE);
            CRON_QUEUE.clear();
            return out;
        } finally { CRON_LOCK.unlock(); }
    }

    /**
     * LLM 成功响应后 ack: recurring 清 pending, one-shot 删除。
     * ack 失败(比如存盘失败) → 全部回滚, 下次重试。
     */
    private static void acknowledgeCronJobs(List<CronJob> jobs) {
        List<Object[]> changed = new ArrayList<>();  // (job, oldPending)
        List<CronJob> removed = new ArrayList<>();
        CRON_LOCK.lock();
        try {
            for (CronJob delivered : jobs) {
                CronJob current = SCHEDULED_JOBS.get(delivered.id);
                if (current == null) continue;
                changed.add(new Object[]{current, current.pendingDelivery});
                if (current.recurring) {
                    current.pendingDelivery = false;
                } else {
                    removed.add(current);
                    SCHEDULED_JOBS.remove(current.id);
                }
            }
            try {
                boolean anyDurable = false;
                for (Object[] c : changed) if (((CronJob) c[0]).durable) { anyDurable = true; break; }
                if (anyDurable) saveDurableJobs();
            } catch (Exception e) {
                // 回滚
                for (CronJob r : removed) SCHEDULED_JOBS.put(r.id, r);
                for (Object[] c : changed) ((CronJob) c[0]).pendingDelivery = (Boolean) c[1];
                Set<String> queuedIds = new java.util.HashSet<>();
                for (CronJob q : CRON_QUEUE) queuedIds.add(q.id);
                for (Object[] c : changed) {
                    CronJob j = (CronJob) c[0];
                    if (!queuedIds.contains(j.id)) CRON_QUEUE.add(j);
                }
                throw e;
            }
        } finally { CRON_LOCK.unlock(); }
    }

    /** LLM 失败时把 job 塞回队列, 下次重试 */
    private static void restoreCronJobs(List<CronJob> jobs) {
        CRON_LOCK.lock();
        try {
            Set<String> queuedIds = new java.util.HashSet<>();
            for (CronJob q : CRON_QUEUE) queuedIds.add(q.id);
            for (CronJob delivered : jobs) {
                CronJob current = SCHEDULED_JOBS.get(delivered.id);
                if (current == null) continue;
                current.pendingDelivery = true;
                if (!queuedIds.contains(current.id)) {
                    CRON_QUEUE.add(current);
                    queuedIds.add(current.id);
                }
            }
        } finally { CRON_LOCK.unlock(); }
    }

    private static boolean hasCronQueue() {
        CRON_LOCK.lock();
        try { return !CRON_QUEUE.isEmpty(); }
        finally { CRON_LOCK.unlock(); }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Tool handlers
    // ══════════════════════════════════════════════════════════════════

    private static String runScheduleCron(String cron, String prompt, Boolean recurring, Boolean durable) {
        boolean r = recurring == null ? true : recurring;
        boolean d = durable == null ? true : durable;
        Object result = scheduleJob(cron, prompt, r, d);
        if (result instanceof String err) return "Error: " + err;
        CronJob job = (CronJob) result;
        return "Scheduled " + job.id + ": " + cron + " -> " + prompt;
    }

    private static String runListCrons() {
        CRON_LOCK.lock();
        List<CronJob> jobs;
        try { jobs = new ArrayList<>(SCHEDULED_JOBS.values()); }
        finally { CRON_LOCK.unlock(); }
        if (jobs.isEmpty()) return "No cron jobs.";
        StringBuilder sb = new StringBuilder();
        for (CronJob j : jobs) {
            String freq = j.recurring ? "recurring" : "one-shot";
            String storage = j.durable ? "durable" : "session";
            String p = j.prompt.length() > 60 ? j.prompt.substring(0, 60) : j.prompt;
            sb.append(j.id).append(": ").append(j.cron).append(" -> ").append(p)
              .append(" [").append(freq).append(", ").append(storage).append("]\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String runCancelCron(String jobId) { return cancelJob(jobId); }

    // ── 基础 5 工具 ────────────────────────────────────────────────
    private static String runBash(String command) {
        try {
            Process p = new ProcessBuilder("bash", "-c", command)
                    .directory(new File(WORKDIR.toString())).redirectErrorStream(true).start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return "Error: Timeout (120s)"; }
            String out = new String(p.getInputStream().readAllBytes()).trim();
            int ec = p.exitValue();
            if (ec != 0) return "Error: command exited with status " + ec + "\n" + out;
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

    private static String dispatchTool(String name, Map<String, Object> input) {
        return switch (name) {
            case "bash"          -> runBash((String) input.get("command"));
            case "read_file"     -> runRead((String) input.get("path"), asInteger(input.get("limit")));
            case "write_file"    -> runWrite((String) input.get("path"), (String) input.get("content"));
            case "edit_file"     -> runEdit((String) input.get("path"),
                                            (String) input.get("old_text"),
                                            (String) input.get("new_text"));
            case "glob"          -> runGlob((String) input.get("pattern"));
            case "schedule_cron" -> runScheduleCron(
                                            (String) input.get("cron"),
                                            (String) input.get("prompt"),
                                            (Boolean) input.get("recurring"),
                                            (Boolean) input.get("durable"));
            case "list_crons"    -> runListCrons();
            case "cancel_cron"   -> runCancelCron((String) input.get("job_id"));
            default              -> "Unknown tool: " + name;
        };
    }

    private static Integer asInteger(Object v) { return (v instanceof Number n) ? n.intValue() : null; }

    // ── Tool definitions ─────────────────────────────────────────
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

            // ★ s12 新增 3 个工具
            tool("schedule_cron", "Schedule a prompt with a 5-field cron expression.",
                    Map.of("cron", Map.of("type", "string"),
                           "prompt", Map.of("type", "string"),
                           "recurring", Map.of("type", "boolean"),
                           "durable", Map.of("type", "boolean")),
                    List.of("cron", "prompt")),
            tool("list_crons", "List scheduled cron jobs.",
                    Map.of(), List.of()),
            tool("cancel_cron", "Cancel a cron job by ID.",
                    Map.of("job_id", Map.of("type", "string")), List.of("job_id"))
    );

    private AgentLoop() {}

    private static Tool tool(String name, String desc, Map<String, ?> props, List<String> required) {
        Tool.InputSchema.Builder schemaBuilder = Tool.InputSchema.builder()
                .properties(JsonValue.from(props));
        if (!required.isEmpty()) {
            schemaBuilder.putAdditionalProperty("required", JsonValue.from(required));
        }
        return Tool.builder().name(name).description(desc).inputSchema(schemaBuilder.build()).build();
    }

    private static AnthropicClient buildClient() {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder();
        if (API_KEY  != null && !API_KEY.isBlank())  b.apiKey(API_KEY);
        if (BASE_URL != null && !BASE_URL.isBlank()) b.baseUrl(BASE_URL);
        return b.build();
    }

    // ── Hooks (简化) ──────────────────────────────────────────
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
    //  ★ s12 核心 4: 两个后台线程 + 主循环互斥
    // ══════════════════════════════════════════════════════════════════

    /** 用户输入 vs cron 触发共享 SESSION_HISTORY, 互斥 */
    private static final ReentrantLock AGENT_LOCK = new ReentrantLock();
    private static final List<MessageParam> SESSION_HISTORY = new ArrayList<>();
    private static final AtomicBoolean RUNTIME_STOP = new AtomicBoolean(false);
    private static final List<Thread> RUNTIME_THREADS = new ArrayList<>();

    /** scheduler 线程: 每 1s 检查一次 */
    private static void cronSchedulerLoop() {
        while (!RUNTIME_STOP.get()) {
            try { Thread.sleep(1000); } catch (InterruptedException e) { break; }
            if (RUNTIME_STOP.get()) break;
            try { pollDueJobs(LocalDateTime.now()); }
            catch (Exception e) { /* keep going */ }
        }
    }

    /** queue processor 线程: 200ms 一次, 有队列 && 抢到 lock 就跑 agent */
    private static void queueProcessorLoop() {
        while (!RUNTIME_STOP.get()) {
            try { Thread.sleep(200); } catch (InterruptedException e) { break; }
            if (RUNTIME_STOP.get()) break;
            if (!hasCronQueue()) continue;
            if (AGENT_LOCK.tryLock()) {
                try {
                    if (hasCronQueue()) runAgentTurnLocked(null);
                } catch (Exception e) {
                    System.out.println("  [error] cron processor: " + e.getMessage());
                } finally {
                    AGENT_LOCK.unlock();
                }
            }
            // 抢不到 lock 就等下一次
        }
    }

    /**
     * 跑一次 agent turn (要求已经拿到 AGENT_LOCK)
     * user_query 非空 = 用户输入; null = 纯 cron 触发
     */
    private static void runAgentTurnLocked(String userQuery) {
        if (userQuery != null) {
            SESSION_HISTORY.add(MessageParam.builder()
                    .role(MessageParam.Role.USER).content(userQuery).build());
        }
        agentLoop(SESSION_HISTORY);
        printLatestAssistantText(SESSION_HISTORY);
        System.out.println();
    }

    /** 从 history 中打印最新的 assistant text */
    private static void printLatestAssistantText(List<MessageParam> history) {
        for (int i = history.size() - 1; i >= 0; i--) {
            MessageParam m = history.get(i);
            if (m.role() != MessageParam.Role.ASSISTANT) continue;
            var content = m.content();
            if (content.string().isPresent()) {
                System.out.println(content.string().get());
            } else if (content.blockParams().isPresent()) {
                for (ContentBlockParam b : content.blockParams().get()) {
                    b.text().ifPresent(t -> System.out.println(t.text()));
                }
            }
            return;
        }
    }

    private static void startRuntimeThreads() {
        loadDurableJobs();
        RUNTIME_STOP.set(false);
        Thread t1 = new Thread(AgentLoop::cronSchedulerLoop, "cron-scheduler");
        t1.setDaemon(true);
        Thread t2 = new Thread(AgentLoop::queueProcessorLoop, "cron-queue-processor");
        t2.setDaemon(true);
        RUNTIME_THREADS.add(t1);
        RUNTIME_THREADS.add(t2);
        t1.start();
        t2.start();
    }

    private static void stopRuntimeThreads() {
        RUNTIME_STOP.set(true);
        for (Thread t : RUNTIME_THREADS) {
            try { t.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        RUNTIME_THREADS.clear();
    }

    // ══════════════════════════════════════════════════════════════════
    //  Agent Loop (与 s09 一致的骨架 + cron 队列消费 + ack)
    // ══════════════════════════════════════════════════════════════════

    private static void agentLoop(List<MessageParam> messages) {
        // ★ 每次入循环先消费 cron 队列
        List<CronJob> fired = consumeCronQueue();
        int scheduledStart = messages.size();
        for (CronJob job : fired) {
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .content("[Scheduled] " + job.prompt)
                    .build());
            System.out.println("  [cron] delivered " + job.id + ": "
                    + (job.prompt.length() > 60 ? job.prompt.substring(0, 60) : job.prompt));
        }
        List<CronJob> waitingForAck = new ArrayList<>(fired);

        while (true) {
            MessageCreateParams.Builder pb = MessageCreateParams.builder()
                    .model(MODEL).system(SYSTEM).maxTokens(8000).temperature(0.3);
            TOOLS.forEach(pb::addTool);
            messages.forEach(pb::addMessage);
            MessageCreateParams params = pb.build();

            if (DEBUG_HTTP) System.out.println("\n\n===============>>>>>>>>>>");
            if (DEBUG_HTTP) System.out.println("request is : ");
            if (DEBUG_HTTP) System.out.println(prettyJson(params._body()));

            Message response;
            try {
                response = CLIENT.messages().create(params);
            } catch (Exception e) {
                // ★ LLM 失败: 回滚 messages + restore cron jobs
                if (!waitingForAck.isEmpty()) {
                    while (messages.size() > scheduledStart) messages.remove(messages.size() - 1);
                    restoreCronJobs(waitingForAck);
                }
                System.out.println("  [error] " + e.getClass().getSimpleName() + ": " + e.getMessage());
                return;
            }
            if (DEBUG_HTTP) System.out.println("response is : ");
            if (DEBUG_HTTP) System.out.println(prettyJson(response));

            messages.add(assistantMessageParam(response));

            // ★ LLM 成功 → ack
            if (!waitingForAck.isEmpty()) {
                try { acknowledgeCronJobs(waitingForAck); }
                catch (Exception e) { System.out.println("  [cron] ack failed: " + e.getMessage()); }
                waitingForAck.clear();
            }

            StopReason stop = response.stopReason().orElse(null);
            if (stop == null || !stop.equals(StopReason.TOOL_USE)) return;

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
            messages.add(MessageParam.builder()
                    .role(MessageParam.Role.USER).contentOfBlockParams(results).build());
        }
    }

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
        System.out.println("s12: Cron Scheduler — run prompts on a local schedule");
        System.out.println("Durable path: " + DURABLE_PATH);
        System.out.println("输入问题, 回车发送。输入 q 退出。\n");

        startRuntimeThreads();
        // JVM shutdown hook 兜底停后台线程
        Runtime.getRuntime().addShutdownHook(new Thread(AgentLoop::stopRuntimeThreads, "cron-shutdown"));

        try {
            while (true) {
                System.out.print("\033[36ms12v >> \033[0m");
                if (!USER_INPUT.hasNextLine()) break;
                String q = USER_INPUT.nextLine().trim();
                if (q.isEmpty() || q.equalsIgnoreCase("q") || q.equalsIgnoreCase("exit")) break;

                // ★ 主线程用户输入: 阻塞式获取 lock (跟 cron processor 互斥)
                AGENT_LOCK.lock();
                try {
                    runAgentTurnLocked(q);
                } finally {
                    AGENT_LOCK.unlock();
                }
            }
        } finally {
            stopRuntimeThreads();
        }
    }
}
