# s10: Task System — 从执行清单到可协调的任务状态

> ℹ️ 下面的代码片段摘自同目录 `AgentLoop.java`（有删减，完整可运行版见该文件）。
> Java 用不可变的 `record Task` 表示任务，所以每次改状态或依赖都是 `new Task(...)` 生成新对象再 `TASKS.save(...)` 写回文件，
> 而不是像 Python dataclass 那样原地改字段。

s01 → ... → s08 → s09 → `s10` → [s11](../s11_background_tasks/) → s12 → ... → s16 → s17

> *"大目标拆成小任务, 排好序, 持久化"* — 文件持久化的任务图, 多 agent 协作的基础。
>
> **Harness 层**: 任务 — 持久化的目标, 可恢复的进度。

---

## 问题

s05 的 TodoWrite 让 Agent 记录当前任务的执行步骤。清单中的每一项只有内容和状态，用来提醒 Agent 接下来还要做什么。

当项目被拆成创建数据库表、编写 API 和添加测试三个任务时，Harness 还需要知道它们之间的关系：数据库表完成后才能编写 API，API 接口确定后才能添加测试。每个任务还要记录由谁负责。

TodoWrite 没有记录这些依赖和分工。它可以显示“编写 API”仍未完成，但 Harness 无法据此判断这个任务是否可以开始。

本章加入 Task System。每个任务都有独立的 ID 和状态，`blockedBy` 记录前置任务，`owner` 记录负责执行的 Agent。

---

## 解决方案

![Task System Overview](images/task-system-overview.svg)

代码保留 S04 的五个基础工具、Permission、Hooks 和统一的 `dispatchTool` 分发，再加入 6 个任务工具、`.tasks/` 目录持久化和 `blockedBy` 依赖检查。

TodoWrite vs Task System：

| | TodoWrite (s05) | Task System (s10) |
|---|---|---|
| 定位 | 当前任务的执行清单 | 可恢复的任务系统 |
| 存储 | 进程内 / 会话状态 | `.tasks/{id}.json` |
| 依赖 | 无 | `blockedBy` 依赖图 |
| 生命周期 | 当前会话 / 当前任务 | 跨会话保留 |
| 分工 | 不负责任务认领 | `owner` / claim |
| 状态 | pending / in_progress / completed | pending / in_progress / completed |
| 粒度 | Agent 自己的步骤 | 可被认领、追踪、解锁的任务 |
| 更新契约 | 整表替换 | 对单条记录执行创建、读取、更新、列举 |

---

## 工作原理

![Task DAG](images/task-dag.svg)

### Task: 数据结构

每个任务是一个 JSON 文件，存于 `.tasks/` 目录：

```java
/** 任务 id 格式: task_{8 位 hex}, 由系统生成,模型不能自选 */
private static final Pattern TASK_ID_PATTERN = Pattern.compile("^task_[0-9a-f]{8}$");

public record Task(String id,
                   String subject,
                   String description,
                   String status,           // pending | in_progress | completed
                   String owner,            // 负责当前任务的 Agent, 未认领时为 null
                   List<String> blockedBy)  // 依赖的任务 ID 列表
{}
```

ID 使用 `task_` 加 8 位随机十六进制字符生成。创建文件时使用排他写入；如果 ID 已存在，就重新生成。

`TaskStore` 负责校验任务 ID 和读写 JSON 文件，`private static final TaskStore TASKS = new TaskStore(TASKS_DIR);` 是本章使用的任务存储。

### create_task: 创建任务

模型调用 `create_task` 时，`dispatchTool` 转到 `runCreateTask`，它只是包一层 `TASKS.create`：

```java
private static String runCreateTask(String subject, String description) {
    try {
        Task task = TASKS.create(subject, description == null ? "" : description);
        return "Created " + task.id() + ": " + task.subject();
    } catch (Exception e) { return "Error: " + e.getMessage(); }
}
```

`TaskStore.create` 检查 subject，分配随机 ID，再把任务写入 `.tasks/{id}.json`。排他写入靠 `Files.createFile`——文件已存在时它会抛 `FileAlreadyExistsException`，捕获后换一个 ID 重试：

```java
Task create(String subject, String description) throws IOException {
    String s = subject == null ? "" : subject.strip();
    if (s.isEmpty()) throw new IllegalArgumentException("Task subject cannot be empty");
    root(true);

    for (int attempt = 0; attempt < 100; attempt++) {
        byte[] bytes = new byte[4];
        RNG.nextBytes(bytes);                       // SecureRandom
        StringBuilder hex = new StringBuilder("task_");
        for (byte b : bytes) hex.append(String.format("%02x", b));
        String id = hex.toString();
        Path p = pathOf(id, true);
        try {
            Files.createFile(p);                    // O_CREAT | O_EXCL
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
```

