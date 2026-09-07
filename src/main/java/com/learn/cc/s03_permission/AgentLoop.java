package com.learn.cc.s03_permission;

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
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * s03_permission - 三道门 (deny-list / 规则匹配 / 人工确认) + Agent Loop。
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
            "You are a coding agent at " + WORKDIR
                    + ". All destructive operations require user approval.";

    private static final List<Tool> TOOLS = List.of(
            tool("bash", "Run a shell command.",
                    Map.of("command", Map.of("type", "string")), List.of("command")),
            tool("read_file", "Read file contents.",
                    Map.of("path",  Map.of("type", "string"),
                           "limit", Map.of("type", "integer")), List.of("path")),
            tool("write_file", "Write content to a file.",
                    Map.of("path",    Map.of("type", "string"),
                           "content", Map.of("type", "string")), List.of("path", "content")),
            tool("edit_file", "Replace exact text in a file once.",
                    Map.of("path",     Map.of("type", "string"),
                           "old_text", Map.of("type", "string"),
                           "new_text", Map.of("type", "string")),
                    List.of("path", "old_text", "new_text")),
            tool("glob", "Find files matching a glob pattern.",
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

    // ── Tools (与 s02 相同, 无路径/危险检查, 全部交给 Gate 处理) ─────
    private static String runBash(String command) {
        try {
            Process p = new ProcessBuilder("bash", "-c", command)
                    .directory(new File(WORKDIR.toString()))
                    .redirectErrorStream(true).start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "Error: Timeout (120s)";
            }
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

    // ── Permission (三道门, 与 Verbose 版同) ─────────────────────────
    private static final List<String> DENY_LIST = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=", "> /dev/sda"
    );

    private record Rule(List<String> tools, Predicate<Map<String, Object>> check, String message) {}

    private static final List<Rule> PERMISSION_RULES = List.of(
            new Rule(List.of("read_file", "write_file", "edit_file"),
                    args -> {
                        String p = (String) args.getOrDefault("path", "");
                        return !WORKDIR.resolve(p).toAbsolutePath().normalize().startsWith(WORKDIR);
                    },
                    "Writing outside workspace"),
            new Rule(List.of("bash"),
                    args -> {
                        String cmd = (String) args.getOrDefault("command", "");
                        return cmd.contains("rm ") || cmd.contains("> /etc/") || cmd.contains("chmod 777");
                    },
                    "Potentially destructive command")
    );

    private enum Decision { ALLOW, DENY }

    private static boolean checkPermission(ToolUseBlock block, Map<String, Object> input) {
        if ("bash".equals(block.name())) {
            String cmd = (String) input.getOrDefault("command", "");
            for (String p : DENY_LIST) {
                if (cmd.contains(p)) {
                    System.out.println("\n\033[31m⛔ Blocked: '" + p + "' is on the deny list\033[0m");
                    return false;
                }
            }
        }
        for (Rule r : PERMISSION_RULES) {
            if (r.tools().contains(block.name()) && r.check().test(input)) {
                System.out.println("\n\033[33m⚠  " + r.message() + "\033[0m");
                System.out.println("   Tool: " + block.name() + "(" + input + ")");
                System.out.print("   Allow? [y/N] ");
                String choice = USER_INPUT.hasNextLine() ? USER_INPUT.nextLine().trim().toLowerCase() : "";
                if (!(choice.equals("y") || choice.equals("yes"))) return false;
                break;
            }
        }
        return true;
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

    /** 处理一个 tool_use: 权限通过就执行,拒绝就回喂 denied。永远返回一个 tool_result 块。*/
    private static ContentBlockParam processToolCall(ToolUseBlock block) {
        Map<String, Object> input = JSON_MAPPER.convertValue(
                block._input(), new TypeReference<Map<String, Object>>() {});
        System.out.println("\033[36m> " + block.name() + "\033[0m");

        String content;
        if (!checkPermission(block, input)) {
            content = "Permission denied.";
        } else {
            content = dispatchTool(block.name(), input);
            System.out.println(content.length() > 200 ? content.substring(0, 200) : content);
        }
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(block.id())
                .content(content)
                .build());
    }

    public static void main(String[] args) {
        if (MODEL == null || MODEL.isBlank()) {
            System.err.println("MODEL_ID is not set in .env");
            System.exit(1);
        }
        System.out.println("s03: Permission");
        System.out.println("输入问题, 回车发送。输入 q 退出。\n");

        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(MODEL).system(SYSTEM).maxTokens(8000);
        TOOLS.forEach(paramsBuilder::addTool);

        while (true) {
            System.out.print("\033[36ms03 >> \033[0m");
            if (!USER_INPUT.hasNextLine()) break;
            String query = USER_INPUT.nextLine().trim();
            if (query.isEmpty() || query.equalsIgnoreCase("q") || query.equalsIgnoreCase("exit")) break;

            paramsBuilder.addUserMessage(query);
            Message finalResponse = agentLoop(paramsBuilder);
            finalResponse.content().stream()
                    .flatMap(cb -> cb.text().stream())
                    .map(TextBlock::text)
                    .forEach(System.out::println);
            System.out.println();
        }
    }
}
