package com.learn.cc.s01_agent_loop;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.cdimascio.dotenv.Dotenv;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * s01_agent_loop - The Agent Loop (Java port of code.py)
 *
 * The entire secret of an AI coding agent in one pattern:
 *
 *     while (stopReason == TOOL_USE) {
 *         response = LLM(messages, tools)
 *         execute tools
 *         append results
 *     }
 */
public final class AgentLoop {

    // ── Config from .env ──────────────────────────────────────────────────
    private static final Dotenv DOTENV = Dotenv.configure()
            .directory("./")
            .ignoreIfMissing()
            .load();

    private static final String API_KEY   = DOTENV.get("ANTHROPIC_API_KEY");
    private static final String BASE_URL  = DOTENV.get("ANTHROPIC_BASE_URL");
    private static final String MODEL     = DOTENV.get("MODEL_ID");

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

    private static final String SYSTEM =
            "You are a coding agent at " + System.getProperty("user.dir")
                    + ". Use bash to solve tasks. Act, don't explain.";

    // ── Tool definition: just bash ────────────────────────────────────────
    private static final Tool BASH_TOOL = Tool.builder()
            .name("bash")
            .description("Run a shell command.")
            .inputSchema(Tool.InputSchema.builder()
                    .properties(JsonValue.from(Map.of(
                            "command", Map.of("type", "string"))))
                    .putAdditionalProperty("required", JsonValue.from(List.of("command")))
                    .build())
            .build();

    private AgentLoop() {}

    private static AnthropicClient buildClient() {
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder();
        if (API_KEY != null && !API_KEY.isBlank()) {
            b.apiKey(API_KEY);
        }
        if (BASE_URL != null && !BASE_URL.isBlank()) {
            b.baseUrl(BASE_URL);
        }
        return b.build();
    }

    // ── Tool execution ────────────────────────────────────────────────────
    private static String runBash(String command) {
        List<String> dangerous = List.of("rm -rf /", "sudo", "shutdown", "reboot", "> /dev/");
        for (String d : dangerous) {
            if (command.contains(d)) {
                return "Error: Dangerous command blocked";
            }
        }
        try {
            Process p = new ProcessBuilder("bash", "-c", command)
                    .directory(new java.io.File(System.getProperty("user.dir")))
                    .redirectErrorStream(true)
                    .start();

            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return "Error: Timeout (120s)";
            }
            String out = new String(p.getInputStream().readAllBytes()).trim();
            if (out.isEmpty()) {
                return "(no output)";
            }
            return out.length() > 50_000 ? out.substring(0, 50_000) : out;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return "Error: " + e.getMessage();
        }
    }

    // ── The core pattern: a while loop that calls tools until the model stops ──
    private static Message agentLoop(MessageCreateParams.Builder paramsBuilder) {
        while (true) {
            MessageCreateParams params = paramsBuilder.build();
            traceHttp("request", params._body());
            Message response = CLIENT.messages().create(params);
            traceHttp("response", response);

            // Append assistant turn
            paramsBuilder.addMessage(response);

            // If the model didn't call a tool, we're done
            StopReason stop = response.stopReason().orElse(null);
            if (stop == null || !stop.equals(StopReason.TOOL_USE)) {
                return response;
            }

            // Execute each tool call, collect results
            List<ContentBlockParam> results = response.content().stream()
                    .flatMap(cb -> cb.toolUse().stream())
                    .map(AgentLoop::executeToolCall)
                    .toList();

            // Feed tool results back, loop continues
            paramsBuilder.addMessage(MessageParam.builder()
                    .role(MessageParam.Role.USER)
                    .contentOfBlockParams(results)
                    .build());
        }
    }

    private static ContentBlockParam executeToolCall(ToolUseBlock block) {
        String command = extractCommand(block);
        System.out.println("\033[33m$ " + command + "\033[0m");
        String output = runBash(command);
        System.out.println(output.length() > 200 ? output.substring(0, 200) : output);

        return ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                .toolUseId(block.id())
                .content(output)
                .build());
    }

    @SuppressWarnings("unchecked")
    private static String extractCommand(ToolUseBlock block) {
        Map<String, JsonValue> input = (Map<String, JsonValue>) block._input().asObject().get();
        return input.get("command").asStringOrThrow();
    }

    // ── Entry point ───────────────────────────────────────────────────────
    public static void main(String[] args) {
        if (MODEL == null || MODEL.isBlank()) {
            System.err.println("MODEL_ID is not set in .env");
            System.exit(1);
        }

        System.out.println("s01: Agent Loop");
        System.out.println("输入问题，回车发送。输入 q 退出。\n");

        MessageCreateParams.Builder paramsBuilder = MessageCreateParams.builder()
                .model(MODEL)
                .system(SYSTEM)
                .maxTokens(8000)
                .addTool(BASH_TOOL);

        Scanner sc = new Scanner(System.in);
        while (true) {
            System.out.print("\033[36ms01 >> \033[0m");
            if (!sc.hasNextLine()) break;
            String query = sc.nextLine().trim();
            if (query.isEmpty() || query.equalsIgnoreCase("q") || query.equalsIgnoreCase("exit")) {
                break;
            }

            paramsBuilder.addUserMessage(query);
            Message finalResponse = agentLoop(paramsBuilder);

            // Print the model's final text response
            finalResponse.content().stream()
                    .flatMap(cb -> cb.text().stream())
                    .map(TextBlock::text)
                    .forEach(System.out::println);
            System.out.println();
        }
    }
}
