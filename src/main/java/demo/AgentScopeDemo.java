package demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.harness.agent.HarnessAgent;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 阿里 AgentScope Java 2.0 最小可运行生产架构演示：
 * 1. 自动跑 ReAct while 循环（大模型返回 tool_calls -> 自动反射调本地 Java 方法 -> 把结果塞回上下文再调大模型）
 * 2. 自动跨实例持久化与恢复会话（podA 销毁后，podB 传入相同 sessionId 自动拼装完整历史记录）
 * 3. 内置本地 Mock 大模型网关（无需外部 API Key，直接抓包打印 AgentScope 底层发送的 HTTP 报文）
 */
public class AgentScopeDemo {

    // 1. 你们自己写的公司内部业务工具（用 @Tool 注解暴露给 AgentScope）
    public static class CompanyOaTools {
        @Tool(name = "query_oa_expense", description = "查询员工名下未审批的OA报销单列表")
        public String queryOaExpense(
                @ToolParam(name = "employeeName", description = "员工姓名", required = true) String employeeName) {
            System.out.println("   ⚙️ [Java 本地业务方法被触发] 正在调公司 OA 数据库查询员工: " + employeeName);
            return "[{\"id\":\"EXP-101\",\"type\":\"差旅费\",\"amount\":1200}, {\"id\":\"EXP-102\",\"type\":\"客户招待\",\"amount\":3500}]";
        }
    }

    public static void main(String[] args) throws Exception {
        // 降低框架内部 INFO 日志干扰，方便看清核心抓包输出
        System.setProperty("org.slf4j.simpleLogger.defaultLogLevel", "error");

        // 准备演示用的本地工作区目录
        Path workspaceDir = Path.of(System.getProperty("user.dir"), "target", "workspace-data");
        if (Files.exists(workspaceDir)) {
            Files.walk(workspaceDir).sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
        Files.createDirectories(workspaceDir);

        // 启动本地模拟的大模型网关 (127.0.0.1:18080)，无需外部真实 API Key，还能把 AgentScope 发来的报文抓包打印出来！
        HttpServer mockLlmGateway = startMockLlmGateway(18080);

        try {
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new CompanyOaTools());

            // 模拟 K8s Pod 启动时配置好的无状态 Builder
            // （上生产时，只需把 baseUrl 换成真实的模型路由网关地址，如 anker-router 或阿里云百炼）
            HarnessAgent.Builder agentBuilder = HarnessAgent.builder()
                    .name("AnkerAssistant")
                    .agentId("oa-assistant")
                    .sysPrompt("你是公司内部的报销助手，负责帮员工查询和计算OA报销单。")
                    .model(OpenAIChatModel.builder()
                            .baseUrl("http://127.0.0.1:18080/v1")
                            .apiKey("mock-no-key-needed")
                            .modelName("qwen-max-mock")
                            .stream(false)
                            .build())
                    .toolkit(toolkit)
                    .workspace(workspaceDir)
                    .disableFilesystemTools()
                    .disableShellTool()
                    .disableWebTools()
                    .disableSubagents()
                    .disableSkills()
                    .disableMemoryHooks(); // 如需体验后台自动提炼长期记忆，把这行注释掉即可

            String sessionId = "session-" + System.currentTimeMillis();

            // ==================== 模拟第 1 次用户请求（比如打到了 K8s Pod-A）====================
            System.out.println("\n==================== 【第 1 轮用户请求 (会话 " + sessionId + ")】 ====================");
            System.out.println("👤 用户发送: \"帮我查一下张三名下还没批完的报销单\"");
            try (HarnessAgent podA = agentBuilder.build()) {
                RuntimeContext ctx = RuntimeContext.builder()
                        .userId("user_zhangsan")
                        .sessionId(sessionId)
                        .build();
                Msg reply1 = podA.call("帮我查一下张三名下还没批完的报销单", ctx).block();
                System.out.println("🤖 Agent 最终回复: " + reply1.getTextContent());
            } // 注意：这里 podA 已经 close 销毁了！内存里什么都没留！

            // ==================== 模拟第 2 次用户请求（比如 10 分钟后打到了全新的 K8s Pod-B）====================
            System.out.println("\n==================== 【第 2 轮用户请求 (同一个会话 " + sessionId + "，全新实例)】 ====================");
            System.out.println("👤 用户追问: \"这两笔报销单加起来一共多少钱？\"");
            try (HarnessAgent podB = agentBuilder.build()) {
                RuntimeContext ctx = RuntimeContext.builder()
                        .userId("user_zhangsan")
                        .sessionId(sessionId)
                        .build();
                Msg reply2 = podB.call("这两笔报销单加起来一共多少钱？", ctx).block();
                System.out.println("🤖 Agent 最终回复: " + reply2.getTextContent());
            }

        } finally {
            mockLlmGateway.stop(0);
        }
    }

