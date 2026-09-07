package com.learn.cc.s05_todo_write;

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
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * s05_todo_write - todo_write 工具 + 每 3 轮无 todo 就注入提醒 + Agent Loop。
 */
public final class AgentLoop {

    private static final Dotenv DOTENV = Dotenv.configure().directory("./").ignoreIfMissing().load();
    private static final String API_KEY  = DOTENV.get("ANTHROPIC_API_KEY");
    private static final String BASE_URL = DOTENV.get("ANTHROPIC_BASE_URL");
    private static final String MODEL    = DOTENV.get("MODEL_ID");
    private static final Path WORKDIR = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private static final AnthropicClient CLIENT = buildClient();

    // ── DEBUG_HTTP=1 (环境变量或 .env): 每轮打印发给模型的请求体 + 收到的响应 JSON ──
    private static final boolean DEBUG_HTTP = "1".equals(DOTENV.get("DEBUG_HTTP"));
    private static final JsonMapper TRACE_MAPPER = ObjectMappers.jsonMapper()
            .rebuild().enable(SerializationFeature.INDENT_OUTPUT).build();

    private static void traceHttp(String label, Object body) {
        if (!DEBUG_HTTP) return;
        try {
            System.out.println("\033[90m─── " + label + " ───\n"
                    + TRACE_MAPPER.writeValueAsString(body) + "\033[0m");
        } catch (Exception e) {
            System.out.println("<trace serialize error: " + e.getMessage() + ">");
        }
    }

    private static final JsonMapper JSON_MAPPER = ObjectMappers.jsonMapper();
    private static final Scanner USER_INPUT = new Scanner(System.in);

    private static final String SYSTEM =
            "You are a coding agent at " + WORKDIR + ". "
                    + "Before starting any multi-step task, use todo_write to plan your steps. "
                    + "Update status as you go.";

    // ── TodoManager ────────────────────────────────────────────────
    public enum TodoStatus { PENDING, IN_PROGRESS, COMPLETED }
    public record TodoItem(String content, TodoStatus status) {}
    private static final List<TodoItem> TODOS = new ArrayList<>();

    private static String updateTodos(List<?> raw) {
        if (raw == null) throw new IllegalArgumentException("todos must be a list");
        if (raw.size() > 20) throw new IllegalArgumentException("Max 20 todos allowed");
        List<TodoItem> vs = new ArrayList<>();
        int inProg = 0;
        for (int i = 0; i < raw.size(); i++) {
            if (!(raw.get(i) instanceof Map<?, ?> m))
                throw new IllegalArgumentException("todos[" + i + "] must be an object");
            String content = String.valueOf(m.get("content")).trim();
            String s = String.valueOf(m.get("status")).toLowerCase();
            if (content.isEmpty() || "null".equals(content))
                throw new IllegalArgumentException("todos[" + i + "] requires content");
            TodoStatus st = switch (s) {
                case "pending"     -> TodoStatus.PENDING;
                case "in_progress" -> TodoStatus.IN_PROGRESS;
                case "completed"   -> TodoStatus.COMPLETED;
                default -> throw new IllegalArgumentException("todos[" + i + "] invalid status: " + s);
            };
            if (st == TodoStatus.IN_PROGRESS) inProg++;
            vs.add(new TodoItem(content, st));
        }
        if (inProg > 1) throw new IllegalArgumentException("Only one in_progress allowed");
        TODOS.clear(); TODOS.addAll(vs);
        return renderTodos();
    }

    private static String renderTodos() {
        if (TODOS.isEmpty()) return "No todos.";
        StringBuilder sb = new StringBuilder();
        for (TodoItem t : TODOS) {
            String m = switch (t.status()) {
                case PENDING -> "[ ]"; case IN_PROGRESS -> "[>]"; case COMPLETED -> "[x]";
            };
            sb.append(m).append(" ").append(t.content()).append("\n");
        }
        long done = TODOS.stream().filter(t -> t.status() == TodoStatus.COMPLETED).count();
        return sb.append("\n(").append(done).append("/").append(TODOS.size()).append(" completed)").toString();
    }

    private static String runTodoWrite(Object arg) {
        try {
            if (!(arg instanceof List<?> l)) return "Error: todos must be a list";
            String out = updateTodos(l);
            System.out.println("\n\033[33m## Current Tasks\033[0m\n" + out);
            return out;
        } catch (IllegalArgumentException e) {
            return "Error: " + e.getMessage();
        }
    }

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
            Tool.builder()
                    .name("todo_write")
                    .description("Create and manage a task list for your current coding session.")
                    .inputSchema(Tool.InputSchema.builder()
                            .properties(JsonValue.from(Map.of(
                                    "todos", Map.of(
                                            "type", "array",
                                            "maxItems", 20,
                                            "items", Map.of(
                                                    "type", "object",
                                                    "properties", Map.of(
                                                            "content", Map.of("type", "string", "minLength", 1),
                                                            "status", Map.of("type", "string",
                                                                    "enum", List.of("pending", "in_progress", "completed"))
                                                    ),
                                                    "required", List.of("content", "status")
                                            )
                                    )
                            )))
                            .putAdditionalProperty("required", JsonValue.from(List.of("todos")))
                            .build())
                    .build()
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

    // ── Tools ─────────────────────────────────────────────────────────
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

