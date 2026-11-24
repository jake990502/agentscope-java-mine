package demo;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;

import java.nio.file.Paths;

/**
 * 官方快速开始 · 多用户并发示例（https://java.agentscope.io/v2/zh/docs/quickstart#多用户并发）
 *
 * 要点：Agent 在调用之间是无状态的——应用启动时创建一个实例（单例即可），
 * 同一个实例可以处理不同用户、不同会话的请求。
 * 通过 RuntimeContext 传入 userId / sessionId，每次调用自动加载并隔离各自的对话上下文。
 * 同一 (userId, sessionId) 的请求自动串行化；不同 session 完全并行。
 */
public class MultiUserDemo {

    // 应用启动时创建一个 agent 实例（单例即可；Spring 里就是 @Bean）
    private static final HarnessAgent AGENT = HarnessAgent.builder()
            .name("note-taker")
            .sysPrompt("你是一个帮助用户做笔记的助手。")
            .model("dashscope:qwen-plus")
            .workspace(Paths.get(".agentscope/workspace"))
            .compaction(CompactionConfig.builder()
                    .triggerMessages(30)
                    .keepMessages(10)
                    .build())
            .build();

    public static void main(String[] args) throws InterruptedException {
        // 模拟三个并发请求：alice 两个会话 + bob 一个会话，互不干扰
        Thread t1 = new Thread(() -> handle("alice", "session-a", "我叫天宇，记住我喜欢先看结论。"));
        Thread t2 = new Thread(() -> handle("alice", "session-b", "帮我列一个今天做技术分享的准备清单。"));
        Thread t3 = new Thread(() -> handle("bob", "session-a", "你好，我是 Bob。"));

        t1.start(); t2.start(); t3.start();
        t1.join(); t2.join(); t3.join();

        // alice 回到 session-a 追问：验证各会话记忆隔离
        handle("alice", "session-a", "我叫什么？我的阅读偏好是什么？");

        AGENT.close();
    }

    // 相当于 HTTP handler：不同请求传入不同 RuntimeContext，调同一个单例
    private static void handle(String userId, String sessionId, String userInput) {
        var reply = AGENT.call(new UserMessage(userInput), RuntimeContext.builder()
                .sessionId(sessionId)
                .userId(userId)
                .build()).block();
        System.out.printf("[%s / %s] 🤖 %s%n", userId, sessionId,
                reply != null ? reply.getTextContent() : "(无回复)");
    }
}