新任务的 `blockedBy` 固定为空，工具结果会把运行时生成的 ID 返回给模型。

### update_task: 使用返回的 ID 添加依赖

```java
private static String runUpdateTask(String taskId, Object addBlockedByObj) {
    try {
        if (!(addBlockedByObj instanceof List<?> list)) {
            return "Error: addBlockedBy must be a list";
        }
        List<String> deps = new ArrayList<>();
        for (Object o : list) deps.add(String.valueOf(o));
        Task task = TASKS.updateDependencies(taskId, deps);
        String depsStr = task.blockedBy().isEmpty() ? "(none)" : String.join(", ", task.blockedBy());
        return "Updated " + task.id() + " blockedBy: " + depsStr;
    } catch (Exception e) { return "Error: " + e.getMessage(); }
}
```

任务图采用两阶段构建：先创建所有节点，再使用 `create_task` 返回的 ID 调用 `update_task` 添加边。模型可能在一条回复里同时发出多个工具调用，而这些同级调用在任何工具结果产生前就已经确定，因此某个 `create_task` 无法直接使用另一个调用刚生成的 ID。

`update_task` 会先校验整次修改，再统一保存。目标任务和依赖必须存在，目标必须仍为 pending 且无人认领，并且不能形成自依赖或环。重复添加已有依赖是安全的，不会产生重复边。

这些校验都在 `TaskStore.updateDependencies` 里，环检测交给 `dependsOn`（用栈做迭代 DFS，看 `dep` 是否已经传递依赖 `taskId`）：

```java
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
    // 全部校验通过后才一次性写入
    List<String> newBlockedBy = new ArrayList<>(task.blockedBy());
    for (String dep : deps) if (!newBlockedBy.contains(dep)) newBlockedBy.add(dep);
    Task updated = new Task(task.id(), task.subject(), task.description(),
            task.status(), task.owner(), newBlockedBy);
    save(updated);
    return updated;
}

boolean dependsOn(String taskId, String targetId) throws IOException {
    Deque<String> pending = new ArrayDeque<>();
    Set<String> visited = new HashSet<>();
    pending.push(taskId);
    while (!pending.isEmpty()) {
        String current = pending.pop();
        if (current.equals(targetId)) return true;
        if (!visited.add(current)) continue;
        if (!exists(current)) continue;
        for (String dep : load(current).blockedBy()) pending.push(dep);
    }
    return false;
}
```

### canStart: 依赖检查

一个任务只能在它的 `blockedBy` **全部 completed** 之后才能开始：

```java
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

private static boolean canStart(String taskId) throws IOException {
    return incompleteDependencies(TASKS.load(taskId)).isEmpty();
}
```

`incompleteDependencies` 读取每个前置任务。只要有一个不是 completed，或者对应文件已经不存在（`load` 抛异常），任务就不能认领。

### claim_task: 认领任务

Agent 开始做一个任务时，调用 `claim_task`：设置 `owner`，状态从 `pending` → `in_progress`。`owner` 字段记录谁认领了这个任务：

```java
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
        return "Claimed " + updated.id() + " (" + updated.subject() + ")";
    } catch (Exception e) { return "Error: " + e.getMessage(); }
}
```

如果任务不是 pending，或者依赖没有完成，就拒绝认领。S10 只处理顺序执行的状态更新。`dispatchTool` 调用时 `owner` 固定传 `"agent"`。

### complete_task: 完成与解锁

任务做完后，设为 `completed`。同时扫描所有其他任务，找出**刚刚被解锁**的下游任务：

```java
private static String completeTask(String taskId, String owner) {
    try {
        Task task = TASKS.load(taskId);
        if (!"in_progress".equals(task.status())) {
            return "Task " + taskId + " is " + task.status() + ", cannot complete";
        }
        if (!owner.equals(task.owner())) {
            return "Task " + taskId + " is owned by " + task.owner() + ", not " + owner;
        }
        // 完成前: 记下"有依赖且已经可以启动"的 pending 任务, 作为基准
        Set<String> readyBefore = new HashSet<>();
        for (Task t : TASKS.listAll()) {
            if ("pending".equals(t.status()) && !t.blockedBy().isEmpty()) {
                try { if (canStart(t.id())) readyBefore.add(t.id()); }
                catch (Exception ignore) {}
            }
        }
        Task updated = new Task(task.id(), task.subject(), task.description(),
                "completed", owner, task.blockedBy());
        TASKS.save(updated);
        // 完成后: 找出"这次才变成可启动"的任务
        List<String> unblocked = new ArrayList<>();
        for (Task t : TASKS.listAll()) {
            if ("pending".equals(t.status()) && !t.blockedBy().isEmpty()
                    && !readyBefore.contains(t.id())) {
                try { if (canStart(t.id())) unblocked.add(t.subject()); }
                catch (Exception ignore) {}
            }
        }
        String msg = "Completed " + updated.id() + " (" + updated.subject() + ")";
        if (!unblocked.isEmpty()) {
            msg += "\nUnblocked: " + String.join(", ", unblocked);
        }
        return msg;
    } catch (Exception e) { return "Error: " + e.getMessage(); }
}
```

