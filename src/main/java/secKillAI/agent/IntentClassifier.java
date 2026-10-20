package secKillAI.agent;

/**
 * 意图分类器。
 *
 * <p>抽成接口的目的：编排器只依赖「给我一个识别结果」这个能力，
 * 不关心背后是真模型还是测试替身。
 * 于是可以在不联网、不花 token 的情况下验证整条分发链路。
 */
public interface IntentClassifier {

    /** 识别用户这句话的意图。任何情况下都不应该抛异常，失败就返回 {@link Intent#UNKNOWN}。 */
    IntentResult recognize(String msg);
}
