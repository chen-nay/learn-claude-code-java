package com.learn.cc.s13_agent_teams;

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

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/**
 * s13_agent_teams - 主实现 (【教学骨架】)
 *
 * s12 → s13 的核心变化: 【真正的多 agent 团队协作】
 *
 * ⚠️ 学习提示: Python 原版 1881 行, 包含 git worktree + fcntl 跨进程锁 +
 * plan version 追踪 + 完善的部分失败回滚等。
 *
 * 本 Java 版是【教学骨架 + git worktree】: 保留核心机制让你看清架构, 仍去掉:
 *   - fcntl 跨进程文件锁 (我们只有一个 JVM, 用 ReentrantLock 代替)
 *   - Plan work_version 追踪
 *   - 完善的部分失败回滚 (只做轻量提示)
 *
 * 保留的核心机制:
 *
 *   1) Task 系统 (继承 s10)
 *      共享的 .tasks/*.json, teammate 可以 claim/complete
 *
 *   1b) Task 绑定的 git worktree (对齐 Python 原版)
 *      create_worktree(name, task_id) → git worktree add -b wt/<name> .worktrees/<name> HEAD
 *      claim 该任务后, owner 的 bash/read/write/edit/glob 默认 cwd 自动落到那个目录
 *      绑定损坏时 fail closed; 移除只留给 host/user (removeWorktree 定义了但不作模型工具)
 *
 *   2) TeammateRuntime (每个 teammate 一个独立线程)
 *      独立的 messages/system/handlers
 *      WORK/IDLE 两个状态: WORK 时跑 agent 循环, IDLE 时 wait_for_work
 *
 *   3) MessageBus (线程安全的文件邮箱)
 *      .mailboxes/<name>.jsonl 一个 agent 一个文件
 *      send(from, to, content, type, metadata) 追加写
 *      readInbox(agent) 全量读+清空 (destructive read)
 *      waitForMessages(agent, timeout) 用 Condition 阻塞等待
 *
 *   4) Protocol 协议 (Plan Approval + Shutdown)
 *      teammate 想改文件先 submit_plan → Lead review_plan(approve/reject) → 才能改
 *      Lead 想让 teammate 停 → request_shutdown → teammate ack
 *      每个 request 有 requestId, ProtocolState 追踪状态
 *
 *   5) Plan Gate (工具级权限门)
 *      teammate 若 require_plan=true, gate 从 "required" 开始
 *      submit_plan → "pending" → Lead approve → "approved"
 *      只有 "approved" 才允许 bash/write/edit
 *
 *   6) Lead Event-driven Loop
 *      不再是简单的"输入 → agent loop → 输出", 而是同时等 stdin 和 lead mailbox
 *      teammate 发消息给 lead → Lead 醒来, 把 team 事件塞进 messages 触发新一轮
 *
 * 保留 s04 hook 系统 + JSON 打印。
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
    private static final Path MAILBOX_DIR = WORKDIR.resolve(".mailboxes");
    private static final AnthropicClient CLIENT = buildClient();
    private static final Scanner USER_INPUT = new Scanner(System.in);
    private static final SecureRandom RNG = new SecureRandom();

    private static final String SYSTEM =
            "You are the Lead agent at " + WORKDIR + ". Act, don't explain.\n\n"
                    + "Available tools: bash, read_file, write_file, edit_file, glob, "
                    + "create_task, update_task, list_tasks, get_task, claim_task, complete_task, "
                    + "spawn_teammate, list_teammates, send_message, request_shutdown, "
                    + "request_plan, review_plan, create_worktree.\n\n"
                    + "Create task nodes first with create_task, then use update_task to add "
                    + "dependencies. Only Lead changes task dependencies.\n\n"
                    + "When parallel work would help, spawn teammates for independent tasks. "
                    + "Pass task_id to spawn_teammate when assigning ready work. When separate "
                    + "working directories would prevent conflicting edits, call create_worktree "
                    + "for the pending task before assigning it; the owner's file tools then default "
                    + "to that directory. A worktree only changes the default cwd; it is not a "
                    + "sandbox. After spawning, end your turn instead of polling; the runtime will "
                    + "wake you when teammates send events. React to those events, and shut "
                    + "teammates down when done.";

    // ══════════════════════════════════════════════════════════════════
    //  ★ s13 核心 1: Task 系统 (继承 s10, 加了跨线程锁 + owner assignment)
    // ══════════════════════════════════════════════════════════════════

    /** 任务 id 格式 */
    private static final Pattern TASK_ID_PATTERN = Pattern.compile("^task_[0-9a-f]{8}$");

    public record Task(String id, String subject, String description,
                       String status, String owner, List<String> blockedBy,
                       String worktree) {}

    /** 跨线程互斥: task 相关操作 (跟 Python task_lock 对应) */
    private static final ReentrantLock TASK_LOCK = new ReentrantLock();

    /** owner → 正在做的 task_id (Python 里叫 teammate_assignments) */
    private static final Map<String, String> TEAMMATE_ASSIGNMENTS = new ConcurrentHashMap<>();

    private static Path taskPath(String taskId) {
        if (taskId == null || !TASK_ID_PATTERN.matcher(taskId).matches()) {
            throw new IllegalArgumentException("Invalid task ID: " + taskId);
        }
        return TASKS_DIR.resolve(taskId + ".json").toAbsolutePath().normalize();
    }

    private static Task createTask(String subject, String description) throws IOException {
        if (subject == null || subject.strip().isEmpty()) {
            throw new IllegalArgumentException("Task subject cannot be empty");
        }
        TASK_LOCK.lock();
        try {
            Files.createDirectories(TASKS_DIR);
            for (int i = 0; i < 100; i++) {
                byte[] bytes = new byte[4];
                RNG.nextBytes(bytes);
                StringBuilder sb = new StringBuilder("task_");
                for (byte b : bytes) sb.append(String.format("%02x", b));
                String id = sb.toString();
                Path p = taskPath(id);
                try {
                    Files.createFile(p);
                    Task t = new Task(id, subject.strip(), description == null ? "" : description,
                            "pending", null, new ArrayList<>(), null);
                    Files.writeString(p, JSON_MAPPER.writeValueAsString(taskToMap(t)));
                    return t;
                } catch (FileAlreadyExistsException e) {
                    // 换 id 重试
                }
            }
            throw new IOException("Could not allocate a unique task ID");
        } finally { TASK_LOCK.unlock(); }
    }

    private static Map<String, Object> taskToMap(Task t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id()); m.put("subject", t.subject()); m.put("description", t.description());
        m.put("status", t.status()); m.put("owner", t.owner()); m.put("blockedBy", t.blockedBy());
        m.put("worktree", t.worktree());
        return m;
    }

    private static Task loadTask(String taskId) throws IOException {
        Path p = taskPath(taskId);
        Map<String, Object> data = JSON_MAPPER.readValue(Files.readString(p), new TypeReference<>() {});
        String id = String.valueOf(data.get("id"));
        Object ownerObj = data.get("owner");
        @SuppressWarnings("unchecked")
        List<String> blockedBy = (List<String>) data.getOrDefault("blockedBy", new ArrayList<>());
        Object wtObj = data.get("worktree");
        return new Task(id,
                String.valueOf(data.getOrDefault("subject", "")),
                String.valueOf(data.getOrDefault("description", "")),
                String.valueOf(data.getOrDefault("status", "pending")),
                ownerObj == null ? null : String.valueOf(ownerObj),
                new ArrayList<>(blockedBy),
                wtObj == null ? null : String.valueOf(wtObj));
    }

    private static void saveTask(Task t) throws IOException {
        Files.writeString(taskPath(t.id()), JSON_MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(taskToMap(t)));
    }

    private static List<Task> listTasks() {
        List<Task> out = new ArrayList<>();
        if (!Files.isDirectory(TASKS_DIR)) return out;
        try (var s = Files.list(TASKS_DIR)) {
            List<Path> files = s.filter(p -> p.getFileName().toString().matches("task_[0-9a-f]{8}\\.json"))
                    .sorted(Comparator.naturalOrder()).toList();
            for (Path p : files) {
                try { out.add(loadTask(p.getFileName().toString().replace(".json", ""))); }
                catch (Exception ignore) {}
            }
        } catch (IOException ignore) {}
        return out;
    }

    private static boolean canStart(Task t) {
        for (String dep : t.blockedBy()) {
            try {
                if (!"completed".equals(loadTask(dep).status())) return false;
            } catch (Exception e) { return false; }
        }
        return true;
    }

    /** 循环检测 (DFS) */
    private static boolean dependsOn(String taskId, String targetId) throws IOException {
        Deque<String> pending = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        pending.push(taskId);
        while (!pending.isEmpty()) {
            String c = pending.pop();
            if (c.equals(targetId)) return true;
            if (!visited.add(c)) continue;
            try { for (String dep : loadTask(c).blockedBy()) pending.push(dep); }
            catch (Exception ignore) {}
        }
        return false;
    }

    private static Task updateTaskDeps(String taskId, List<String> addBlockedBy) throws IOException {
        TASK_LOCK.lock();
        try {
            Task t = loadTask(taskId);
            if (!"pending".equals(t.status()) || t.owner() != null) {
                throw new IllegalArgumentException("Task " + taskId + " must be pending and unowned to change deps");
            }
            List<String> newDeps = new ArrayList<>(t.blockedBy());
            for (String dep : addBlockedBy) {
                if (newDeps.contains(dep)) continue;
                if (dep.equals(taskId)) throw new IllegalArgumentException("Task cannot depend on itself");
                if (!Files.exists(taskPath(dep))) throw new IllegalArgumentException("Dependency not found: " + dep);
                if (dependsOn(dep, taskId)) throw new IllegalArgumentException("Dependency cycle: " + taskId + " -> " + dep);
                newDeps.add(dep);
            }
            Task updated = new Task(t.id(), t.subject(), t.description(), t.status(), t.owner(), newDeps, t.worktree());
            saveTask(updated);
            return updated;
        } finally { TASK_LOCK.unlock(); }
    }

    /** claim: pending → in_progress, 记录 owner + assignment */
    private static String claimTaskFor(String taskId, String owner) {
        TASK_LOCK.lock();
        try {
            Task t = loadTask(taskId);
            if (!"pending".equals(t.status())) return "Task " + taskId + " is " + t.status() + ", cannot claim";
            if (t.owner() != null) return "Task " + taskId + " already owned by " + t.owner();
            if (TEAMMATE_ASSIGNMENTS.get(owner) != null)
                return owner + " already has assignment " + TEAMMATE_ASSIGNMENTS.get(owner);
            if (!canStart(t)) return "Blocked by deps";
            try {
                taskWorktreeCwd(t);   // ★ fail closed: 拒绝 claim 一个 worktree 绑定已损坏的任务
            } catch (Exception e) {
                return "Cannot claim " + taskId + ": " + e.getMessage();
            }

            Task updated = new Task(t.id(), t.subject(), t.description(), "in_progress", owner, t.blockedBy(), t.worktree());
            saveTask(updated);
            TEAMMATE_ASSIGNMENTS.put(owner, taskId);
            System.out.println("  \033[34m[claim]\033[0m " + updated.subject() + " -> " + owner);
            return "Claimed " + taskId + " (" + updated.subject() + ")";
        } catch (Exception e) { return "Error: " + e.getMessage(); }
        finally { TASK_LOCK.unlock(); }
    }

    /** complete: 只有 owner 才能完成 */
    private static String completeTaskFor(String taskId, String owner) {
        TASK_LOCK.lock();
        try {
            Task t = loadTask(taskId);
            if (!"in_progress".equals(t.status())) return "Task " + taskId + " is " + t.status();
            if (!owner.equals(t.owner())) return "Not owner (owned by " + t.owner() + ")";
            // plan gate 检查 (teammate 才有 gate)
            String gate = PLAN_GATES.get(owner);
            if (gate != null && !"not_required".equals(gate) && !"approved".equals(gate)) {
                return "Cannot complete: plan status is " + gate;
            }
            Task updated = new Task(t.id(), t.subject(), t.description(), "completed", owner, t.blockedBy(), t.worktree());
            saveTask(updated);
            TEAMMATE_ASSIGNMENTS.remove(owner);
            System.out.println("  \033[34m[complete]\033[0m " + updated.subject());
            return "Completed " + taskId + " (" + updated.subject() + ")";
        } catch (Exception e) { return "Error: " + e.getMessage(); }
        finally { TASK_LOCK.unlock(); }
    }

    /** Idle teammate 找一个可 claim 的任务 (跳过 worktree 绑定已损坏的) */
    private static Task claimNextTaskFor(String owner) {
        for (Task t : listTasks()) {
            if (!"pending".equals(t.status()) || t.owner() != null) continue;
            if (!canStart(t)) continue;
            try { taskWorktreeCwd(t); } catch (Exception e) { continue; }
            String result = claimTaskFor(t.id(), owner);
            if (result.startsWith("Claimed")) {
                try { return loadTask(t.id()); } catch (Exception ignore) {}
            }
        }
        return null;
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s13 核心 1b: Task 绑定的 git worktree (对齐 Python 原版)
    //
    //  一个 pending 任务可以 create_worktree 绑一个专属 checkout:
    //    .worktrees/<name>/  ← git worktree add -b wt/<name> <path> HEAD
    //  之后谁 claim 了这个任务, 它的 bash/read/write/edit/glob 默认 cwd
    //  就自动落到那个目录, 与主工作区 / 其它 teammate 的改动隔离。
    //  worktree 只改工具默认目录, 不是沙箱; 移除只留给 host/user, 不作为模型工具。
    //  绑定损坏时 fail closed: claim / 文件工具直接报错, 不静默回落主目录。
    // ══════════════════════════════════════════════════════════════════

    private static final Path WORKTREES_DIR = WORKDIR.resolve(".worktrees");
    private static final Pattern VALID_WORKTREE_NAME =
            Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");

    /** 文件工具接收一个已解析的 cwd */
    @FunctionalInterface
    private interface FsOp { String run(Path cwd); }

    private record GitResult(boolean ok, String output) {}

    private static String validateWorktreeName(String name) {
        if (name == null || !VALID_WORKTREE_NAME.matcher(name).matches()) {
            return "worktree name must be 1-64 chars of [A-Za-z0-9._-], starting with a letter or digit";
        }
        if (name.contains("..")) return "worktree name cannot contain '..'";
        return null;
    }

    private static Path worktreePath(String name) {
        Path root = WORKTREES_DIR.toAbsolutePath().normalize();
        Path path = root.resolve(name).toAbsolutePath().normalize();
        if (!path.startsWith(root) || path.equals(root)) {
            throw new IllegalArgumentException("Worktree path escapes directory: " + name);
        }
        return path;
    }

    private static String worktreeBranch(String name) { return "wt/" + name; }

    /** 不经 shell 直接跑 git; 30s 超时; 返回 (exit==0, stdout+stderr) */
    private static GitResult runGitRaw(List<String> args, Path cwd) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(args);
        try {
            Process p = new ProcessBuilder(cmd)
                    .directory((cwd == null ? WORKDIR : cwd).toFile())
                    .redirectErrorStream(true)
                    .start();
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new GitResult(false, "git timeout (30s)");
            }
            String out = new String(p.getInputStream().readAllBytes()).trim();
            return new GitResult(p.exitValue() == 0, out.isEmpty() ? "(no output)" : out);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new GitResult(false, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /** 同上, 但只把回给模型的文本截到 5000 字 */
    private static GitResult runGit(List<String> args, Path cwd) {
        GitResult r = runGitRaw(args, cwd);
        String o = r.output();
        return new GitResult(r.ok(), o.length() > 5000 ? o.substring(0, 5000) : o);
    }

    /** 解析符号链接; 目标不存在时回落到 absolute+normalize (对齐 Python Path.resolve) */
    private static Path canonPath(Path p) {
        try { return p.toRealPath(); }
        catch (IOException e) { return p.toAbsolutePath().normalize(); }
    }

    /** 解析 `git worktree list --porcelain` → {规范化路径: {key: value}} */
    private static Map<Path, Map<String, String>> registeredWorktrees() throws IOException {
        GitResult r = runGitRaw(List.of("worktree", "list", "--porcelain"), WORKDIR);
        if (!r.ok()) throw new IOException("cannot read Git worktree registry: " + r.output());
        Map<Path, Map<String, String>> entries = new LinkedHashMap<>();
        Map<String, String> current = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>(List.of(r.output().split("\n", -1)));
        lines.add("");   // 末尾补空行, 触发最后一个 record 落库
        for (String line : lines) {
            if (line.isEmpty()) {
                String raw = current.get("worktree");
                if (raw != null) entries.put(canonPath(Paths.get(raw)), current);
                current = new LinkedHashMap<>();
                continue;
            }
            int sp = line.indexOf(' ');
            if (sp < 0) current.put(line, "");
            else current.put(line.substring(0, sp), line.substring(sp + 1));
        }
        return entries;
    }

    /** 校验命名 worktree 确实注册在 wt/<name> 分支上且目录存在; 否则抛异常 */
    private static Path registeredWorktree(String name) throws IOException {
        Path path = worktreePath(name);
        if (!Files.isDirectory(path)) {
            throw new IOException("worktree '" + name + "' is missing at " + path);
        }
        Path canon = canonPath(path);
        Map<Path, Map<String, String>> entries = registeredWorktrees();
        if (!entries.containsKey(canon)) {
            throw new IOException("worktree '" + name + "' is not registered with Git");
        }
        String expected = "refs/heads/" + worktreeBranch(name);
        if (!expected.equals(entries.get(canon).get("branch"))) {
            throw new IOException("worktree '" + name + "' is not on expected branch '"
                    + worktreeBranch(name) + "'");
        }
        return path;
    }

    /** task.worktree 为空 → 主工作区; 否则解析绑定的 worktree (损坏则抛异常) */
    private static Path taskWorktreeCwd(Task task) throws IOException {
        if (task.worktree() == null || task.worktree().isEmpty()) return WORKDIR;
        return registeredWorktree(task.worktree());
    }

    /** owner 当前 assignment 的 cwd。无 assignment → 主工作区; assignment 失效/损坏 → 抛异常 */
    private static Path assignmentCwd(String owner) throws IOException {
        String taskId = TEAMMATE_ASSIGNMENTS.get(owner);
        if (taskId == null) return WORKDIR;
        Task t = loadTask(taskId);
        boolean live = ("in_progress".equals(t.status()) || "completed".equals(t.status()))
                && owner.equals(t.owner());
        if (!live) throw new IOException("assignment for " + owner + " is no longer active");
        return taskWorktreeCwd(t);
    }

    /** Lead(owner="agent") 的文件工具: 解析 cwd 再执行, 解析失败回错误串 */
    private static String withAgentCwd(FsOp op) {
        Path cwd;
        try { cwd = assignmentCwd("agent"); }
        catch (Exception e) { return "Error: invalid task assignment: " + e.getMessage(); }
        return op.run(cwd);
    }

    /** create_worktree: 全部前置校验通过后, git worktree add + 绑定到任务 */
    private static String createWorktree(String name, String taskId) {
        String nameErr = validateWorktreeName(name);
        if (nameErr != null) return "Error: " + nameErr;
        Path path;
        try {
            path = worktreePath(name);
            taskPath(taskId);   // 校验 taskId 格式
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
        String branch = worktreeBranch(name);

        TASK_LOCK.lock();
        try {
            if (!Files.exists(taskPath(taskId))) return "Error: Task " + taskId + " not found";
            Task task = loadTask(taskId);
            if (!"pending".equals(task.status()) || task.owner() != null) {
                return "Error: Task " + taskId + " must be pending and unowned";
            }
            if (task.worktree() != null) {
                return "Error: Task " + taskId + " already uses worktree '" + task.worktree() + "'";
            }
            for (Task other : listTasks()) {
                if (!other.id().equals(taskId) && name.equals(other.worktree())) {
                    return "Error: Worktree '" + name + "' is already bound to another task";
                }
            }
            if (Files.exists(path)) return "Error: Worktree path already exists: " + path;

            GitResult root = runGit(List.of("rev-parse", "--show-toplevel"), WORKDIR);
            if (!root.ok()
                    || !canonPath(Paths.get(root.output())).equals(canonPath(WORKDIR))) {
                return "Error: Working directory must be the root of a Git repository";
            }
            GitResult fmt = runGit(List.of("check-ref-format", "--branch", branch), WORKDIR);
            if (!fmt.ok()) return "Error: Invalid worktree branch '" + branch + "': " + fmt.output();
            GitResult exists = runGit(
                    List.of("show-ref", "--verify", "--quiet", "refs/heads/" + branch), WORKDIR);
            if (exists.ok()) return "Error: Branch '" + branch + "' already exists";
            Map<Path, Map<String, String>> entries;
            try { entries = registeredWorktrees(); }
            catch (IOException e) { return "Error: " + e.getMessage(); }
            if (entries.containsKey(canonPath(path))) return "Error: Worktree path already registered: " + path;

            Files.createDirectories(WORKTREES_DIR);
            GitResult add = runGit(
                    List.of("worktree", "add", "-b", branch, path.toString(), "HEAD"), WORKDIR);
            if (!add.ok()) {
                boolean branchLeft = runGit(
                        List.of("show-ref", "--verify", "--quiet", "refs/heads/" + branch), WORKDIR).ok();
                String left = (Files.exists(path) ? ("path '" + path + "'") : "")
                        + (branchLeft ? (Files.exists(path) ? " + " : "") + "branch '" + branch + "'" : "");
                return left.isEmpty()
                        ? "Git error: " + add.output()
                        : "Partial operation: `git worktree add` failed after leaving " + left
                          + ". Task " + taskId + " stays unbound; inspect and clean up manually. "
                          + "Git error: " + add.output();
            }

            saveTask(new Task(task.id(), task.subject(), task.description(),
                    task.status(), task.owner(), task.blockedBy(), name));
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        } finally {
            TASK_LOCK.unlock();
        }

        System.out.println("  \033[33m[worktree]\033[0m created: " + name + " at " + path);
        return "Worktree '" + name + "' created at " + path + " for task " + taskId;
    }

    /**
     * remove_worktree: 移除 checkout, 永远保留分支。
     * 对齐 Python: 定义出来供 host/user 使用, 但不放进模型工具池。
     */
    @SuppressWarnings("unused")
    private static String removeWorktree(String name, boolean discardChanges) {
        String nameErr = validateWorktreeName(name);
        if (nameErr != null) return "Error: " + nameErr;
        TASK_LOCK.lock();
        try {
            Path path;
            try { path = registeredWorktree(name); }
            catch (IOException e) { return "Error: " + e.getMessage(); }
            List<Task> bound = new ArrayList<>();
            for (Task t : listTasks()) if (name.equals(t.worktree())) bound.add(t);
            if (bound.isEmpty()) return "Error: Worktree '" + name + "' is not bound to a task";
            for (Task t : bound) {
                if (!"completed".equals(t.status())) {
                    return "Error: Worktree '" + name + "' is bound to active task " + t.id()
                            + "; complete it before removal";
                }
            }
            GitResult status = runGit(List.of("status", "--porcelain", "--ignored"), path);
            if (!status.ok()) {
                return "Error: cannot verify worktree '" + name + "' status: " + status.output();
            }
            if (!"(no output)".equals(status.output()) && !discardChanges) {
                return "Error: Worktree '" + name + "' has uncommitted changes; "
                        + "preserve or discard them manually";
            }
            List<String> args = new ArrayList<>(List.of("worktree", "remove"));
            if (discardChanges) args.add("--force");
            args.add(path.toString());
            GitResult rm = runGit(args, WORKDIR);
            if (!rm.ok()) return "Git error: " + rm.output();
            for (Task t : bound) {
                saveTask(new Task(t.id(), t.subject(), t.description(),
                        t.status(), t.owner(), t.blockedBy(), null));
            }
        } catch (IOException e) {
            return "Error: " + e.getMessage();
        } finally {
            TASK_LOCK.unlock();
        }
        System.out.println("  \033[33m[worktree]\033[0m removed: " + name + "; branch retained");
        return "Worktree '" + name + "' removed; branch '" + worktreeBranch(name) + "' retained";
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s13 核心 2: MessageBus - 线程安全文件邮箱
    // ══════════════════════════════════════════════════════════════════

    private static final Pattern VALID_AGENT_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");
    private static final Set<String> RESERVED_NAMES = Set.of("lead", "agent");

    static final class MessageBus {
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition changed = lock.newCondition();

        private Path mailboxPath(String agent) {
            if (!VALID_AGENT_NAME.matcher(agent).matches())
                throw new IllegalArgumentException("Invalid mailbox name: " + agent);
            return MAILBOX_DIR.resolve(agent + ".jsonl").toAbsolutePath().normalize();
        }

        /** 追加一条消息, notify 所有 waiter */
        void send(String from, String to, String content, String type, Map<String, Object> metadata) {
            lock.lock();
            try {
                Files.createDirectories(MAILBOX_DIR);
                Map<String, Object> msg = new LinkedHashMap<>();
                msg.put("from", from); msg.put("to", to); msg.put("content", content);
                msg.put("type", type);
                msg.put("ts", System.currentTimeMillis() / 1000.0);
                msg.put("metadata", metadata == null ? Map.of() : metadata);
                String line = JSON_MAPPER.writeValueAsString(msg) + "\n";
                Files.writeString(mailboxPath(to), line,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                changed.signalAll();
                String preview = content.length() > 50 ? content.substring(0, 50) : content;
                System.out.println("  \033[36m[bus]\033[0m " + from + " -> " + to + " (" + type + "): " + preview);
            } catch (Exception e) {
                System.err.println("[bus] send failed: " + e.getMessage());
            } finally { lock.unlock(); }
        }

        /** 全量读+清空 (destructive read) */
        List<Map<String, Object>> readInbox(String agent) {
            lock.lock();
            try {
                Path p = mailboxPath(agent);
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
            lock.lock();
            try {
                Path p = mailboxPath(agent);
                return Files.exists(p) && Files.size(p) > 0;
            } catch (IOException e) { return false; }
            finally { lock.unlock(); }
        }

        /** 阻塞等待直到有消息或超时 */
        List<Map<String, Object>> waitForMessages(String agent, long timeoutMs) {
            long deadline = System.currentTimeMillis() + timeoutMs;
            lock.lock();
            try {
                while (!peek(agent)) {
                    long remaining = deadline - System.currentTimeMillis();
                    if (remaining <= 0) return List.of();
                    try { changed.await(remaining, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); return List.of(); }
                }
                return readInbox(agent);
            } finally { lock.unlock(); }
        }
    }

    private static final MessageBus BUS = new MessageBus();

    // ══════════════════════════════════════════════════════════════════
    //  ★ s13 核心 3: Team 状态 + Protocol 协议
    // ══════════════════════════════════════════════════════════════════

    /** teammate 状态: working / waiting_approval / idle / stopping */
    private static final Map<String, String> ACTIVE_TEAMMATES = new ConcurrentHashMap<>();
    /** teammate 的 plan gate: not_required / required / pending / approved / rejected */
    private static final Map<String, String> PLAN_GATES = new ConcurrentHashMap<>();
    /** teammate 当前的 plan request id */
    private static final Map<String, String> PLAN_REQUEST_IDS = new ConcurrentHashMap<>();
    /** teammate 线程句柄 (用于清理) */
    private static final Map<String, Thread> TEAMMATE_THREADS = new ConcurrentHashMap<>();
    private static final ReentrantLock TEAM_LOCK = new ReentrantLock();

    /** protocol state (shutdown 请求 + plan_approval 请求) */
    static final class ProtocolState {
        final String requestId;
        final String type;      // "shutdown" | "plan_approval"
        final String sender;
        final String target;
        volatile String status; // "pending" | "approved" | "rejected"
        final String payload;

        ProtocolState(String requestId, String type, String sender, String target, String payload) {
            this.requestId = requestId;
            this.type = type;
            this.sender = sender;
            this.target = target;
            this.status = "pending";
            this.payload = payload;
        }
    }

    private static final Map<String, ProtocolState> PENDING_REQUESTS = new ConcurrentHashMap<>();

    private static String newRequestId() {
        while (true) {
            String id = String.format("req_%06d", RNG.nextInt(1_000_000));
            if (!PENDING_REQUESTS.containsKey(id)) return id;
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s13 核心 4: TeammateRuntime - 每个 teammate 一个独立线程
    // ══════════════════════════════════════════════════════════════════

    static final class TeammateRuntime implements Runnable {
        private final String name;
        private final String role;
        private final String system;
        private final List<MessageParam> messages;
        private final boolean requirePlan;

        TeammateRuntime(String name, String role, String prompt, String taskId, boolean requirePlan) {
            this.name = name;
            this.role = role;
            this.requirePlan = requirePlan;
            this.system = "You are '" + name + "', a " + role + ". "
                    + "Use tools to complete the assigned Task, then call complete_task and report a concise result. "
                    + "If the first user message contains [Assigned task], that Task is already claimed; "
                    + "do not call claim_task for it again. "
                    + "When asked for a plan, call submit_plan and wait for approval before bash or file changes. "
                    + "The runtime delivers your final text to Lead. Use send_message only for intermediate "
                    + "coordination, and address the coordinator as 'lead'.";
            StringBuilder initPrompt = new StringBuilder(prompt);
            if (taskId != null) {
                try {
                    Task t = loadTask(taskId);
                    String wd;
                    try { wd = assignmentCwd(name).toString(); } catch (Exception e) { wd = WORKDIR.toString(); }
                    initPrompt.append("\n\n[Assigned task ").append(t.id()).append("] ")
                              .append(t.subject()).append("\n").append(t.description())
                              .append("\nWork directory: ").append(wd);
                } catch (Exception ignore) {}
            }
            if (requirePlan) {
                initPrompt.append("\n\n[Plan required] Submit a plan and wait for Lead approval "
                        + "before changing files or using bash.");
            }
            this.messages = new ArrayList<>();
            this.messages.add(MessageParam.builder().role(MessageParam.Role.USER)
                    .content(initPrompt.toString()).build());
        }

        /**
         * 处理收件箱: shutdown request → 直接返回 true (要停);
         *              plan response → 更新 PLAN_GATES;
         *              普通消息 → append 到 messages
         */
        boolean handleInbox(List<Map<String, Object>> inbox) {
            List<String> workMsgs = new ArrayList<>();
            for (Map<String, Object> msg : inbox) {
                String type = String.valueOf(msg.getOrDefault("type", "message"));
                @SuppressWarnings("unchecked")
                Map<String, Object> metadata = (Map<String, Object>) msg.getOrDefault("metadata", Map.of());
                String requestId = String.valueOf(metadata.getOrDefault("request_id", ""));

                switch (type) {
                    case "shutdown_request" -> {
                        ProtocolState state = PENDING_REQUESTS.get(requestId);
                        if (state != null && "shutdown".equals(state.type) && "lead".equals(state.sender)
                                && name.equals(state.target)) {
                            ACTIVE_TEAMMATES.put(name, "stopping");
                            BUS.send(name, "lead", "Shutdown acknowledged.",
                                    "shutdown_response",
                                    Map.of("request_id", requestId, "approve", true));
                            return true;
                        }
                        workMsgs.add("[Ignored shutdown_request: mismatch]");
                    }
                    case "plan_approval_response" -> {
                        boolean approve = Boolean.TRUE.equals(metadata.get("approve"));
                        String expectedId = PLAN_REQUEST_IDS.get(name);
                        if (requestId.equals(expectedId)) {
                            PLAN_GATES.put(name, approve ? "approved" : "rejected");
                            PLAN_REQUEST_IDS.remove(name);
                            ACTIVE_TEAMMATES.put(name, "working");
                            workMsgs.add("[Plan " + (approve ? "approved" : "rejected") + "] "
                                    + msg.get("content"));
                        } else {
                            workMsgs.add("[Ignored plan_approval_response: mismatch]");
                        }
                    }
                    case "plan_request" -> {
                        workMsgs.add("[Plan required] " + msg.get("content"));
                    }
                    default -> {
                        workMsgs.add("[Message from " + msg.get("from") + "] " + msg.get("content"));
                    }
                }
            }
            if (!workMsgs.isEmpty()) {
                messages.add(MessageParam.builder().role(MessageParam.Role.USER)
                        .content(String.join("\n", workMsgs)).build());
            }
            return false;
        }

        /**
         * 一轮 model turn。返回:
         *   "continue" - 有 tool_use 需要继续下一轮
         *   "idle" - 空转, 等新消息或新任务
         *   "stop" - 收到 shutdown 或错误
         */
        String work() {
            if (handleInbox(BUS.readInbox(name))) return "stop";
            ACTIVE_TEAMMATES.put(name, "working");

            MessageCreateParams.Builder pb = MessageCreateParams.builder()
                    .model(MODEL).system(system).maxTokens(8000).temperature(0.3);
            TEAMMATE_TOOLS.forEach(pb::addTool);
            messages.forEach(pb::addMessage);

            Message response;
            try {
                response = CLIENT.messages().create(pb.build());
            } catch (Exception e) {
                BUS.send(name, "lead", e.getClass().getSimpleName() + ": " + e.getMessage(), "error", null);
                return "stop";
            }

            messages.add(assistantToParam(response));

            // 是否有 tool_use
            boolean hasToolUse = false;
            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock cb : response.content()) {
                Optional<ToolUseBlock> mtu = cb.toolUse();
                if (mtu.isEmpty()) continue;
                hasToolUse = true;
                ToolUseBlock tb = mtu.get();
                Map<String, Object> input = JSON_MAPPER.convertValue(tb._input(), new TypeReference<>() {});
                String output = runTeammateTool(tb, input);
                System.out.println("    \033[90m[" + name + "] " + tb.name() + " -> "
                        + (output.length() > 100 ? output.substring(0, 100) : output) + "\033[0m");
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(tb.id()).content(output).build()));
            }
            if (hasToolUse) {
                messages.add(MessageParam.builder().role(MessageParam.Role.USER)
                        .contentOfBlockParams(results).build());
                return "continue";
            }

            // 无 tool_use → 提取最终 text 报告给 Lead
            String summary = response.content().stream()
                    .flatMap(cb -> cb.text().stream())
                    .map(TextBlock::text)
                    .reduce("", (a, b) -> a.isEmpty() ? b : a + "\n" + b);
            String gate = PLAN_GATES.getOrDefault(name, "not_required");
            if (!"pending".equals(gate) && !summary.isEmpty()) {
                BUS.send(name, "lead", summary, "result", null);
            }
            if ("pending".equals(gate)) {
                ACTIVE_TEAMMATES.put(name, "waiting_approval");
            } else {
                ACTIVE_TEAMMATES.put(name, "idle");
                BUS.send(name, "lead", "Waiting for more work.", "idle_notification", null);
            }
            return "idle";
        }

        /** teammate 的文件工具 cwd: 必须先 claim 一个任务, cwd 落到该任务的 worktree (或主目录) */
        private String withCwd(FsOp op) {
            if (!TEAMMATE_ASSIGNMENTS.containsKey(name)) {
                return "Error: Claim a Task before using workspace tools.";
            }
            Path cwd;
            try { cwd = assignmentCwd(name); }
            catch (Exception e) { return "Error: Invalid task assignment: " + e.getMessage(); }
            return op.run(cwd);
        }

        /** teammate 工具执行 (含 plan gate 检查) */
        private String runTeammateTool(ToolUseBlock block, Map<String, Object> input) {
            String toolName = block.name();
            // ★ Plan Gate: bash/write/edit 需要 approved 才能用
            if (Set.of("bash", "write_file", "edit_file").contains(toolName)) {
                String gate = PLAN_GATES.getOrDefault(name, "not_required");
                if (!"approved".equals(gate) && !"not_required".equals(gate)) {
                    return "Blocked: plan status is " + gate
                            + ". Submit or revise the plan and wait for approval.";
                }
            }
            return switch (toolName) {
                // ★ 文件工具经 assignmentCwd(name) 解析到本任务的 worktree, 与其它 teammate 隔离
                case "bash"          -> withCwd(cwd -> runBash((String) input.get("command"), cwd));
                case "read_file"     -> withCwd(cwd -> runRead((String) input.get("path"),
                                                asInteger(input.get("limit")), cwd));
                case "write_file"    -> withCwd(cwd -> runWrite((String) input.get("path"),
                                                (String) input.get("content"), cwd));
                case "edit_file"     -> withCwd(cwd -> runEdit((String) input.get("path"),
                                                (String) input.get("old_text"),
                                                (String) input.get("new_text"), cwd));
                case "glob"          -> withCwd(cwd -> runGlob((String) input.get("pattern"), cwd));
                case "send_message"  -> teammateSendMessage(name,
                                                (String) input.get("to"),
                                                (String) input.get("content"));
                case "submit_plan"   -> teammateSubmitPlan(name, (String) input.get("plan"));
                case "list_tasks"    -> runListTasks();
                case "claim_task"    -> claimTaskFor((String) input.get("task_id"), name);
                case "complete_task" -> completeTaskFor((String) input.get("task_id"), name);
                default              -> "Unknown tool: " + toolName;
            };
        }

        /**
         * IDLE 状态: 等消息 或 自己抢 unclaimed task
         * 返回 true = 继续工作, false = 该退出
         */
        boolean waitForWork() {
            while (true) {
                List<Map<String, Object>> inbox = BUS.waitForMessages(name, 2000);
                if (!inbox.isEmpty()) {
                    int before = messages.size();
                    if (handleInbox(inbox)) return false;
                    if (messages.size() > before) return true;
                    continue;
                }
                // 没消息 → 试着自己找活干
                Task t = claimNextTaskFor(name);
                if (t != null) {
                    String wd;
                    try { wd = assignmentCwd(name).toString(); } catch (Exception e) { wd = WORKDIR.toString(); }
                    messages.add(MessageParam.builder().role(MessageParam.Role.USER)
                            .content("[Auto-claimed task " + t.id() + "] "
                                    + t.subject() + "\n" + t.description()
                                    + "\nWork directory: " + wd).build());
                    System.out.println("  \033[35m[idle]\033[0m " + name + " claimed " + t.id());
                    return true;
                }
                // 也没活 → 继续等
            }
        }

        @Override
        public void run() {
            try {
                String state = "continue";
                while (!"stop".equals(state)) {
                    if ("idle".equals(state) && !waitForWork()) break;
                    state = work();
                }
            } catch (Exception e) {
                try { BUS.send(name, "lead", e.getClass().getSimpleName() + ": " + e.getMessage(), "error", null); }
                catch (Exception ignore) {}
            } finally {
                // 释放未完成的 assignment
                TASK_LOCK.lock();
                try {
                    String assignedTaskId = TEAMMATE_ASSIGNMENTS.remove(name);
                    if (assignedTaskId != null) {
                        try {
                            Task t = loadTask(assignedTaskId);
                            if ("in_progress".equals(t.status()) && name.equals(t.owner())) {
                                saveTask(new Task(t.id(), t.subject(), t.description(), "pending", null, t.blockedBy(), t.worktree()));
                            }
                        } catch (Exception ignore) {}
                    }
                } finally { TASK_LOCK.unlock(); }
                ACTIVE_TEAMMATES.remove(name);
                PLAN_GATES.remove(name);
                PLAN_REQUEST_IDS.remove(name);
                TEAMMATE_THREADS.remove(name);
                System.out.println("  \033[35m[teammate]\033[0m " + name + " finished");
            }
        }
    }

    /** teammate 内部工具: submit_plan */
    private static String teammateSubmitPlan(String fromName, String plan) {
        TEAM_LOCK.lock();
        try {
            if ("pending".equals(PLAN_GATES.get(fromName))) {
                return "A plan is already waiting for review.";
            }
            String requestId = newRequestId();
            PENDING_REQUESTS.put(requestId, new ProtocolState(requestId, "plan_approval", fromName, "lead", plan));
            PLAN_GATES.put(fromName, "pending");
            PLAN_REQUEST_IDS.put(fromName, requestId);
            ACTIVE_TEAMMATES.put(fromName, "waiting_approval");
            BUS.send(fromName, "lead", plan, "plan_approval_request",
                    Map.of("request_id", requestId));
            return "Plan submitted (" + requestId + "). Wait for Lead's decision.";
        } finally { TEAM_LOCK.unlock(); }
    }

    /** teammate 内部工具: send_message */
    private static String teammateSendMessage(String fromName, String to, String content) {
        if (!"lead".equals(to) && !ACTIVE_TEAMMATES.containsKey(to)) {
            return "Agent '" + to + "' is not active";
        }
        BUS.send(fromName, to, content, "message", null);
        return "Sent to " + to;
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s13 核心 5: Lead 端团队工具
    // ══════════════════════════════════════════════════════════════════

    private static String spawnTeammate(String name, String role, String prompt,
                                        String taskId, boolean requirePlan) {
        if (!VALID_AGENT_NAME.matcher(name).matches()) {
            return "Invalid teammate name: use 1-64 letters/digits/underscores/dashes";
        }
        if (RESERVED_NAMES.contains(name.toLowerCase())) {
            return "Invalid teammate name: '" + name + "' is reserved";
        }
        TEAM_LOCK.lock();
        try {
            if (ACTIVE_TEAMMATES.containsKey(name)) return "Teammate '" + name + "' already exists";
            ACTIVE_TEAMMATES.put(name, "working");
            PLAN_GATES.put(name, requirePlan ? "required" : "not_required");
        } finally { TEAM_LOCK.unlock(); }

        if (taskId != null) {
            String claimed = claimTaskFor(taskId, name);
            if (!claimed.startsWith("Claimed")) {
                ACTIVE_TEAMMATES.remove(name);
                PLAN_GATES.remove(name);
                return "Cannot spawn teammate '" + name + "': " + claimed;
            }
        }
        TeammateRuntime rt = new TeammateRuntime(name, role, prompt, taskId, requirePlan);
        Thread t = new Thread(rt, "teammate-" + name);
        t.setDaemon(true);
        TEAMMATE_THREADS.put(name, t);
        t.start();
        System.out.println("  \033[35m[teammate]\033[0m " + name + " spawned as " + role);
        return "Teammate '" + name + "' spawned as " + role
                + (taskId == null ? " without initial task" : " for " + taskId)
                + ". End this turn; runtime will deliver its events.";
    }

    private static String runListTeammates() {
        if (ACTIVE_TEAMMATES.isEmpty()) return "No active teammates.";
        StringBuilder sb = new StringBuilder();
        ACTIVE_TEAMMATES.forEach((n, s) -> sb.append(n).append(": ").append(s).append("\n"));
        return sb.toString().stripTrailing();
    }

    private static String runSendMessage(String to, String content) {
        if (!ACTIVE_TEAMMATES.containsKey(to)) return "Teammate '" + to + "' is not active";
        BUS.send("lead", to, content, "message", null);
        return "Sent to " + to;
    }

    private static String runRequestShutdown(String teammate) {
        if (!ACTIVE_TEAMMATES.containsKey(teammate)) return "Teammate '" + teammate + "' is not active";
        String requestId = newRequestId();
        PENDING_REQUESTS.put(requestId, new ProtocolState(requestId, "shutdown", "lead", teammate, ""));
        BUS.send("lead", teammate, "Finish current step and shut down.",
                "shutdown_request", Map.of("request_id", requestId));
        return "Shutdown requested from " + teammate + " (" + requestId + ")";
    }

    private static String runRequestPlan(String teammate, String task) {
        if (!ACTIVE_TEAMMATES.containsKey(teammate)) return "Teammate '" + teammate + "' is not active";
        PLAN_GATES.put(teammate, "required");
        BUS.send("lead", teammate, task, "plan_request", null);
        return "Plan requested from " + teammate;
    }

    private static String runReviewPlan(String requestId, boolean approve, String feedback) {
        ProtocolState state = PENDING_REQUESTS.get(requestId);
        if (state == null) return "Request " + requestId + " not found";
        if (!"plan_approval".equals(state.type)) return "Request is not a plan";
        if (!"pending".equals(state.status)) return "Request already " + state.status;
        state.status = approve ? "approved" : "rejected";
        String content = feedback == null || feedback.isEmpty()
                ? (approve ? "Plan approved." : "Revise the plan and submit it again.")
                : feedback;
        BUS.send("lead", state.sender, content, "plan_approval_response",
                Map.of("request_id", requestId, "approve", approve));
        return "Plan " + state.status + " (" + requestId + ")";
    }

    // ══════════════════════════════════════════════════════════════════
    //  基础 5 工具
    // ══════════════════════════════════════════════════════════════════

    /** 把相对路径解析到 base 目录下, 并挡住越界 (对齐 Python safe_path) */
    private static Path safePath(String p, Path base) {
        Path root = base.toAbsolutePath().normalize();
        Path resolved = root.resolve(p).toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("Path escapes workspace: " + p);
        }
        return resolved;
    }

    private static String runBash(String command) { return runBash(command, WORKDIR); }

    private static String runBash(String command, Path cwd) {
        try {
            Process p = new ProcessBuilder("bash", "-c", command)
                    .directory(cwd.toFile()).redirectErrorStream(true).start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return "Error: Timeout"; }
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (out.isEmpty()) return "(no output)";
            return out.length() > 50_000 ? out.substring(0, 50_000) : out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return "Error: " + e.getMessage();
        }
    }

    private static String runRead(String path, Integer limit) { return runRead(path, limit, WORKDIR); }

    private static String runRead(String path, Integer limit, Path cwd) {
        try {
            List<String> lines = Files.readAllLines(safePath(path, cwd));
            if (limit != null && limit < lines.size()) {
                List<String> t = new ArrayList<>(lines.subList(0, limit));
                t.add("... (" + (lines.size() - limit) + " more lines)");
                lines = t;
            }
            return String.join("\n", lines);
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runWrite(String path, String content) { return runWrite(path, content, WORKDIR); }

    private static String runWrite(String path, String content, Path cwd) {
        try {
            Path file = safePath(path, cwd);
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            return "Wrote " + content.length() + " bytes to " + path;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runEdit(String path, String oldText, String newText) {
        return runEdit(path, oldText, newText, WORKDIR);
    }

    private static String runEdit(String path, String oldText, String newText, Path cwd) {
        try {
            Path file = safePath(path, cwd);
            String text = Files.readString(file);
            int idx = text.indexOf(oldText);
            if (idx < 0) return "Error: text not found in " + path;
            Files.writeString(file, text.substring(0, idx) + newText + text.substring(idx + oldText.length()));
            return "Edited " + path;
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runGlob(String pattern) { return runGlob(pattern, WORKDIR); }

    private static String runGlob(String pattern, Path cwd) {
        try {
            Path base = cwd.toAbsolutePath().normalize();
            var matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            try (var walk = Files.walk(base)) {
                List<String> results = walk.filter(p -> p.startsWith(base))
                        .map(base::relativize)
                        .filter(rel -> !rel.toString().isEmpty())
                        .filter(matcher::matches)
                        .sorted(Comparator.naturalOrder())
                        .map(Path::toString).toList();
                return results.isEmpty() ? "(no matches)" : String.join("\n", results);
            }
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static String runListTasks() {
        List<Task> tasks = listTasks();
        if (tasks.isEmpty()) return "No tasks.";
        StringBuilder sb = new StringBuilder();
        for (Task t : tasks) {
            String icon = switch (t.status()) {
                case "pending" -> "[ ]"; case "in_progress" -> "[~]"; case "completed" -> "[x]";
                default -> "[?]";
            };
            String owner = t.owner() == null ? "" : " [" + t.owner() + "]";
            String deps = t.blockedBy().isEmpty() ? "" : " (blockedBy: " + String.join(", ", t.blockedBy()) + ")";
            String wt = t.worktree() == null ? "" : " (worktree: " + t.worktree() + ")";
            sb.append(icon).append(" ").append(t.id()).append(": ").append(t.subject())
              .append(" [").append(t.status()).append("]").append(owner).append(deps).append(wt).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    /** Lead 主 dispatch */
    private static String dispatchLeadTool(String name, Map<String, Object> input) {
        try {
            return switch (name) {
                // ★ 文件工具经 assignmentCwd("agent") 解析: Lead 若 claim 了带 worktree 的任务, 默认落到那个目录
                case "bash"             -> withAgentCwd(cwd -> runBash((String) input.get("command"), cwd));
                case "read_file"        -> withAgentCwd(cwd -> runRead((String) input.get("path"),
                                                   asInteger(input.get("limit")), cwd));
                case "write_file"       -> withAgentCwd(cwd -> runWrite((String) input.get("path"),
                                                   (String) input.get("content"), cwd));
                case "edit_file"        -> withAgentCwd(cwd -> runEdit((String) input.get("path"),
                                                   (String) input.get("old_text"),
                                                   (String) input.get("new_text"), cwd));
                case "glob"             -> withAgentCwd(cwd -> runGlob((String) input.get("pattern"), cwd));
                case "create_worktree"  -> createWorktree((String) input.get("name"),
                                                   (String) input.get("task_id"));
                case "create_task"      -> {
                    Task t = createTask((String) input.get("subject"),
                            (String) input.getOrDefault("description", ""));
                    yield "Created " + t.id() + ": " + t.subject();
                }
                case "update_task"      -> {
                    @SuppressWarnings("unchecked")
                    List<String> deps = (List<String>) input.get("addBlockedBy");
                    Task t = updateTaskDeps((String) input.get("task_id"), deps);
                    yield "Updated " + t.id() + " blockedBy: " + String.join(", ", t.blockedBy());
                }
                case "list_tasks"       -> runListTasks();
                case "get_task"         -> JSON_MAPPER.writeValueAsString(taskToMap(loadTask((String) input.get("task_id"))));
                case "claim_task"       -> claimTaskFor((String) input.get("task_id"), "agent");
                case "complete_task"    -> completeTaskFor((String) input.get("task_id"), "agent");
                case "spawn_teammate"   -> spawnTeammate((String) input.get("name"),
                                                        (String) input.get("role"),
                                                        (String) input.get("prompt"),
                                                        (String) input.get("task_id"),
                                                        Boolean.TRUE.equals(input.get("require_plan")));
                case "list_teammates"   -> runListTeammates();
                case "send_message"     -> runSendMessage((String) input.get("to"), (String) input.get("content"));
                case "request_shutdown" -> runRequestShutdown((String) input.get("teammate"));
                case "request_plan"     -> runRequestPlan((String) input.get("teammate"), (String) input.get("task"));
                case "review_plan"      -> runReviewPlan((String) input.get("request_id"),
                                                        Boolean.TRUE.equals(input.get("approve")),
                                                        (String) input.getOrDefault("feedback", ""));
                default                 -> "Unknown tool: " + name;
            };
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private static Integer asInteger(Object v) { return (v instanceof Number n) ? n.intValue() : null; }

    // ── Tool 定义 ─────────────────────────────────────────────────
    private static final List<Tool> LEAD_TOOLS = new ArrayList<>();
    private static final List<Tool> TEAMMATE_TOOLS = new ArrayList<>();

    static {
        // 基础 5 工具
        LEAD_TOOLS.add(tool("bash", "Run a shell command.",
                Map.of("command", Map.of("type", "string")), List.of("command")));
        LEAD_TOOLS.add(tool("read_file", "Read file contents.",
                Map.of("path", Map.of("type", "string"), "limit", Map.of("type", "integer")), List.of("path")));
        LEAD_TOOLS.add(tool("write_file", "Write content to a file.",
                Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")),
                List.of("path", "content")));
        LEAD_TOOLS.add(tool("edit_file", "Replace exact text once.",
                Map.of("path", Map.of("type", "string"),
                       "old_text", Map.of("type", "string"),
                       "new_text", Map.of("type", "string")),
                List.of("path", "old_text", "new_text")));
        LEAD_TOOLS.add(tool("glob", "Find files by glob pattern.",
                Map.of("pattern", Map.of("type", "string")), List.of("pattern")));

        // Task 工具
        LEAD_TOOLS.add(tool("create_task", "Create a task and return its runtime-generated ID.",
                Map.of("subject", Map.of("type", "string"),
                       "description", Map.of("type", "string")), List.of("subject")));
        LEAD_TOOLS.add(tool("update_task", "Add dependencies using IDs returned by create_task.",
                Map.of("task_id", Map.of("type", "string"),
                       "addBlockedBy", Map.of("type", "array", "items", Map.of("type", "string"))),
                List.of("task_id", "addBlockedBy")));
        LEAD_TOOLS.add(tool("list_tasks", "List shared tasks.", Map.of(), List.of()));
        LEAD_TOOLS.add(tool("get_task", "Get one task by ID.",
                Map.of("task_id", Map.of("type", "string")), List.of("task_id")));
        LEAD_TOOLS.add(tool("claim_task", "Claim a ready task.",
                Map.of("task_id", Map.of("type", "string")), List.of("task_id")));
        LEAD_TOOLS.add(tool("complete_task", "Complete an owned task.",
                Map.of("task_id", Map.of("type", "string")), List.of("task_id")));

        // Team 工具 (只有 Lead 有)
        LEAD_TOOLS.add(tool("spawn_teammate", "Spawn a persistent teammate.",
                Map.of("name", Map.of("type", "string"),
                       "role", Map.of("type", "string"),
                       "prompt", Map.of("type", "string"),
                       "task_id", Map.of("type", "string"),
                       "require_plan", Map.of("type", "boolean")),
                List.of("name", "role", "prompt")));
        LEAD_TOOLS.add(tool("list_teammates", "List active teammates.", Map.of(), List.of()));
        LEAD_TOOLS.add(tool("send_message", "Message a teammate.",
                Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string")),
                List.of("to", "content")));
        LEAD_TOOLS.add(tool("request_shutdown", "Ask a teammate to shut down.",
                Map.of("teammate", Map.of("type", "string")), List.of("teammate")));
        LEAD_TOOLS.add(tool("request_plan", "Require a teammate plan before workspace changes.",
                Map.of("teammate", Map.of("type", "string"), "task", Map.of("type", "string")),
                List.of("teammate", "task")));
        LEAD_TOOLS.add(tool("review_plan", "Approve or reject a plan.",
                Map.of("request_id", Map.of("type", "string"),
                       "approve", Map.of("type", "boolean"),
                       "feedback", Map.of("type", "string")),
                List.of("request_id", "approve")));
        LEAD_TOOLS.add(tool("create_worktree",
                "Create a git worktree (.worktrees/<name> on branch wt/<name>) and bind it to a "
                        + "pending task. Whoever claims that task gets its file tools defaulted to "
                        + "that directory. Not a sandbox; removal stays with the host.",
                Map.of("name", Map.of("type", "string"),
                       "task_id", Map.of("type", "string")),
                List.of("name", "task_id")));

        // TEAMMATE_TOOLS: 基础 5 + 3 个专属 (send_message / submit_plan / claim/complete)
        TEAMMATE_TOOLS.add(tool("bash", "Run a shell command.",
                Map.of("command", Map.of("type", "string")), List.of("command")));
        TEAMMATE_TOOLS.add(tool("read_file", "Read file contents.",
                Map.of("path", Map.of("type", "string"), "limit", Map.of("type", "integer")), List.of("path")));
        TEAMMATE_TOOLS.add(tool("write_file", "Write content to a file.",
                Map.of("path", Map.of("type", "string"), "content", Map.of("type", "string")),
                List.of("path", "content")));
        TEAMMATE_TOOLS.add(tool("edit_file", "Replace exact text once.",
                Map.of("path", Map.of("type", "string"),
                       "old_text", Map.of("type", "string"),
                       "new_text", Map.of("type", "string")),
                List.of("path", "old_text", "new_text")));
        TEAMMATE_TOOLS.add(tool("glob", "Find files by glob pattern.",
                Map.of("pattern", Map.of("type", "string")), List.of("pattern")));
        TEAMMATE_TOOLS.add(tool("send_message", "Send intermediate message to 'lead' or a teammate.",
                Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string")),
                List.of("to", "content")));
        TEAMMATE_TOOLS.add(tool("submit_plan", "Submit a work plan for Lead approval.",
                Map.of("plan", Map.of("type", "string")), List.of("plan")));
        TEAMMATE_TOOLS.add(tool("list_tasks", "List shared tasks.", Map.of(), List.of()));
        TEAMMATE_TOOLS.add(tool("claim_task", "Claim a ready task.",
                Map.of("task_id", Map.of("type", "string")), List.of("task_id")));
        TEAMMATE_TOOLS.add(tool("complete_task", "Complete owned task.",
                Map.of("task_id", Map.of("type", "string")), List.of("task_id")));
    }

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

    // ══════════════════════════════════════════════════════════════════
    //  ★ s13 核心 6: Lead 主循环 (event-driven, 同时监听 stdin + lead mailbox)
    // ══════════════════════════════════════════════════════════════════

    /** consume lead inbox → 更新 protocol 状态 → 返回原始消息列表 (用于插入 messages) */
    private static List<Map<String, Object>> consumeLeadInbox() {
        List<Map<String, Object>> msgs = BUS.readInbox("lead");
        for (Map<String, Object> msg : msgs) {
            String type = String.valueOf(msg.getOrDefault("type", "message"));
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) msg.getOrDefault("metadata", Map.of());
            if (type.endsWith("_response")) {
                String requestId = String.valueOf(metadata.getOrDefault("request_id", ""));
                ProtocolState state = PENDING_REQUESTS.get(requestId);
                if (state != null && "pending".equals(state.status)) {
                    boolean approve = Boolean.TRUE.equals(metadata.get("approve"));
                    state.status = approve ? "approved" : "rejected";
                }
            }
        }
        return msgs;
    }

    /** 把 team events 格式化成一个 user 消息内容 */
    private static String formatTeamEvents(List<Map<String, Object>> msgs) {
        StringBuilder sb = new StringBuilder("[Team events]\n");
        for (Map<String, Object> msg : msgs) {
            @SuppressWarnings("unchecked")
            Map<String, Object> metadata = (Map<String, Object>) msg.getOrDefault("metadata", Map.of());
            Object rid = metadata.get("request_id");
            String suffix = rid == null ? "" : " request_id=" + rid;
            sb.append("[").append(msg.get("type")).append(suffix).append("] ")
              .append(msg.get("from")).append(": ").append(msg.get("content")).append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private static Message agentLoop(List<MessageParam> messages) {
        while (true) {
            MessageCreateParams.Builder pb = MessageCreateParams.builder()
                    .model(MODEL).system(SYSTEM).maxTokens(8000).temperature(0.3);
            LEAD_TOOLS.forEach(pb::addTool);
            messages.forEach(pb::addMessage);
            MessageCreateParams params = pb.build();

            if (DEBUG_HTTP) System.out.println("\n\n===============>>>>>>>>>>");
            if (DEBUG_HTTP) System.out.println("request is : ");
            if (DEBUG_HTTP) System.out.println(prettyJson(params._body()));

            Message response;
            try {
                response = CLIENT.messages().create(params);
            } catch (Exception e) {
                System.out.println("  [lead error] " + e.getMessage());
                return null;
            }
            if (DEBUG_HTTP) System.out.println("response is : ");
            if (DEBUG_HTTP) System.out.println(prettyJson(response));

            messages.add(assistantToParam(response));
            StopReason stop = response.stopReason().orElse(null);
            if (stop == null || !stop.equals(StopReason.TOOL_USE)) return response;

            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                Optional<ToolUseBlock> mtu = block.toolUse();
                if (mtu.isEmpty()) continue;
                ToolUseBlock tb = mtu.get();
                Map<String, Object> input = JSON_MAPPER.convertValue(tb._input(), new TypeReference<>() {});
                System.out.println("\033[36m> " + tb.name() + " " + input + "\033[0m");
                String output = dispatchLeadTool(tb.name(), input);
                System.out.println(output.length() > 200 ? output.substring(0, 200) : output);
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(tb.id()).content(output).build()));
            }
            messages.add(MessageParam.builder().role(MessageParam.Role.USER)
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
    //  Entry point: event-driven 主循环
    //  简化: 用一个后台线程轮询 lead mailbox, 有消息就 signal 主线程
    //  (Python 用 select.select 同时监听 stdin+mailbox, Java 里做类似的)
    // ══════════════════════════════════════════════════════════════════

    public static void main(String[] args) {
        if (MODEL == null || MODEL.isBlank()) {
            System.err.println("MODEL_ID is not set in .env"); System.exit(1);
        }
        System.out.println("s13: Agent Teams - Lead + 持久 teammates + shared tasks + mailbox");
        System.out.println("Tasks: " + TASKS_DIR);
        System.out.println("Mailboxes: " + MAILBOX_DIR);
        System.out.println("输入问题, 回车发送。输入 q 退出。");
        System.out.println("(注意: 教学版没实现 select.select 那种同时监听 stdin+mailbox, 简化为轮询)\n");

        List<MessageParam> history = new ArrayList<>();

        while (true) {
            System.out.print("\033[36ms13v >> \033[0m");
            // 1. 输入之前, 先看看有没有 lead mailbox 消息 (可能 teammate 发过来了)
            if (BUS.peek("lead")) {
                List<Map<String, Object>> inbox = consumeLeadInbox();
                if (!inbox.isEmpty()) {
                    history.add(MessageParam.builder().role(MessageParam.Role.USER)
                            .content(formatTeamEvents(inbox)).build());
                    System.out.println("\n[wake: " + inbox.size() + " team event(s) -> new turn]");
                    Message r = agentLoop(history);
                    if (r != null) {
                        for (ContentBlock cb : r.content()) {
                            cb.text().ifPresent(t -> System.out.println(t.text()));
                        }
                    }
                    System.out.println();
                    continue;   // 处理完 team event 回到 prompt
                }
            }

            if (!USER_INPUT.hasNextLine()) break;
            String q = USER_INPUT.nextLine().trim();
            if (q.isEmpty() || q.equalsIgnoreCase("q") || q.equalsIgnoreCase("exit")) break;

            // 每轮用户输入前也 consume 一次 mailbox, 把可能积累的 team events 一起处理
            List<Map<String, Object>> inbox = consumeLeadInbox();
            if (!inbox.isEmpty()) {
                history.add(MessageParam.builder().role(MessageParam.Role.USER)
                        .content(formatTeamEvents(inbox)).build());
            }
            history.add(MessageParam.builder().role(MessageParam.Role.USER).content(q).build());

            Message r = agentLoop(history);
            if (r != null) {
                for (ContentBlock cb : r.content()) {
                    cb.text().ifPresent(t -> System.out.println(t.text()));
                }
            }
            System.out.println();
        }
        System.out.println("[shutting down " + TEAMMATE_THREADS.size() + " teammate thread(s)...]");
    }
}
