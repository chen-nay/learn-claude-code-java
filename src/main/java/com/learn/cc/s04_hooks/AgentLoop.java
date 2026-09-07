package com.learn.cc.s04_hooks;

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
 * s04_hooks - 可插拔 hook 系统 (UserPromptSubmit / PreToolUse / PostToolUse / Stop) + Agent Loop。
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
            "You are a coding agent at " + WORKDIR + ". Use tools to solve tasks. Act, don't explain.";

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
            default           -> "Unknown tool: " + name;
        };
    }

    private static Integer asInteger(Object v) { return (v instanceof Number n) ? n.intValue() : null; }

    // ── Hooks ────────────────────────────────────────────────────────
    public enum HookEvent { USER_PROMPT_SUBMIT, PRE_TOOL_USE, POST_TOOL_USE, STOP }
    public interface HookCallback extends Function<Object[], String> {}
    private static final Map<HookEvent, List<HookCallback>> HOOKS = new EnumMap<>(HookEvent.class);
    static { for (HookEvent e : HookEvent.values()) HOOKS.put(e, new ArrayList<>()); }

    public static void registerHook(HookEvent event, HookCallback cb) { HOOKS.get(event).add(cb); }

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
        String toolName = block.name();

        if ("bash".equals(toolName)) {
            String cmd = (String) input.getOrDefault("command", "");
            for (String p : DENY_LIST) if (cmd.contains(p)) {
                System.out.println("\n\033[31m[blocked] '" + p + "'\033[0m");
                return "Permission denied by deny list";
            }
            boolean destructive = DESTRUCTIVE_CMD.matcher(cmd).find()
                    || DESTRUCTIVE.stream().anyMatch(cmd::contains);
            if (destructive) {
                System.out.println("\n\033[33m[permission] Potentially destructive command\033[0m");
                System.out.println("   Tool: " + toolName + "(" + input + ")");
                System.out.print("   Allow? [y/N] ");
                String choice = USER_INPUT.hasNextLine() ? USER_INPUT.nextLine().trim().toLowerCase() : "";
                if (!(choice.equals("y") || choice.equals("yes"))) return "Permission denied by user";
            }
        }
        if (List.of("read_file", "write_file", "edit_file").contains(toolName)) {
            String path = (String) input.getOrDefault("path", "");
            if (!WORKDIR.resolve(path).toAbsolutePath().normalize().startsWith(WORKDIR)) {
                System.out.println("\n\033[33m[permission] Access outside workspace\033[0m");
                System.out.println("   Tool: " + toolName + "(" + input + ")");
                System.out.print("   Allow? [y/N] ");
                String choice = USER_INPUT.hasNextLine() ? USER_INPUT.nextLine().trim().toLowerCase() : "";
                if (!(choice.equals("y") || choice.equals("yes"))) return "Permission denied by user";
            }
        }
        return null;
    }

    private static String logHook(Object[] args) {
        ToolUseBlock block = (ToolUseBlock) args[0];
        System.out.println("\033[90m[HOOK] " + block.name() + "\033[0m");
        return null;
    }

    private static String largeOutputHook(Object[] args) {
        ToolUseBlock block = (ToolUseBlock) args[0];
        String output = (String) args[2];
        if (output.length() > 100_000) {
            System.out.println("\033[33m[HOOK] Large output " + block.name()
                    + ": " + output.length() + " chars\033[0m");
        }
        return null;
    }

    private static String contextInjectHook(Object[] args) {
        System.out.println("\033[90m[HOOK] UserPromptSubmit: working in " + WORKDIR + "\033[0m");
        return null;
    }

    private static String summaryHook(Object[] args) {
        System.out.println("\033[90m[HOOK] Stop: loop exit\033[0m");
        return null;
    }

    static {
        registerHook(HookEvent.USER_PROMPT_SUBMIT, AgentLoop::contextInjectHook);
        registerHook(HookEvent.PRE_TOOL_USE, AgentLoop::permissionHook);
        registerHook(HookEvent.PRE_TOOL_USE, AgentLoop::logHook);
        registerHook(HookEvent.POST_TOOL_USE, AgentLoop::largeOutputHook);
        registerHook(HookEvent.STOP, AgentLoop::summaryHook);
    }

    // ── Agent Loop (stream 版) ─────────────────────────────────────
    private static Message agentLoop(MessageCreateParams.Builder paramsBuilder) {
        while (true) {
            MessageCreateParams params = paramsBuilder.build();
            traceHttp("request", params._body());
            Message response = CLIENT.messages().create(params);
            traceHttp("response", response);
            paramsBuilder.addMessage(response);

            StopReason stop = response.stopReason().orElse(null);
            if (stop == null || !stop.equals(StopReason.TOOL_USE)) {
                String force = triggerHooks(HookEvent.STOP);
                if (force != null) { paramsBuilder.addUserMessage(force); continue; }
                return response;
            }

            List<ContentBlockParam> results = response.content().stream()
                    .flatMap(cb -> cb.toolUse().stream())
                    .map(AgentLoop::processToolCall)
                    .toList();

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
        String content;
        if (blocked != null) {
            content = blocked;
        } else {
            content = dispatchTool(block.name(), input);
            triggerHooks(HookEvent.POST_TOOL_USE, block, input, content);
            System.out.println(content.length() > 200 ? content.substring(0, 200) : content);
        }
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(block.id()).content(content).build());
    }

    public static void main(String[] args) {
        if (MODEL == null || MODEL.isBlank()) {
            System.err.println("MODEL_ID is not set in .env"); System.exit(1);
        }
        System.out.println("s04: Hooks");
        System.out.println("输入问题, 回车发送。输入 q 退出。\n");

        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(MODEL).system(SYSTEM).maxTokens(8000).temperature(0.3);
        TOOLS.forEach(paramsBuilder::addTool);

        while (true) {
            System.out.print("\033[36ms04 >> \033[0m");
            if (!USER_INPUT.hasNextLine()) break;
            String q = USER_INPUT.nextLine().trim();
            if (q.isEmpty() || q.equalsIgnoreCase("q") || q.equalsIgnoreCase("exit")) break;

            triggerHooks(HookEvent.USER_PROMPT_SUBMIT, q);
            paramsBuilder.addUserMessage(q);
            Message finalResponse = agentLoop(paramsBuilder);
            finalResponse.content().stream()
                    .flatMap(cb -> cb.text().stream())
                    .map(TextBlock::text)
                    .forEach(System.out::println);
            System.out.println();
        }
    }
}
