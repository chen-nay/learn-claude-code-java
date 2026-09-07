package com.learn.cc.s14_mcp_plugin;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * s14_mcp_plugin - 主实现
 *
 * s13 → s14 的核心变化: 【MCP (Model Context Protocol) 工具的动态发现与加载】
 * 跟 s13 agent teams 是【独立的两个方向】。
 *
 * 心智模型:
 *   agent 通过 connect_mcp("server_name") 连接外部 MCP server
 *   → server 返回一堆工具描述 (tools/list)
 *   → 主循环下一轮组装工具池时【动态加入】这些工具
 *   → 模型看到 mcp__docs__search / mcp__docs__get_version 等新工具
 *   → 调用这些工具时 dispatch 到对应 server 的 tools/call
 *
 * 5 个核心机制:
 *
 *   1) MCPClient 类
 *      模拟一个 MCP server 的最小抽象
 *      tools: List<Map>            工具定义 (name/description/inputSchema)
 *      handlers: Map<name, fn>     实际执行函数
 *      callTool(name, args)        运行时调用
 *
 *   2) mock 两个 server: "docs" 和 "deploy"
 *      docs.search(query) / docs.get_version()
 *      deploy.trigger(service) / deploy.status(service)
 *      教学: 真实 MCP 通过 stdio/HTTP 协议连接远程 process,
 *      这里省成 in-process factory
 *
 *   3) connect_mcp 工具
 *      模型主动调 → 把 server 实例存进 MCP_CLIENTS
 *      从此以后 assembleToolPool 就会带上这些工具
 *
 *   4) assembleToolPool() 动态组装
 *      ★ 每轮 LLM 调用前重新组装:
 *      内置 5+1 工具 + 所有已连接 MCP server 的工具
 *      MCP 工具名改成 mcp__<server>__<tool> (Anthropic 惯例)
 *      校验: 名字唯一、schema 合法、长度不超 64 字符
 *
 *   5) MCP_HOST_POLICY 宿主级授权
 *      ("docs", "search") → "allow"   直接放行
 *      ("deploy", "trigger") → "confirm"  需用户 y/N 确认
 *      ★ 核心安全设计: 授权来自 host 配置, 不来自 server 描述
 *      (server 可能撒谎说自己是安全的, host 必须自己判断)
 *
 * ★ 为什么工具池要每轮都重组:
 *   模型可能在第 1 轮 connect_mcp 连接了 docs
 *   第 2 轮 request 时才能看到 mcp__docs__search 已经在 tools 列表里
 *   → 动态发现的核心, "工具池随连接状态变化"
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
    private static final AnthropicClient CLIENT = buildClient();
    private static final Scanner USER_INPUT = new Scanner(System.in);

    private static final String BASE_SYSTEM =
            "You are a coding agent at " + WORKDIR + ". Use built-in and connected MCP tools "
                    + "to solve tasks. Call connect_mcp before using a server.";

    // ══════════════════════════════════════════════════════════════════
    //  ★ s14 核心 1: MCPClient - 一个 MCP server 的最小抽象
    // ══════════════════════════════════════════════════════════════════

    /**
     * MCP server 提供的一个工具定义 (对应 Anthropic tool schema, 但 key 名称
     * 用 MCP 协议惯例: inputSchema 而不是 input_schema)
     */
    public record McpToolDef(String name, String description, Map<String, Object> inputSchema) {}

    /**
     * MCP handler: 接收 kwargs 形式的参数 Map, 返回结果字符串
     * (对应 Python 的 def handler(**kwargs) -> str)
     */
    @FunctionalInterface
    public interface McpHandler extends Function<Map<String, Object>, String> {}

    /** 一个 MCP server 的 in-process 实现 */
    static final class MCPClient {
        final String name;
        final List<McpToolDef> tools = new ArrayList<>();
        final Map<String, McpHandler> handlers = new LinkedHashMap<>();

        MCPClient(String name) { this.name = name; }

        /** 注册工具 + handlers, 校验一致性 */
        void register(List<McpToolDef> toolDefs, Map<String, McpHandler> handlerMap) {
            java.util.Set<String> names = new java.util.HashSet<>();
            for (McpToolDef td : toolDefs) {
                if (td.name() == null || td.name().isEmpty()) {
                    throw new IllegalArgumentException("Every MCP tool needs a non-empty name");
                }
                if (!names.add(td.name())) {
                    throw new IllegalArgumentException("Duplicate MCP tool name on server " + this.name);
                }
                if (!handlerMap.containsKey(td.name())) {
                    throw new IllegalArgumentException("Missing MCP handler: " + td.name());
                }
            }
            this.tools.addAll(toolDefs);
            this.handlers.putAll(handlerMap);
        }

        /** 运行时调用: 找到 handler, 执行, 返回字符串 */
        String callTool(String toolName, Map<String, Object> args) {
            McpHandler h = handlers.get(toolName);
            if (h == null) return "MCP error: unknown tool '" + toolName + "'";
            try {
                return h.apply(args);
            } catch (Exception e) {
                return "MCP error: " + e.getClass().getSimpleName() + ": " + e.getMessage();
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s14 核心 2: 两个 mock server + 名字规范化
    // ══════════════════════════════════════════════════════════════════

    private static final Pattern DISALLOWED_CHARS = Pattern.compile("[^a-zA-Z0-9_-]");

    /** 把 server/tool 名字里的非法字符替换成下划线 (Anthropic tool name 只允许 [a-zA-Z0-9_-]) */
    private static String normalizeMcpName(String name) {
        String normalized = DISALLOWED_CHARS.matcher(name).replaceAll("_");
        if (normalized.isEmpty()) throw new IllegalArgumentException("MCP name normalizes to empty");
        return normalized;
    }

    /** docs server: 只读的两个工具 */
    private static MCPClient mockServerDocs() {
        MCPClient server = new MCPClient("docs");
        server.register(
                List.of(
                        new McpToolDef("search", "Search the documentation.",
                                Map.of("type", "object",
                                        "properties", Map.of("query", Map.of("type", "string")),
                                        "required", List.of("query"))),
                        new McpToolDef("get_version", "Get the documentation API version.",
                                Map.of("type", "object", "properties", Map.of()))
                ),
                Map.of(
                        "search", args -> "[docs] Found 3 results for '" + args.get("query") + "'",
                        "get_version", args -> "[docs] API v2.1.0"
                )
        );
        return server;
    }

    /** deploy server: 有个破坏性工具 (trigger) */
    private static MCPClient mockServerDeploy() {
        MCPClient server = new MCPClient("deploy");
        server.register(
                List.of(
                        new McpToolDef("trigger", "Trigger a deployment.",
                                Map.of("type", "object",
                                        "properties", Map.of("service", Map.of("type", "string")),
                                        "required", List.of("service"))),
                        new McpToolDef("status", "Check deployment status.",
                                Map.of("type", "object",
                                        "properties", Map.of("service", Map.of("type", "string")),
                                        "required", List.of("service")))
                ),
                Map.of(
                        "trigger", args -> "[deploy] Triggered: " + args.get("service"),
                        "status", args -> "[deploy] " + args.get("service") + ": running (v1.4.2)"
                )
        );
        return server;
    }

    /** name → server 工厂 (对应 Python MOCK_SERVERS 字典) */
    private static final Map<String, Supplier<MCPClient>> MOCK_SERVERS = Map.of(
            "docs", AgentLoop::mockServerDocs,
            "deploy", AgentLoop::mockServerDeploy
    );

    // ══════════════════════════════════════════════════════════════════
    //  ★ s14 核心 3: MCP 连接状态 + connect_mcp 工具
    // ══════════════════════════════════════════════════════════════════

    /** 已连接的 MCP server (server_name → client) */
    private static final Map<String, MCPClient> MCP_CLIENTS = new LinkedHashMap<>();

    /** MCP 工具的授权策略 (prefixed_name → allow/confirm/deny) 由 assembleToolPool 填充 */
    private static final Map<String, String> MCP_TOOL_POLICIES = new LinkedHashMap<>();

    /**
     * ★ Host-level 授权策略表
     * 授权信息来源: host 配置文件 (硬编码于此), 而不是 server 自报
     * 关键设计: server 可能撒谎说 "readOnlyHint=true", host 必须自己判断
     * key: (server_name, tool_name) → "allow" 或 "confirm"
     * 未列出的 MCP 工具默认 "confirm"
     */
    private record ServerToolKey(String serverName, String toolName) {}
    private static final Map<ServerToolKey, String> MCP_HOST_POLICY = Map.of(
            new ServerToolKey("docs", "search"), "allow",
            new ServerToolKey("docs", "get_version"), "allow",
            new ServerToolKey("deploy", "status"), "allow",
            new ServerToolKey("deploy", "trigger"), "confirm"    // 破坏性,要用户确认
    );

    /** connect_mcp 工具的 handler */
    private static String runConnectMcp(String name) {
        if (MCP_CLIENTS.containsKey(name)) {
            return "MCP server '" + name + "' already connected";
        }
        Supplier<MCPClient> factory = MOCK_SERVERS.get(name);
        if (factory == null) {
            return "Unknown server '" + name + "'. Available: "
                    + String.join(", ", MOCK_SERVERS.keySet());
        }
        MCPClient server = factory.get();
        MCP_CLIENTS.put(name, server);
        String toolNames = server.tools.stream().map(McpToolDef::name)
                .reduce((a, b) -> a + ", " + b).orElse("");
        System.out.println("  \033[35m[mcp]\033[0m connected: " + name + " -> " + toolNames);
        return "Connected to MCP server '" + name + "'. Discovered "
                + server.tools.size() + " tools: " + toolNames;
    }

    // ══════════════════════════════════════════════════════════════════
    //  ★ s14 核心 4: assembleToolPool - 动态组装工具池
    // ══════════════════════════════════════════════════════════════════

    /** 返回值: 当前的工具列表 + 分发表 */
    public record ToolPool(List<Tool> tools, Map<String, Function<Map<String, Object>, String>> handlers) {}

    /**
     * ★ 每轮 LLM 调用前调用: 组装内置工具 + 已连接 MCP 工具
     * MCP 工具名改成 mcp__<server>__<tool> 后加入池
     * 顺便填充 MCP_TOOL_POLICIES 供 permission hook 使用
     */
    private static ToolPool assembleToolPool() {
        List<Tool> tools = new ArrayList<>(BUILTIN_TOOLS);
        Map<String, Function<Map<String, Object>, String>> handlers = new LinkedHashMap<>(BUILTIN_HANDLERS);
        Map<String, String> origins = new LinkedHashMap<>();
        for (Tool t : tools) origins.put(t.name(), "built-in tool " + t.name());

        MCP_TOOL_POLICIES.clear();

        for (Map.Entry<String, MCPClient> entry : MCP_CLIENTS.entrySet()) {
            String serverName = entry.getKey();
            MCPClient server = entry.getValue();
            String safeServer = normalizeMcpName(serverName);

            for (McpToolDef td : server.tools) {
                String rawName = td.name();
                String safeTool = normalizeMcpName(rawName);
                String prefixed = "mcp__" + safeServer + "__" + safeTool;

                if (prefixed.length() > 64) {
                    throw new IllegalArgumentException("MCP tool name longer than 64 chars: " + prefixed);
                }
                String origin = "MCP tool '" + serverName + "/" + rawName + "'";
                if (origins.containsKey(prefixed)) {
                    throw new IllegalArgumentException("MCP tool name collision after normalization: "
                            + prefixed + " maps both " + origins.get(prefixed) + " and " + origin);
                }
                Map<String, Object> schema = td.inputSchema();
                if (schema == null || !"object".equals(schema.getOrDefault("type", "object"))) {
                    throw new IllegalArgumentException("Invalid input schema for " + origin);
                }
                origins.put(prefixed, origin);

                // 用 Anthropic Tool.builder + JsonValue.from 把 MCP schema 转成 SDK Tool
                Tool.InputSchema.Builder isb = Tool.InputSchema.builder()
                        .properties(JsonValue.from(schema.getOrDefault("properties", Map.of())));
                Object required = schema.get("required");
                if (required != null) isb.putAdditionalProperty("required", JsonValue.from(required));
                tools.add(Tool.builder()
                        .name(prefixed)
                        .description(td.description() == null ? "" : td.description())
                        .inputSchema(isb.build())
                        .build());

                // handler: 闭包捕获 server + rawName, dispatch 到 server.callTool
                MCPClient serverRef = server;
                String toolNameRef = rawName;
                handlers.put(prefixed, args -> serverRef.callTool(toolNameRef, args));

                // 授权策略
                String policy = MCP_HOST_POLICY.getOrDefault(
                        new ServerToolKey(serverName, rawName), "confirm");
                MCP_TOOL_POLICIES.put(prefixed, policy);
            }
        }
        return new ToolPool(tools, handlers);
    }

    /** SYSTEM prompt: 若有已连接 server, 加一段告知 */
    private static String assembleSystemPrompt() {
        if (MCP_CLIENTS.isEmpty()) return BASE_SYSTEM;
        return BASE_SYSTEM + "\n\nConnected MCP servers: " + String.join(", ", MCP_CLIENTS.keySet());
    }

    // ══════════════════════════════════════════════════════════════════
    //  内置工具
    // ══════════════════════════════════════════════════════════════════

    private static String runBash(String command) {
        try {
            Process p = new ProcessBuilder("bash", "-c", command)
                    .directory(new File(WORKDIR.toString())).redirectErrorStream(true).start();
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); return "Error: Timeout"; }
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
                        .map(Path::toString).toList();
                return results.isEmpty() ? "(no matches)" : String.join("\n", results);
            }
        } catch (Exception e) { return "Error: " + e.getMessage(); }
    }

    private static Integer asInteger(Object v) { return (v instanceof Number n) ? n.intValue() : null; }

    // ── 内置工具定义 + handler ────────────────────────────────
    private static final List<Tool> BUILTIN_TOOLS = List.of(
            tool("bash", "Run a shell command.",
                    Map.of("command", Map.of("type", "string")), List.of("command")),
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
            tool("glob", "Find files by glob pattern; ** matches recursively.",
                    Map.of("pattern", Map.of("type", "string")), List.of("pattern")),

            // ★ s14 新增: connect_mcp
            Tool.builder()
                    .name("connect_mcp")
                    .description("Connect to an MCP server and discover its tools.")
                    .inputSchema(Tool.InputSchema.builder()
                            .properties(JsonValue.from(Map.of("name",
                                    Map.of("type", "string",
                                           "enum", List.of("docs", "deploy")))))
                            .putAdditionalProperty("required", JsonValue.from(List.of("name")))
                            .build())
                    .build()
    );

    private static final Map<String, Function<Map<String, Object>, String>> BUILTIN_HANDLERS = Map.of(
            "bash", args -> runBash((String) args.get("command")),
            "read_file", args -> runRead((String) args.get("path"), asInteger(args.get("limit"))),
            "write_file", args -> runWrite((String) args.get("path"), (String) args.get("content")),
            "edit_file", args -> runEdit((String) args.get("path"),
                    (String) args.get("old_text"), (String) args.get("new_text")),
            "glob", args -> runGlob((String) args.get("pattern")),
            "connect_mcp", args -> runConnectMcp((String) args.get("name"))
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

    // ── Hooks (permission hook 里对 mcp__ 工具查 policy) ────────────
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
        String name = block.name();

        if ("bash".equals(name)) {
            String cmd = (String) input.getOrDefault("command", "");
            for (String p : DENY_LIST) if (cmd.contains(p)) {
                System.out.println("\n\033[31m[blocked] '" + p + "'\033[0m");
                return "Permission denied by deny list";
            }
        }

        // ★ s14 新增: mcp__ 工具查 host policy
        if (name.startsWith("mcp__")) {
            String policy = MCP_TOOL_POLICIES.getOrDefault(name, "confirm");
            if (!"allow".equals(policy)) {
                System.out.println("\n\033[33m[permission] External tool " + name
                        + "(" + input + ")\033[0m");
                System.out.print("Allow? [y/N] ");
                String choice = USER_INPUT.hasNextLine() ? USER_INPUT.nextLine().trim().toLowerCase() : "";
                if (!(choice.equals("y") || choice.equals("yes"))) {
                    return "Permission denied by user";
                }
            }
        }
        return null;
    }

    static { registerHook(HookEvent.PRE_TOOL_USE, AgentLoop::permissionHook); }

    // ══════════════════════════════════════════════════════════════════
    //  Agent Loop - 每轮 assembleToolPool()
    // ══════════════════════════════════════════════════════════════════

    private static Message agentLoop(MessageCreateParams.Builder paramsBuilder,
                                     List<MessageParam> history) {
        while (true) {
            // ★ 每轮重新组装工具池 (可能有新连接的 MCP server)
            ToolPool pool = assembleToolPool();

            // 每轮造新 builder (因为 tools 可能变了, 而 Builder.tools() 是覆盖式的?
            // 保险起见每次现造 builder)
            MessageCreateParams.Builder pb = MessageCreateParams.builder()
                    .model(MODEL).system(assembleSystemPrompt())
                    .maxTokens(8000).temperature(0.3);
            pool.tools().forEach(pb::addTool);
            history.forEach(pb::addMessage);

            MessageCreateParams params = pb.build();
            if (DEBUG_HTTP) System.out.println("\n\n===============>>>>>>>>>>");
            System.out.println("[tool pool] size=" + pool.tools().size() + ", MCP servers="
                    + MCP_CLIENTS.keySet());
            if (DEBUG_HTTP) System.out.println("request is : ");
            if (DEBUG_HTTP) System.out.println(prettyJson(params._body()));
            Message response = CLIENT.messages().create(params);
            if (DEBUG_HTTP) System.out.println("response is : ");
            if (DEBUG_HTTP) System.out.println(prettyJson(response));

            history.add(assistantToParam(response));
            StopReason stop = response.stopReason().orElse(null);
            if (stop == null || !stop.equals(StopReason.TOOL_USE)) return response;

            List<ContentBlockParam> results = new ArrayList<>();
            for (ContentBlock block : response.content()) {
                Optional<ToolUseBlock> mtu = block.toolUse();
                if (mtu.isEmpty()) continue;
                ToolUseBlock tb = mtu.get();
                Map<String, Object> input = JSON_MAPPER.convertValue(tb._input(), new TypeReference<>() {});
                System.out.println("\033[36m> " + tb.name() + " " + input + "\033[0m");

                String blocked = triggerHooks(HookEvent.PRE_TOOL_USE, tb, input);
                String output;
                if (blocked != null) {
                    output = blocked;
                } else {
                    Function<Map<String, Object>, String> handler = pool.handlers().get(tb.name());
                    output = handler == null
                            ? "Unknown tool: " + tb.name()
                            : handler.apply(input);
                }
                System.out.println(output.length() > 200 ? output.substring(0, 200) : output);
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(tb.id()).content(output).build()));
            }
            history.add(MessageParam.builder().role(MessageParam.Role.USER)
                    .contentOfBlockParams(results).build());
        }
    }

    private static MessageParam assistantToParam(Message response) {
        List<ContentBlockParam> blocks = new ArrayList<>();
        for (ContentBlock cb : response.content()) {
            cb.text().ifPresent(t -> blocks.add(ContentBlockParam.ofText(
                    com.anthropic.models.messages.TextBlockParam.builder().text(t.text()).build())));
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

    public static void main(String[] args) {
        if (MODEL == null || MODEL.isBlank()) {
            System.err.println("MODEL_ID is not set in .env"); System.exit(1);
        }
        System.out.println("s14: MCP Plugin");
        System.out.println("可用 MCP servers: " + String.join(", ", MOCK_SERVERS.keySet()));
        System.out.println("输入问题, 回车发送。输入 q 退出。\n");

        List<MessageParam> history = new ArrayList<>();
        MessageCreateParams.Builder placeholderBuilder = null;   // 不再共享 builder,每轮现造

        while (true) {
            System.out.print("\033[36ms14v >> \033[0m");
            if (!USER_INPUT.hasNextLine()) break;
            String q = USER_INPUT.nextLine().trim();
            if (q.isEmpty() || q.equalsIgnoreCase("q") || q.equalsIgnoreCase("exit")) break;

            history.add(MessageParam.builder().role(MessageParam.Role.USER).content(q).build());
            Message r = agentLoop(placeholderBuilder, history);
            if (r != null) {
                for (ContentBlock cb : r.content()) {
                    cb.text().ifPresent(t -> System.out.println(t.text()));
                }
            }
            System.out.println();
        }
    }
}
