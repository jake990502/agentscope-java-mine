package demo;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;

import java.nio.file.Paths;

/**
 * 官方快速开始示例（https://java.agentscope.io/v2/zh/docs/quickstart）
 *
 * 跑通三件事：
 * 1. 工作区驱动的人格（AGENTS.md）
 * 2. 会话自动持久化（相同 sessionId 的第二轮记得第一轮）
 * 3. 对话压缩（超阈值后自动压缩 + 长期事实落到 MEMORY.md）
 *
 * 运行前设置环境变量（真实 DashScope Key）：
 *   export DASHSCOPE_API_KEY=sk-xxxx
 *
 * 模型字符串由 ModelRegistry 解析，切换厂商时改用：
 *   "openai:gpt-5.5" / "anthropic:claude-sonnet-4-5" / "gemini:gemini-2.0-flash" / "ollama:llama3"
 * 并引入对应的 agentscope-extensions-model-* 依赖、设置对应 API Key。
 */
public class FirstAgent {
    public static void main(String[] args) {
        HarnessAgent agent = HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt("你是一个帮助用户做笔记的助手。")
                // 字符串形式由 ModelRegistry 解析 —— 自动读取 DASHSCOPE_API_KEY
                .model("dashscope:qwen-plus")
                .workspace(Paths.get(".agentscope/workspace"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)   // 消息条数到 30 触发压缩
                        .keepMessages(10)      // 压缩后保留最近 10 条原文
                        .build())
                .build();

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("demo-session")
                .userId("alice")
                .build();

        // 第一轮：自我介绍 + 当天的事
        Msg reply1 = agent.call(new UserMessage("我叫天宇，今天准备一个关于 ReAct 的技术分享。"), ctx).block();
        System.out.println("🤖 第一轮回复: " + (reply1 != null ? reply1.getTextContent() : "(无回复)"));

        // 第二轮：同 sessionId，自动恢复上一轮状态后回答
        Msg reply2 = agent.call(new UserMessage("我叫什么？我今天要干什么？"), ctx).block();
        System.out.println("🤖 第二轮回复: " + (reply2 != null ? reply2.getTextContent() : "(无回复)"));

        agent.close();
    }
}
