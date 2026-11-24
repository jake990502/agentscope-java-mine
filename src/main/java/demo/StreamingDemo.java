package demo;

import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;

/**
 * 官方快速开始 · 流式查看推理与工具调用（https://java.agentscope.io/v2/zh/docs/quickstart#流式查看推理与工具调用）
 *
 * 把 call(...) 换成 streamEvents(...) 就能实时拿到文本片段、工具调用等中间事件，
 * 适合 Web / TUI 渲染（配合 SSE 推给前端）。
 */
public class StreamingDemo {
    public static void main(String[] args) {
        HarnessAgent agent = HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt("你是一个帮助用户做笔记的助手。")
                .model("dashscope:qwen-plus")
                .build();

        agent.streamEvents(new UserMessage("帮我把今天的关键点列三条。"))
                .doOnNext(event -> {
                    if (event instanceof TextBlockDeltaEvent delta) {
                        // 模型返回的流式文本片段 —— 追加到界面或标准输出
                        System.out.print(delta.getDelta());
                    } else if (event instanceof ToolCallStartEvent tc) {
                        // 智能体即将调用工具 —— 展示调用信息
                        System.out.println("\n[tool] " + tc.getToolCallName());
                    }
                    // 其他事件：思考块、工具结果、回复结束等
                })
                .blockLast();

        System.out.println();
        agent.close();
    }
}