完成 "schema" 后，"endpoints" 和 "docs" 的 `canStart` 返回 `true`，它们可以开始。

### get_task: 查看完整细节

`list_tasks` 只显示一行摘要。`get_task` 返回完整的任务 JSON，包括 description 和依赖细节。跨会话恢复时，Agent 需要读取完整描述才能继续工作：

```java
private static String runGetTask(String taskId) {
    try { return taskToJson(TASKS.load(taskId)); }
    catch (Exception e) { return "Error: " + e.getMessage(); }
}

/** Task → 带缩进的 JSON (Jackson), 字段顺序与文件里一致 */
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
```

### 状态机: 两个动作，三个状态

```
pending ──claim──→ in_progress ──complete──→ completed
```

这里的 `claim` / `complete` 是动作，`pending` / `in_progress` / `completed` 是状态：

- **claim_task**（`claimTask`）: `pending` → `in_progress`。设置 owner，开始工作。
- **complete_task**（`completeTask`）: `in_progress` → `completed`。把任务标记为完成，并解锁下游。

### 合起来跑

下面这段是示意（`AgentLoop.java` 里没有这个 demo 方法，实际由模型通过工具调用驱动），直接调用上面几个方法走一遍：

```java
// 第一阶段：创建所有节点并取得运行时 ID
Task schema    = TASKS.create("setup database schema", "");
Task endpoints = TASKS.create("create API endpoints", "");
Task tests     = TASKS.create("write tests", "");
Task docs      = TASKS.create("write docs", "");

// 第二阶段：使用返回的 ID 建立依赖边
TASKS.updateDependencies(endpoints.id(), List.of(schema.id()));
TASKS.updateDependencies(tests.id(),     List.of(endpoints.id()));
TASKS.updateDependencies(docs.id(),      List.of(schema.id()));

// Agent 认领第一个可做的任务
claimTask(schema.id(), "agent");        // ✓ Claimed (无依赖)
completeTask(schema.id(), "agent");     // ✓ Completed → 解锁 endpoints, docs (列表顺序按任务 ID 排序)

claimTask(endpoints.id(), "agent");     // ✓ Claimed (schema 已完成)
completeTask(endpoints.id(), "agent");  // ✓ Completed → 解锁 tests

claimTask(docs.id(), "agent");          // ✓ Claimed (schema 已完成)
completeTask(docs.id(), "agent");       // ✓ Completed

claimTask(tests.id(), "agent");         // ✓ Claimed (endpoints 已完成)
completeTask(tests.id(), "agent");      // ✓ Completed
```

每个 `create_task` 写一个 JSON 文件，`update_task`、`claim_task` 和 `complete_task` 更新文件。跨会话时，`.tasks/` 目录还在，Agent 读文件就能恢复进度。

---

## 试一下

```sh
cd learn-claude-code-java
mvn -q compile exec:java -Dexec.mainClass=com.learn.cc.s10_task_system.AgentLoop
```

试试这些 prompt：

1. `Create tasks: setup database schema, create API endpoints (depends on schema), write tests (depends on endpoints), write docs (depends on schema)`
2. `List all tasks and their statuses`
3. `Claim the first unblocked task and complete it`
4. `List tasks again — which ones are now unblocked?`

观察重点：`.tasks/` 目录下是否生成了 JSON 文件？完成任务后，被阻塞的任务是否解锁？

---

## 接下来

任务图有了，但全量测试、安装依赖和部署等命令可能需要很长时间。同步执行这些命令时，Agent Loop 会一直停在当前工具调用上，只有命令结束后才能继续处理其他工作。

s11 Background Tasks → 把慢操作放到后台。Agent 可以继续处理其他任务，后台执行完成后再接收通知。

<!-- translation-sync: zh@v5, en@v5, ja@v5 -->