    /**
     * 一个极简的本地 Mock 大模型网关：
     * 既解决了没 API Key 的问题，又能把 AgentScope 底层自动拼装的上下文 JSON 抓包打印出来
     */
    private static HttpServer startMockLlmGateway(int port) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger callCounter = new AtomicInteger(0);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);

        server.createContext("/v1/chat/completions", exchange -> {
            int callNo = callCounter.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode root = mapper.readTree(body);
            JsonNode messages = root.get("messages");
            JsonNode tools = root.get("tools");

            System.out.println("\n   📡 [大模型网关抓包] 收到 AgentScope 的第 " + callNo + " 次 API 调用:");
            System.out.println("      ├─ 携带工具数量: " + (tools != null ? tools.size() : 0)
                    + (tools != null && tools.size() > 0 ? " (含自定义工具: " + tools.get(0).path("function").path("name").asText() + ")" : ""));
            System.out.println("      └─ 携带上下文 messages 数组 (共 " + messages.size() + " 条):");
            for (int i = 0; i < messages.size(); i++) {
                JsonNode m = messages.get(i);
                String role = m.path("role").asText();
                String content = m.path("content").asText("");
                if (content.length() > 60) content = content.substring(0, 60).replace("\n", " ") + "...";
                if (m.has("tool_calls")) content = "[要求调用工具: " + m.path("tool_calls").get(0).path("function").path("name").asText() + "]";
                System.out.println("         [" + i + "] role=" + role + " | " + content);
            }

            // 根据轮次返回模拟的大模型响应
            String responseJson;
            if (callNo == 1) {
                // 第1次被调：大模型决定先调用 query_oa_expense 工具
                responseJson = """
                    {
                      "id": "chatcmpl-1",
                      "object": "chat.completion",
                      "choices": [{
                        "index": 0,
                        "message": {
                          "role": "assistant",
                          "content": null,
                          "tool_calls": [{
                            "id": "call_oa_001",
                            "type": "function",
                            "function": {
                              "name": "query_oa_expense",
                              "arguments": "{\\"employeeName\\":\\"张三\\"}"
                            }
                          }]
                        },
                        "finish_reason": "tool_calls"
                      }],
                      "usage": {"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120}
                    }
                    """;
            } else if (callNo == 2) {
                // 第2次被调：大模型看到了工具返回的 JSON，生成第1轮的最终回答
                responseJson = """
                    {
                      "id": "chatcmpl-2",
                      "object": "chat.completion",
                      "choices": [{
                        "index": 0,
                        "message": {
                          "role": "assistant",
                          "content": "查到了！张三名下有 2 笔未审批报销单：1. EXP-101（差旅费 1200 元）；2. EXP-102（客户招待 3500 元）。"
                        },
                        "finish_reason": "stop"
                      }],
                      "usage": {"prompt_tokens": 180, "completion_tokens": 40, "total_tokens": 220}
                    }
                    """;
            } else {
                // 第3次被调（第2轮用户追问）：大模型根据带过来的历史记录算出总和
                responseJson = """
                    {
                      "id": "chatcmpl-3",
                      "object": "chat.completion",
                      "choices": [{
                        "index": 0,
                        "message": {
                          "role": "assistant",
                          "content": "根据刚才查出的记录，1200 元 + 3500 元 = 共计 4700 元。"
                        },
                        "finish_reason": "stop"
                      }],
                      "usage": {"prompt_tokens": 240, "completion_tokens": 25, "total_tokens": 265}
                    }
                    """;
            }

            byte[] bytes = responseJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        server.start();
        return server;
    }
}
