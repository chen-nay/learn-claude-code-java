package com.learn.cc.s07_skill_loading;

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
import org.yaml.snakeyaml.Yaml;

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
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * s07_skill_loading - 渐进式 skill 加载 (扫描 SKILL.md / load_skill 拉全文) + Agent Loop。
 */
public final class AgentLoop {

    private static final Dotenv DOTENV = Dotenv.configure().directory("./").ignoreIfMissing().load();
    private static final String API_KEY  = DOTENV.get("ANTHROPIC_API_KEY");
    private static final String BASE_URL = DOTENV.get("ANTHROPIC_BASE_URL");
    private static final String MODEL    = DOTENV.get("MODEL_ID");

    private static final Path WORKDIR = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
    private static final Path SKILLS_DIR = WORKDIR.resolve("skills");
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

    // ── Skill Registry ────────────────────────────────────────────────
    private record SkillEntry(String name, String description, String content) {}
    private static final Map<String, SkillEntry> SKILL_REGISTRY = new TreeMap<>();
    static { scanSkills(); }

    private static void scanSkills() {
        if (!Files.isDirectory(SKILLS_DIR)) return;
        try (Stream<Path> subdirs = Files.list(SKILLS_DIR)) {
            subdirs.filter(Files::isDirectory)
                    .sorted(Comparator.naturalOrder())
                    .forEach(dir -> {
                        Path manifest = dir.resolve("SKILL.md");
                        if (!Files.isRegularFile(manifest)) return;
                        try {
                            SKILL_REGISTRY.put(
                                    parseSkill(Files.readString(manifest), dir.getFileName().toString()).name(),
                                    parseSkill(Files.readString(manifest), dir.getFileName().toString())
                            );
                        } catch (IOException ignore) {}
                    });
        } catch (IOException ignore) {}
    }

    private static SkillEntry parseSkill(String raw, String dirName) {
        String name = dirName, desc = firstNonEmpty(raw), content = raw;
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
        return new SkillEntry(name, desc, content);
    }

    private static String firstNonEmpty(String s) {
        for (String line : s.split("\n")) {
            String t = line.replaceFirst("^#+\\s*", "").trim();
            if (!t.isEmpty()) return t;
        }
        return "(no description)";
    }

    private static String listSkills() {
        if (SKILL_REGISTRY.isEmpty()) return "(no skills found)";
        return SKILL_REGISTRY.values().stream()
                .map(s -> "- **" + s.name() + "**: " + s.description().split("\n", 2)[0].trim())
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static final String SYSTEM =
            "You are a coding agent at " + WORKDIR + ".\n"
                    + "Skills available:\n" + listSkills() + "\n"
                    + "Use load_skill to get full details when needed.";

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
                    Map.of("pattern", Map.of("type", "string")), List.of("pattern")),
            tool("load_skill", "Load the full content of a skill by name.",
                    Map.of("name", Map.of("type", "string")), List.of("name"))
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

    // ── Tool impls ────────────────────────────────────────────────────
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

    private static String runLoadSkill(String name) {
        SkillEntry s = SKILL_REGISTRY.get(name);
        if (s == null) return "Skill not found: " + name
                + ". Available: " + String.join(", ", SKILL_REGISTRY.keySet());
        return s.content();
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
            case "load_skill" -> runLoadSkill((String) input.get("name"));
            default           -> "Unknown tool: " + name;
        };
    }

    private static Integer asInteger(Object v) { return (v instanceof Number n) ? n.intValue() : null; }

    private static final List<String> DENY_LIST = List.of(
            "rm -rf /", "sudo", "shutdown", "reboot", "mkfs", "dd if=", "> /dev/sda"
    );

    // ── Agent Loop (stream 版) ─────────────────────────────────────
    private static Message agentLoop(MessageCreateParams.Builder paramsBuilder) {
        while (true) {
            MessageCreateParams params = paramsBuilder.build();
            traceHttp("request", params._body());
            Message response = CLIENT.messages().create(params);
            traceHttp("response", response);
            paramsBuilder.addMessage(response);

            StopReason stop = response.stopReason().orElse(null);
            if (stop == null || !stop.equals(StopReason.TOOL_USE)) return response;

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
        System.out.println("\033[36m> " + block.name() + " " + input + "\033[0m");

        String content;
        if ("bash".equals(block.name())) {
            String cmd = (String) input.getOrDefault("command", "");
            String deny = DENY_LIST.stream().filter(cmd::contains).findFirst().orElse(null);
            if (deny != null) {
                System.out.println("\033[31m⛔ blocked: " + deny + "\033[0m");
                content = "Permission denied: " + deny;
                return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(block.id()).content(content).build());
            }
        }
        content = dispatchTool(block.name(), input);
        if ("load_skill".equals(block.name())) {
            String skillName = (String) input.get("name");
            boolean hit = SKILL_REGISTRY.containsKey(skillName);
            System.out.println("\033[35m  [skill] " + skillName
                    + (hit ? " loaded, " + content.length() + " chars" : " NOT FOUND") + "\033[0m");
        }
        System.out.println(content.length() > 200 ? content.substring(0, 200) : content);
        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(block.id()).content(content).build());
    }

    public static void main(String[] args) {
        if (MODEL == null || MODEL.isBlank()) {
            System.err.println("MODEL_ID is not set in .env"); System.exit(1);
        }
        System.out.println("s07: Skill Loading");
        System.out.println("已加载 " + SKILL_REGISTRY.size() + " 个 skill: "
                + String.join(", ", SKILL_REGISTRY.keySet()));
        System.out.println("输入问题, 回车发送。输入 q 退出。\n");

        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(MODEL).system(SYSTEM).maxTokens(8000).temperature(0.3);
        TOOLS.forEach(paramsBuilder::addTool);

        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("\033[36ms07 >> \033[0m");
            if (!sc.hasNextLine()) break;
            String q = sc.nextLine().trim();
            if (q.isEmpty() || q.equalsIgnoreCase("q") || q.equalsIgnoreCase("exit")) break;

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