    private static String dispatchTool(String name, Map<String, Object> input) {
        return switch (name) {
            case "bash"       -> runBash((String) input.get("command"));
            case "read_file"  -> runRead((String) input.get("path"), asInteger(input.get("limit")));
            case "write_file" -> runWrite((String) input.get("path"), (String) input.get("content"));
            case "edit_file"  -> runEdit((String) input.get("path"),
                                         (String) input.get("old_text"),
                                         (String) input.get("new_text"));
            case "glob"       -> runGlob((String) input.get("pattern"));
            case "todo_write" -> runTodoWrite(input.get("todos"));
            default           -> "Unknown tool: " + name;
        };
    }

    private static Integer asInteger(Object v) { return (v instanceof Number n) ? n.intValue() : null; }

    // ── Hooks (与 s04 相同) ─────────────────────────────────────
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
    private static final Pattern DESTRUCTIVE_CMD = Pattern.compile(
            "(?i)(?:^|[;&|()\\n])\\s*(?:rm|del)(?=\\s|$|[;&|()])");
    private static final List<String> DESTRUCTIVE = List.of("rm ", "> /etc/", "chmod 777");

    @SuppressWarnings("unchecked")
    private static String permissionHook(Object[] args) {
        ToolUseBlock block = (ToolUseBlock) args[0];
        Map<String, Object> input = (Map<String, Object>) args[1];
        if ("bash".equals(block.name())) {
            String cmd = (String) input.getOrDefault("command", "");
            for (String p : DENY_LIST) if (cmd.contains(p)) return "Permission denied by deny list";
            if (DESTRUCTIVE_CMD.matcher(cmd).find() || DESTRUCTIVE.stream().anyMatch(cmd::contains)) {
                System.out.print("\n\033[33m[permission] destructive command Allow? [y/N] \033[0m");
                String c = USER_INPUT.hasNextLine() ? USER_INPUT.nextLine().trim().toLowerCase() : "";
                if (!(c.equals("y") || c.equals("yes"))) return "Permission denied by user";
            }
        }
        if (List.of("read_file", "write_file", "edit_file").contains(block.name())) {
            String path = (String) input.getOrDefault("path", "");
            if (!WORKDIR.resolve(path).toAbsolutePath().normalize().startsWith(WORKDIR)) {
                System.out.print("\n\033[33m[permission] outside workspace Allow? [y/N] \033[0m");
                String c = USER_INPUT.hasNextLine() ? USER_INPUT.nextLine().trim().toLowerCase() : "";
                if (!(c.equals("y") || c.equals("yes"))) return "Permission denied by user";
            }
        }
        return null;
    }

    static { registerHook(HookEvent.PRE_TOOL_USE, AgentLoop::permissionHook); }

    // ── Agent Loop (stream) ────────────────────────────────────────
    private static final int REMINDER_ROUNDS = 3;

    private static Message agentLoop(MessageCreateParams.Builder paramsBuilder) {
        int roundsSinceTodo = 0;
        while (true) {
            MessageCreateParams params = paramsBuilder.build();
            traceHttp("request", params._body());
            Message response = CLIENT.messages().create(params);
            traceHttp("response", response);
            paramsBuilder.addMessage(response);

            StopReason stop = response.stopReason().orElse(null);
            if (stop == null || !stop.equals(StopReason.TOOL_USE)) return response;

            boolean[] usedTodoRef = {false};
            List<ContentBlockParam> results = new ArrayList<>(response.content().stream()
                    .flatMap(cb -> cb.toolUse().stream())
                    .map(b -> {
                        if ("todo_write".equals(b.name())) usedTodoRef[0] = true;
                        return processToolCall(b);
                    })
                    .toList());

            roundsSinceTodo = usedTodoRef[0] ? 0 : roundsSinceTodo + 1;
            if (roundsSinceTodo >= REMINDER_ROUNDS) {
                results.add(ContentBlockParam.ofText(TextBlockParam.builder()
                        .text("<reminder>Update your todos.</reminder>").build()));
                System.out.println("\033[35m[nag] reminder injected\033[0m");
                roundsSinceTodo = 0;
            }

            paramsBuilder.addMessage(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(results)
                    .build());
        }
    }

    private static ContentBlockParam processToolCall(ToolUseBlock block) {
        Map<String, Object> input = JSON_MAPPER.convertValue(
                block._input(), new TypeReference<Map<String, Object>>() {});
        System.out.println("\033[36m> " + block.name() + "\033[0m");
        String blocked = triggerHooks(HookEvent.PRE_TOOL_USE, block, input);
        String content = (blocked != null) ? blocked : dispatchTool(block.name(), input);
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(block.id()).content(content).build());
    }

    public static void main(String[] args) {
        if (MODEL == null || MODEL.isBlank()) { System.err.println("MODEL_ID missing"); System.exit(1); }
        System.out.println("s05: TodoWrite");
        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(MODEL).system(SYSTEM).maxTokens(8000).temperature(0.3);
        TOOLS.forEach(paramsBuilder::addTool);
        while (true) {
            System.out.print("\033[36ms05 >> \033[0m");
            if (!USER_INPUT.hasNextLine()) break;
            String q = USER_INPUT.nextLine().trim();
            if (q.isEmpty() || q.equalsIgnoreCase("q") || q.equalsIgnoreCase("exit")) break;
            paramsBuilder.addUserMessage(q);
            Message r = agentLoop(paramsBuilder);
            r.content().stream().flatMap(cb -> cb.text().stream()).map(TextBlock::text).forEach(System.out::println);
            System.out.println();
        }
    }
}
