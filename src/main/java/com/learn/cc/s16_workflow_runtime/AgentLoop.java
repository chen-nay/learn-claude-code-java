package com.learn.cc.s16_workflow_runtime;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * s16_workflow_runtime - 主实现
 *
 * s15 → s16 的核心变化: 【引入 "Workflow" 工具 = 脚本编排运行时】
 *
 * ═══════════════════════════════════════════════════════════════════
 *  核心思想: "模型决定每一步; 脚本决定编排"
 * ═══════════════════════════════════════════════════════════════════
 *
 * s01~s15 的模式:
 *   模型看当前 messages → 决定下一个 tool_use → 结果进 messages → 循环
 *   适合"路径依赖上一步发现"的任务
 *
 * s16 的新模式:
 *   有些任务的"编排步骤是固定的" (如 code review 分维度并行 → 逐个验证 → 汇总)
 *   把这类固定编排【写成代码】, 而不是靠模型每轮临时决定
 *   模型只需一次 tool_use 调用 Workflow(name="review-changes"), 剩下是脚本跑
 *
 * 三大好处:
 *   1) 并发: parallel/pipeline 让多个 agent 同时跑, 不用一个个等
 *   2) 结构稳定: 结果 schema 固定, 不依赖模型每次凭 vibes 组织
 *   3) 可恢复: 中途断了 resume_from_run_id 从 journal 检查点续跑
 *
 * ═══════════════════════════════════════════════════════════════════
 *  6 大组件
 * ═══════════════════════════════════════════════════════════════════
 *
 *   1) WorkflowContext (ctx)
 *      注入到 workflow 脚本, 提供 4 个编排原语:
 *      - phase(title)              阶段标记, 影响 progress 显示
 *      - agent(prompt, schema)     启动一个 agent 调用 (可带 JSON schema 校验)
 *      - parallel(thunks)          并发跑所有 thunk, 全部完成才 return
 *      - pipeline(items, stages)   每个 item 独立按 stages 流水 (无 stage barrier)
 *
 *   2) WorkflowJournal
 *      append-only <runId>.journal.jsonl 磁盘日志
 *      resume 时: agent(prompt) 算 key → 找 cache 命中就 return, 不 re-run
 *
 *   3) WorkflowRunner interface
 *      抽象"实际跑一次 agent"的能力
 *      主实现给两个实现: MockRunner (演示) + AnthropicRunner (真调 API)
 *
 *   4) Budget
 *      token 上限守卫
 *
 *   5) WORKFLOWS 注册表 (host 硬编码)
 *      Map<String, RegisteredWorkflow>  --  name → (meta, script fn)
 *      ★ 关键安全: 模型只传 name/args, 不能传可执行代码
 *
 *   6) Workflow 工具
 *      模型看到: {name, args, resume_from_run_id}
 *      dispatch 到 host 注册表, 跑 WorkflowTool.call()
 *
 * ═══════════════════════════════════════════════════════════════════
 *  Java 相比 Python 的翻译差异
 * ═══════════════════════════════════════════════════════════════════
 *
 * Python 用 async/await + asyncio.gather 天然并发
 * Java 17 没原生 async/await, 用 CompletableFuture 表达:
 *   agent(...)   -> CompletableFuture<Object>
 *   parallel(...) -> CompletableFuture.allOf(...).thenApply(...)
 *   pipeline(...) -> 每个 item 一个 CF 链, allOf 汇总
 * 用 ExecutorService (固定线程池) 跑 agent
 * Semaphore 限并发上限
 *
 * 保留主 agent loop (s02+s03+s04 最小骨架) + 新的 Workflow 工具。
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
    private static final Path RUNTIME_STORE = WORKDIR.resolve(".runtime");
    private static final AnthropicClient CLIENT = buildClient();
    private static final Scanner USER_INPUT = new Scanner(System.in);
    private static final SecureRandom RNG = new SecureRandom();

    /** 全局并发上限 (agent 同时跑的最大数) 和 agent cap */
    private static final int CONCURRENCY = 4;
    private static final int AGENT_CAP = 32;
    /** workflow 内部用的线程池 */
    private static final ExecutorService WORKFLOW_POOL =
            Executors.newFixedThreadPool(CONCURRENCY,
                    r -> { Thread t = new Thread(r, "wf-worker"); t.setDaemon(true); return t; });

    // ══════════════════════════════════════════════════════════════════
    //  ★ s16 核心 1: WorkflowJournal - append-only 日志, 支持 resume
    // ══════════════════════════════════════════════════════════════════

    static final class WorkflowJournal {
        private final Path path;
        private final boolean resume;
        private final Map<String, Object> cache = new LinkedHashMap<>();

        WorkflowJournal(String runId, boolean resume) throws IOException {
            Files.createDirectories(RUNTIME_STORE);
            this.path = RUNTIME_STORE.resolve(runId + ".journal.jsonl");
            this.resume = resume;
            if (resume) {
                if (!Files.exists(path))
                    throw new IllegalArgumentException("resume journal not found for " + runId);
                for (String line : Files.readAllLines(path)) {
                    if (line.isBlank()) continue;
                    try {
                        Map<String, Object> rec = JSON_MAPPER.readValue(line, new TypeReference<>() {});
                        String key = (String) rec.get("key");
                        Object value = rec.get("value");
                        if (key != null) cache.put(key, value);
                    } catch (Exception e) { /* skip bad line */ }
                }
            } else {
                // 新 run: truncate
                Files.writeString(path, "");
            }
        }

        /** 语义 key: 基于 kind+label+prompt+schema, 不依赖并发顺序 */
        String key(String kind, String label, String prompt, Object schema) {
            String basis;
            try {
                basis = kind + "|" + label + "|" + prompt + "|"
                        + (schema == null ? "null" : JSON_MAPPER.writeValueAsString(schema));
            } catch (Exception e) { basis = kind + "|" + label + "|" + prompt; }
            long h = stableHash(basis) % 10_000_000_000L;
            if (h < 0) h += 10_000_000_000L;
            return kind + "-" + String.format("%010d", h);
        }

        private static long stableHash(String s) {
            long h = 1125899906842597L;
            for (int i = 0; i < s.length(); i++) h = 31L * h + s.charAt(i);
            return Math.abs(h);
        }

        Object cached(String key) {
            return cache.getOrDefault(key, MISS);
        }

        void record(String key, Object value) throws IOException {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("key", key); rec.put("value", value);
            Files.writeString(path, JSON_MAPPER.writeValueAsString(rec) + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            cache.put(key, value);
        }

        /** 表示"没命中"的哨兵值 */
        static final Object MISS = new Object();
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s16 核心 2: WorkflowRunner - 抽象 agent 执行
    // ══════════════════════════════════════════════════════════════════

    /** 一次 agent 调用的结果 */
    public record RunnerOutput(Object value, int tokens) {}

    /** WorkflowRunner: 实际跑一次 agent 调用的抽象 */
    public interface WorkflowRunner {
        /** 阻塞式 run, 返回 (value, tokens_used) */
        RunnerOutput run(String prompt, Object schema, String label);
    }

    /** Mock runner: 不调 LLM, 返回演示数据。方便开发 workflow 时快速迭代 */
    static final class MockRunner implements WorkflowRunner {
        @Override
        public RunnerOutput run(String prompt, Object schema, String label) {
            // 简单根据 label 前缀返回 mock 数据 (对齐 sample workflow)
            if (label.startsWith("audit:")) {
                String dimension = label.substring("audit:".length());
                // 每个维度报 2 条 finding
                List<Map<String, Object>> findings = List.of(
                        Map.of("title", "[mock] " + dimension + " issue A", "severity", "medium"),
                        Map.of("title", "[mock] " + dimension + " issue B", "severity", "low")
                );
                return new RunnerOutput(Map.of("findings", findings), 100);
            }
            if (label.startsWith("verify:")) {
                // 大部分 finding 确认, 少数拒绝 (奇偶数)
                boolean isReal = (label.hashCode() & 1) == 0;
                return new RunnerOutput(Map.of("isReal", isReal,
                        "reason", "[mock] verifier " + (isReal ? "confirmed" : "rejected")), 50);
            }
            return new RunnerOutput("[mock] result for " + label, 30);
        }
    }

    /** 真调 Anthropic 的 runner */
    static final class AnthropicRunner implements WorkflowRunner {
        @Override
        public RunnerOutput run(String prompt, Object schema, String label) {
            try {
                String system = "You are a specialized worker agent. "
                        + (schema != null ? "Return ONLY valid JSON matching the required schema. " : "")
                        + "Label: " + label;
                MessageCreateParams params = MessageCreateParams.builder()
                        .model(MODEL).system(system).maxTokens(1000).temperature(0.3)
                        .addUserMessage(prompt).build();
                Message resp = CLIENT.messages().create(params);
                String text = resp.content().stream().flatMap(cb -> cb.text().stream())
                        .map(TextBlock::text).reduce("", (a, b) -> a + b);
                Object value = text;
                if (schema != null) {
                    // 尝试从文本里抠出 JSON
                    try {
                        int i = text.indexOf('{');
                        int j = text.lastIndexOf('}');
                        if (i >= 0 && j > i) {
                            value = JSON_MAPPER.readValue(text.substring(i, j + 1), new TypeReference<Object>() {});
                        }
                    } catch (Exception ignore) {}
                }
                int tokens = (int) (resp.usage().inputTokens() + resp.usage().outputTokens());
                return new RunnerOutput(value, tokens);
            } catch (Exception e) {
                return new RunnerOutput("Error: " + e.getMessage(), 0);
            }
        }
    }

    /** 默认用 Mock runner (安全, 无成本), 想真跑改成 AnthropicRunner */
    private static final Supplier<WorkflowRunner> RUNNER_FACTORY = MockRunner::new;

    // ══════════════════════════════════════════════════════════════════
    //  ★ s16 核心 3: Budget - token 预算
    // ══════════════════════════════════════════════════════════════════

    static final class Budget {
        private final Integer total;
        private int spent = 0;
        Budget(Integer total) { this.total = total; }
        void add(int n) {
            if (total != null && spent + n > total)
                throw new IllegalStateException("token budget exceeded (" + (spent + n) + " > " + total + ")");
            spent += n;
        }
        int spent() { return spent; }
        int remaining() { return total == null ? Integer.MAX_VALUE : Math.max(0, total - spent); }
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s16 核心 4: WorkflowTask - lifecycle + progress events
    // ══════════════════════════════════════════════════════════════════

    static final class WorkflowTask {
        final String taskId;
        final String runId;
        final Map<String, Object> meta;
        String status = "running";
        final Map<String, Integer> usage = new LinkedHashMap<>();
        final List<Map<String, Object>> progress = new ArrayList<>();

        WorkflowTask(String taskId, String runId, Map<String, Object> meta) {
            this.taskId = taskId; this.runId = runId; this.meta = meta;
            usage.put("agents", 0); usage.put("tokens", 0);
        }

        void event(String name, Map<String, Object> data) {
            StringBuilder sb = new StringBuilder();
            data.forEach((k, v) -> sb.append(k).append("=").append(v).append(" "));
            System.out.println("  event      " + String.format("%-18s", name) + sb.toString().trim());
        }

        void progressEvent(String type, Map<String, Object> data) {
            Map<String, Object> rec = new LinkedHashMap<>();
            rec.put("type", type); rec.putAll(data);
            progress.add(rec);
            StringBuilder sb = new StringBuilder();
            data.forEach((k, v) -> sb.append(k).append("=").append(v).append(" "));
            System.out.println("  progress   " + String.format("%-16s", type) + sb.toString().trim());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s16 核心 5: WorkflowContext - 注入到脚本的编排原语
    // ══════════════════════════════════════════════════════════════════

    /** workflow 脚本的类型: (ctx, args) -> CompletableFuture<result> */
    @FunctionalInterface
    public interface WorkflowScript
            extends BiFunction<WorkflowContext, Map<String, Object>, CompletableFuture<Object>> {}

    /** pipeline 的 stage: (prevValue, originalItem, index) -> CompletableFuture<newValue> */
    @FunctionalInterface
    public interface PipelineStage {
        CompletableFuture<Object> apply(Object prevValue, Object originalItem, int index);
    }

    static final class ExecutionLimits {
        private final AtomicInteger agents = new AtomicInteger(0);
        private final Semaphore semaphore = new Semaphore(CONCURRENCY);
        void claimAgent() {
            if (agents.incrementAndGet() > AGENT_CAP)
                throw new IllegalStateException("agent() cap reached (" + AGENT_CAP + ")");
        }
    }

    public static final class WorkflowContext {
        final WorkflowTask task;
        final WorkflowJournal journal;
        final WorkflowRunner runner;
        final Budget budget;
        final Map<String, Object> args;
        private final ExecutionLimits limits;
        private String currentPhase = null;

        WorkflowContext(WorkflowTask t, WorkflowJournal j, WorkflowRunner r,
                        Budget b, Map<String, Object> args, ExecutionLimits lim) {
            this.task = t; this.journal = j; this.runner = r;
            this.budget = b; this.args = args;
            this.limits = lim == null ? new ExecutionLimits() : lim;
        }

        /** 原语 1: phase - 开始一个阶段 */
        public void phase(String title) {
            currentPhase = title;
            task.progressEvent("workflow_phase", Map.of("title", title));
        }

        /** 原语 2: log - 写一条进度日志 */
        public void log(String message) {
            task.progressEvent("workflow_log", Map.of("message", message));
        }

        /**
         * 原语 3: agent - 启动一个 subagent 调用
         *   - 有 journal cache 命中就返回 cached (resume 关键)
         *   - 用 semaphore 限并发
         *   - schema 校验略过(教学简化), Python 版会 retry once
         */
        public CompletableFuture<Object> agent(String prompt, Object schema, String label) {
            String finalLabel = label != null ? label : (prompt.length() > 24 ? prompt.substring(0, 24) + "..." : prompt);
            limits.claimAgent();
            if (budget.remaining() <= 0) {
                return CompletableFuture.failedFuture(new IllegalStateException("budget exceeded"));
            }
            String key = journal.key("agent", finalLabel, prompt, schema);
            Object cached = journal.cached(key);
            if (cached != WorkflowJournal.MISS) {
                task.progressEvent("workflow_agent", Map.of(
                        "label", finalLabel,
                        "phase", currentPhase == null ? "-" : currentPhase,
                        "status", "cached"));
                return CompletableFuture.completedFuture(cached);
            }
            return CompletableFuture.supplyAsync(() -> {
                try {
                    limits.semaphore.acquire();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(e);
                }
                try {
                    RunnerOutput out = runner.run(prompt, schema, finalLabel);
                    budget.add(out.tokens());
                    task.usage.merge("agents", 1, Integer::sum);
                    task.usage.merge("tokens", out.tokens(), Integer::sum);
                    try { journal.record(key, out.value()); } catch (IOException ignore) {}
                    task.progressEvent("workflow_agent", Map.of(
                            "label", finalLabel,
                            "phase", currentPhase == null ? "-" : currentPhase,
                            "status", "done"));
                    return out.value();
                } finally {
                    limits.semaphore.release();
                }
            }, WORKFLOW_POOL);
        }

        /**
         * 原语 4: parallel - 并发跑所有 thunk, 全部完成才 return
         *  BARRIER: 任一失败整个失败
         */
        public CompletableFuture<List<Object>> parallel(List<Supplier<CompletableFuture<Object>>> thunks) {
            List<CompletableFuture<Object>> futures = new ArrayList<>();
            for (Supplier<CompletableFuture<Object>> t : thunks) futures.add(t.get());
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> futures.stream().map(CompletableFuture::join).toList());
        }

        /**
         * 原语 5: pipeline - 每个 item 独立按 stages 流水
         *  NO barrier between stages: item A 可以在 stage 3 时 item B 还在 stage 1
         */
        public <T> CompletableFuture<List<Object>> pipeline(List<T> items, List<PipelineStage> stages) {
            List<CompletableFuture<Object>> perItem = new ArrayList<>();
            for (int i = 0; i < items.size(); i++) {
                final int idx = i;
                final T item = items.get(i);
                CompletableFuture<Object> chain = CompletableFuture.completedFuture((Object) item);
                for (PipelineStage stage : stages) {
                    chain = chain.thenCompose(prev -> stage.apply(prev, item, idx));
                }
                perItem.add(chain);
            }
            return CompletableFuture.allOf(perItem.toArray(new CompletableFuture[0]))
                    .thenApply(v -> perItem.stream().map(CompletableFuture::join).toList());
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s16 核心 6: WORKFLOWS 注册表 + Workflow 工具
    // ══════════════════════════════════════════════════════════════════

    public record RegisteredWorkflow(Map<String, Object> meta, WorkflowScript script) {}

    /** ★ host 硬编码的可信 workflow 注册表 */
    private static final Map<String, RegisteredWorkflow> WORKFLOWS = new LinkedHashMap<>();

    static {
        // 注册 sample workflow: review-changes
        Map<String, Object> meta = Map.of(
                "name", "review-changes",
                "description", "Review changed files across dimensions, verify each finding",
                "phases", List.of("Review", "Verify"));
        WORKFLOWS.put("review-changes", new RegisteredWorkflow(meta, AgentLoop::sampleWorkflowReview));
    }

    /** meta 校验: name 合法 (类似 s12 cron 校验) */
    private static final Pattern SAFE_NAME = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

    private static void validateMeta(Map<String, Object> meta) {
        String name = String.valueOf(meta.get("name"));
        if (!SAFE_NAME.matcher(name).matches())
            throw new IllegalArgumentException("meta.name must be 1-64 chars [A-Za-z0-9._-]");
        if (!(meta.get("description") instanceof String))
            throw new IllegalArgumentException("meta.description must be a string");
    }

    /** 生成 run_id / task_id */
    private static String createRunId(Map<String, Object> meta) {
        String name = String.valueOf(meta.get("name"));
        return "run_" + name + "_" + System.currentTimeMillis() + "_" + RNG.nextInt(0x1000000);
    }

    private static String createTaskId(String runId) {
        return "task_" + runId.hashCode() + "_" + RNG.nextInt(0x1000);
    }

    /**
     * ★ WorkflowTool.call: workflow 执行的完整生命周期
     * 校验 meta → 分配 runId → 创建 journal (resume 与否) → 跑 script → 写 output
     * 全过程发 event/progress 事件
     */
    private static Map<String, Object> workflowCall(Map<String, Object> meta, WorkflowScript scriptFn,
                                                    Map<String, Object> args, String resumeFromRunId) throws IOException {
        validateMeta(meta);
        boolean resuming = resumeFromRunId != null;
        String runId = resuming ? resumeFromRunId : createRunId(meta);
        WorkflowJournal journal = new WorkflowJournal(runId, resuming);
        String taskId = createTaskId(runId);

        WorkflowTask task = new WorkflowTask(taskId, runId, meta);
        Map<String, Object> launched = Map.of(
                "status", "async_launched",
                "taskId", taskId,
                "taskType", "local_workflow",
                "runId", runId,
                "workflowName", meta.get("name"));
        task.event("async_launched", Map.of("runId", runId, "taskId", taskId));
        task.event("task_started", Map.of(
                "workflow", meta.get("name"),
                "phases", String.join(",", (List<String>) meta.getOrDefault("phases", List.of())),
                "resume", resuming));

        writeJson(RUNTIME_STORE.resolve(runId + ".json"), Map.of(
                "runId", runId,
                "workflowName", meta.get("name"),
                "args", args == null ? Map.of() : args,
                "task", serializeTask(task)));

        Object result;
        WorkflowContext ctx = new WorkflowContext(task, journal,
                RUNNER_FACTORY.get(), new Budget((Integer) (args == null ? null : args.get("budget"))),
                args == null ? Map.of() : args, new ExecutionLimits());
        try {
            result = scriptFn.apply(ctx, args == null ? Map.of() : args).get();
            task.status = "completed";
        } catch (Exception e) {
            task.status = "failed";
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            result = Map.of("error", cause.getMessage() == null ? cause.toString() : cause.getMessage());
        }

        writeJson(RUNTIME_STORE.resolve(runId + ".output.json"), result);
        writeJson(RUNTIME_STORE.resolve(runId + ".json"), Map.of(
                "runId", runId,
                "workflowName", meta.get("name"),
                "args", args == null ? Map.of() : args,
                "task", serializeTask(task)));

        task.event("task_notification", Map.of(
                "status", task.status,
                "agents", task.usage.get("agents"),
                "tokens", task.usage.get("tokens"),
                "outputFile", ".runtime/" + runId + ".output.json"));

        Map<String, Object> ret = new LinkedHashMap<>();
        ret.put("launched", launched);
        ret.put("result", result);
        ret.put("task", serializeTask(task));
        return ret;
    }

    private static Map<String, Object> serializeTask(WorkflowTask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("taskId", t.taskId); m.put("taskType", "local_workflow");
        m.put("runId", t.runId); m.put("workflowName", t.meta.get("name"));
        m.put("status", t.status); m.put("usage", new LinkedHashMap<>(t.usage));
        m.put("progress", new ArrayList<>(t.progress));
        return m;
    }

    private static void writeJson(Path path, Object value) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    /** 模型面对的 Workflow 工具入口 */
    @SuppressWarnings("unchecked")
    private static String runWorkflow(String name, Object argsObj, String resumeFromRunId) {
        if (name == null) return "Error: workflow name required";
        RegisteredWorkflow reg = WORKFLOWS.get(name);
        if (reg == null) return "Error: unknown workflow '" + name + "'. Available: "
                + String.join(", ", WORKFLOWS.keySet());
        Map<String, Object> args = (argsObj instanceof Map<?, ?> m) ? new LinkedHashMap<>((Map<String, Object>) m) : Map.of();
        try {
            Map<String, Object> out = workflowCall(reg.meta(), reg.script(), args, resumeFromRunId);
            return JSON_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(out);
        } catch (Exception e) {
            return "Error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ Sample workflow: review-changes
    //   pipeline(维度, audit -> verify): 每个维度独立 audit → 逐 finding 并发 verify
    // ══════════════════════════════════════════════════════════════════

    private static final Object FINDINGS_SCHEMA = Map.of(
            "type", "object", "required", List.of("findings"),
            "properties", Map.of("findings", Map.of(
                    "type", "array", "items", Map.of(
                            "type", "object", "required", List.of("title", "severity"),
                            "properties", Map.of(
                                    "title", Map.of("type", "string"),
                                    "severity", Map.of("type", "string",
                                            "enum", List.of("high", "medium", "low")))))));

    private static final Object VERDICT_SCHEMA = Map.of(
            "type", "object", "required", List.of("isReal", "reason"),
            "properties", Map.of("isReal", Map.of("type", "boolean"),
                    "reason", Map.of("type", "string")));

    private static final List<String> DIMENSIONS = List.of("correctness", "security", "performance", "style");

    /** ★ workflow 脚本本体: 用 ctx 的编排原语 */
    @SuppressWarnings("unchecked")
    private static CompletableFuture<Object> sampleWorkflowReview(WorkflowContext ctx, Map<String, Object> args) {
        ctx.phase("Review");
        String changes = String.valueOf(args.getOrDefault("changes", "")).trim();
        String reviewInput = changes.isEmpty() ? "No change context was supplied." : changes;

        // audit stage: 每个维度独立 audit, 返回 {dimension, findings}
        PipelineStage audit = (prev, dimObj, idx) -> {
            String dimension = (String) dimObj;
            return ctx.agent(
                    "Review this change context for " + dimension + " issues. "
                            + "Report only issues supported by the supplied text.\n\n" + reviewInput,
                    FINDINGS_SCHEMA, "audit:" + dimension
            ).thenApply(out -> {
                Map<String, Object> auditResult = (Map<String, Object>) out;
                Map<String, Object> ret = new LinkedHashMap<>();
                ret.put("dimension", dimension);
                ret.put("findings", auditResult.getOrDefault("findings", List.of()));
                return ret;
            });
        };

        // verify stage: 每个 finding 一个并发验证, 只留 isReal=true 的
        PipelineStage verify = (prev, dimObj, idx) -> {
            String dimension = (String) dimObj;
            Map<String, Object> audited = (Map<String, Object>) prev;
            List<Map<String, Object>> findings = (List<Map<String, Object>>) audited.get("findings");

            ctx.phase("Verify");
            List<Supplier<CompletableFuture<Object>>> thunks = new ArrayList<>();
            for (Map<String, Object> f : findings) {
                thunks.add(() -> ctx.agent(
                        "Adversarially verify this " + dimension + " finding against the "
                                + "supplied change context.\n\nChange context:\n" + reviewInput
                                + "\n\nFinding:\n" + toJson(f),
                        VERDICT_SCHEMA, "verify:" + dimension + ":" + f.get("title")
                ));
            }
            return ctx.parallel(thunks).thenApply(verdicts -> {
                List<Map<String, Object>> confirmed = new ArrayList<>();
                for (int i = 0; i < findings.size(); i++) {
                    Object v = verdicts.get(i);
                    if (v instanceof Map<?, ?> vm && Boolean.TRUE.equals(vm.get("isReal"))) {
                        confirmed.add(findings.get(i));
                    }
                }
                Map<String, Object> ret = new LinkedHashMap<>();
                ret.put("dimension", dimension);
                ret.put("confirmed", confirmed);
                return ret;
            });
        };

        return ctx.pipeline(DIMENSIONS, List.of(audit, verify)).thenApply(results -> {
            List<Map<String, Object>> confirmed = new ArrayList<>();
            for (Object r : results) {
                Map<String, Object> rr = (Map<String, Object>) r;
                List<Map<String, Object>> cs = (List<Map<String, Object>>) rr.get("confirmed");
                for (Map<String, Object> f : cs) {
                    Map<String, Object> annotated = new LinkedHashMap<>(f);
                    annotated.put("dimension", rr.get("dimension"));
                    confirmed.add(annotated);
                }
            }
            // 按 severity 排序 (high > medium > low)
            confirmed.sort((a, b) -> {
                Map<String, Integer> order = Map.of("high", 0, "medium", 1, "low", 2);
                int oa = order.getOrDefault(String.valueOf(a.get("severity")), 3);
                int ob = order.getOrDefault(String.valueOf(b.get("severity")), 3);
                return Integer.compare(oa, ob);
            });
            ctx.log("confirmed " + confirmed.size() + " real finding(s)");
            return (Object) Map.of("confirmed", confirmed);
        });
    }

    private static String toJson(Object o) {
        try { return JSON_MAPPER.writeValueAsString(o); } catch (Exception e) { return String.valueOf(o); }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Main agent loop (最简, 只装 Workflow + 少量基础工具)
    // ══════════════════════════════════════════════════════════════════

    private static final String SYSTEM =
            "You are a coding agent at " + WORKDIR + ".\n"
                    + "You have access to a Workflow tool that runs saved scripts for orchestrated multi-agent tasks.\n"
                    + "Available saved workflows: " + String.join(", ", WORKFLOWS.keySet()) + "\n"
                    + "Use Workflow(name='review-changes', args={'changes': '...'}) to trigger a code review workflow.\n"
                    + "You can also pass resume_from_run_id to resume a previous run from journal.";

    private static final List<Tool> TOOLS = List.of(
            tool("Workflow", "Run a saved workflow by name. Pass input in args.",
                    Map.of("name", Map.of("type", "string"),
                           "args", Map.of("type", "object"),
                           "resume_from_run_id", Map.of("type", "string")),
                    List.of("name")),
            tool("list_workflows", "List available saved workflows.", Map.of(), List.of())
    );

    private static String runListWorkflows() {
        StringBuilder sb = new StringBuilder("Available workflows:\n");
        for (RegisteredWorkflow rw : WORKFLOWS.values()) {
            sb.append("- ").append(rw.meta().get("name")).append(": ")
              .append(rw.meta().get("description")).append("\n");
            Object phases = rw.meta().get("phases");
            if (phases != null) sb.append("  phases: ").append(phases).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private AgentLoop() {}

    private static Tool tool(String name, String desc, Map<String, ?> props, List<String> required) {
        Tool.InputSchema.Builder isb = Tool.InputSchema.builder().properties(JsonValue.from(props));
        if (!required.isEmpty()) isb.putAdditionalProperty("required", JsonValue.from(required));
        return Tool.builder().name(name).description(desc).inputSchema(isb.build()).build();
    }

    private static AnthropicClient buildClient() {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder();
        if (API_KEY  != null && !API_KEY.isBlank())  b.apiKey(API_KEY);
        if (BASE_URL != null && !BASE_URL.isBlank()) b.baseUrl(BASE_URL);
        return b.build();
    }

    private static String dispatchTool(String name, Map<String, Object> input) {
        return switch (name) {
            case "Workflow" -> runWorkflow(
                    (String) input.get("name"),
                    input.get("args"),
                    (String) input.get("resume_from_run_id"));
            case "list_workflows" -> runListWorkflows();
            default -> "Unknown tool: " + name;
        };
    }

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
                Map<String, Object> input = JSON_MAPPER.convertValue(tb._input(), new TypeReference<>() {});
                System.out.println("\033[36m> " + tb.name() + "\033[0m");
                String output = dispatchTool(tb.name(), input);
                System.out.println(output.length() > 500 ? output.substring(0, 500) + "..." : output);
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(tb.id()).content(output).build()));
            }
            paramsBuilder.addMessage(MessageParam.builder().role(MessageParam.Role.USER)
                    .contentOfBlockParams(results).build());
        }
    }

    private static final JsonMapper JSON_MAPPER = ObjectMappers.jsonMapper()
            .rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();

    private static String prettyJson(Object obj) {
        try { return JSON_MAPPER.writeValueAsString(obj); }
        catch (JsonProcessingException e) { return "<serialize error: " + e.getMessage() + ">"; }
    }

    public static void main(String[] args) {
        if (MODEL == null || MODEL.isBlank()) { System.err.println("MODEL_ID missing"); System.exit(1); }

        System.out.println("s16: Workflow Runtime");
        System.out.println("Runner: MockRunner (演示,不实际调 LLM 做 sub-agent)");
        System.out.println("Registered workflows: " + WORKFLOWS.keySet());
        System.out.println("Store: " + RUNTIME_STORE);
        System.out.println("输入问题, 回车发送。输入 q 退出。");
        System.out.println("试试: 'Run the review-changes workflow' 或 'list workflows'\n");

        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(MODEL).system(SYSTEM).maxTokens(8000).temperature(0.3);
        TOOLS.forEach(paramsBuilder::addTool);

        while (true) {
            System.out.print("\033[36ms16v >> \033[0m");
            if (!USER_INPUT.hasNextLine()) break;
            String q = USER_INPUT.nextLine().trim();
            if (q.isEmpty() || q.equalsIgnoreCase("q") || q.equalsIgnoreCase("exit")) break;

            paramsBuilder.addUserMessage(q);
            Message r = agentLoop(paramsBuilder);
            for (ContentBlock cb : r.content()) cb.text().ifPresent(t -> System.out.println(t.text()));
            System.out.println();
        }
        WORKFLOW_POOL.shutdown();
    }
}
