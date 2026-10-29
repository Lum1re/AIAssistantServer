# 秒杀商城 · AI 智能客服（编排版）

> **一句话**：把"要不要执行"的决定权，从大模型手里收回到代码手里。
>
> 模型只做两件事 —— **听懂**（分类 + 提参）和 **说话**（组织话术）；
> **做什么、按什么顺序做、能不能做，全部由代码决定。**

---

## 一、核心设计

### 1.1 一次请求的链路

```
用户消息
  ↓
AiChatServiceImpl       token → Redis → 手机号（身份只从这里来）
  ↓
AgentOrchestrator       加用户级锁 → 读会话状态 → 路由
  ├─ 用户说"算了"          → 清状态 + 清票据 → 结束
  ├─ 在等确认              → ConfirmRules 判定 → 执行 / 取消 / 插话
  ├─ 在等参数              → 模型识别不出就把这句当参数补上（补完要验证）
  └─ 正常                  → IntentRecognizer 识别 → 查表分发
  ↓
IntentHandler           取槽位 → 调原有 Mapper/Service → 拼话术
  ↓
SSE 事件流                stage → delta… → done
```

### 1.2 三层防护（写操作）

| 层 | 机制 | 防什么 |
|---|---|---|
| 1 | `ConfirmRules` 纯规则判定 | "用户是否确认"不再由模型判断 |
| 2 | `ConfirmTicket` 一次性票据（Lua 原子取出即删） | 同一个确认执行两次 |
| 3 | SQL `AND status = 1`（CAS） | 并发 / 任何泄漏场景 |

**任意一层失效，另外两层仍能挡住重复退款。**

### 1.3 降级策略

| 场景 | 走哪 | 工具权限 |
|---|---|---|
| 意图没注册 / 识别失败 | 降级到 `LegacyChatAgent` | **只读**（查商品/库存/详情/订单） |
| 人工改 `ai.agent.mode=legacy` | 完全老链路 | 完整（含下单/退款） |

> 降级路径**不能**把写操作权限还给模型，否则只要降级一次，票据机制就形同虚设。

---

## 二、目录结构

```
src/main/java/secKillAI
├── agent/
│   ├── Intent.java                 8 个意图（枚举，含给模型看的 description）
│   ├── IntentRecognizer.java       唯一真正调模型的地方（只输出 JSON，无工具）
│   ├── AgentOrchestrator.java      路由 + 分发 + 状态读写
│   ├── AgentContext.java           槽位校验（time 只认 10/12/14）
│   ├── LegacyChatAgent.java        老链路（降级 / 人工回退）
│   ├── TraceId.java                请求追踪 id
│   ├── state/                      Stage / AgentState / AgentStateStore / AgentLock
│   ├── confirm/                    ConfirmTicket / ConfirmTicketStore / ConfirmRules
│   ├── handler/                    HandlerOutcome / IntentHandler / ConfirmableHandler
│   │                               + 6 个具体处理器
│   ├── result/                     ErrorCode（错误码 + 话术）/ ReplyTemplates
│   └── event/                      SseEvents（事件构造）
├── web/                            Controller / Service / Mapper
├── tools/                          老链路的 @Tool（仅 legacy 模式与只读降级用）
└── mongo/                          会话记忆（给模型看的历史）

src/main/resources
├── application.properties          主配置（不含任何密钥）
├── application-dev.properties      本地调试（打印用户原文，勿在生产激活）
├── prompts/seckill-prompt.txt      老链路的系统提示词
├── eval/intent-golden-set.txt      意图识别评测集（30 条）
└── mapper/*.xml                    MyBatis SQL
```

---

## 三、接口

| 接口 | 方法 | 参数 | 返回 |
|---|---|---|---|
| `/seckillAgent/chat` | GET | `msg`、header `token` | **SSE 事件流** |
| `/seckillAgent/session/clear` | POST | header `token` | String |

### SSE 事件格式

```
data:{"type":"stage","stage":"ROUTING"}
data:{"type":"delta","text":"订单 1997"}
data:{"type":"error","message":"系统有点忙，请稍后再试"}
data:{"type":"done"}
```

- `stage`：阶段变化（前端可显示"正在理解…"）
- `delta`：一段正文
- `error`：**流内错误**（HTTP 状态码已经发出去了，只能这样通知前端）
- `done`：结束标记

---

## 四、配置

```properties
# 大模型（密钥走环境变量，绝不写进文件）
langchain4j.open-ai.streaming-chat-model.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1
langchain4j.open-ai.streaming-chat-model.model-name=qwen3.7-plus
langchain4j.open-ai.streaming-chat-model.api-key=${DASHSCOPE_API_KEY:}

# 数据库
spring.datasource.username=${DB_USERNAME:root}
spring.datasource.password=${MYSQL_PASSWORD:}

# 编排开关：orchestrated（默认）| legacy（回退到改造前）
ai.agent.mode=orchestrated
ai.agent.intent.timeout-ms=10000
ai.agent.intent.min-confidence=0.6
ai.agent.state-ttl-minutes=30
ai.agent.lock-ttl-seconds=15
ai.agent.confirm-ttl-minutes=5
```

### Redis Key 一览

| Key | 类型 | TTL | 用途 |
|---|---|---|---|
| `ai:agent:state:{userId}` | String(JSON) | 30 分钟 | 会话进度（在等什么） |
| `ai:confirm:{userId}` | String(JSON) | 5 分钟 | 待确认操作票据 |
| `ai:lock:{userId}` | String | 15 秒 | 用户级并发锁 |
| `userToken:{token}` | String(JSON) | — | 登录态（含手机号） |
| `seckillStockCount:{time}` | Hash | — | 秒杀库存 |

---

## 五、运行

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17.0.2"
$env:DASHSCOPE_API_KEY = "sk-..."
$env:MYSQL_PASSWORD = "xxx"

cd AIAssistantServer
mvn spring-boot:run
```

**启动成功的标志**（必须是 6 个处理器）：

```
Agent 编排已启用，已注册处理器：
[QUERY_ORDER, QUERY_PRODUCTS, QUERY_STOCK, QUERY_PRODUCT_DETAIL, REFUND, SECKILL_BUY]
```

### 造测试登录态

```powershell
redis-cli SET userToken:test-token "{\"phone\":13800000000,\"nickName\":\"test\"}"
```

---

## 六、测试

| 测试类 | 覆盖 | 数量 |
|---|---|---|
| `AgentOrchestratorTest` | 分发 / 多轮补参数 / 两阶段确认 / 并发锁 | 24 |
| `ConfirmRulesTest` | 确认判定（YES/NO/NONE） | 41 |
| `ConfirmTicketStoreTest` | 票据一次性 / 过期 / 跨用户 | 8 |
| `RefundHandlerTest` | 退款两阶段 + 参数不被篡改 | 8 |
| `SeckillBuyHandlerTest` | 下单多步链 + 售罄不给确认 | 8 |
| `AgentStateTest` | 状态对象与 JSON 往返 | 7 |
| `ErrorCodeTest` | 错误码话术 / 异常不泄漏 | 5 |
| `IntentGoldenSetTest` | 评测集格式 | 3 |

**共 104 个，全部不依赖数据库/Redis/模型。**

### 意图识别准确率（需真实模型）

```
运行 secKillAI.agent.eval.IntentAccuracyCheck（IDE 右键 Run）
```

基线数据在 `eval/intent-golden-set.txt`（30 条）。改提示词或换模型后重跑对比。

---

## 七、已知限制

| 限制 | 说明 |
|---|---|
| 意图识别失败 → 写操作办不了 | 安全换可用性：降级只读，用户重说一遍即可恢复 |
| 一个用户同时只有一张待确认票据 | 新票据会覆盖旧票据 |
| 补参数是"整句硬塞" | 用户回"十点"这类口语化参数可能失败（若模型没提取出结构化值） |
| 只支持单会话 | 一个用户固定一份记忆，可清空重开 |
| 未做限流 / 输入长度限制 | 生产环境需要补 |
| 确认票据依赖 Redis | Redis 挂时退化成"无状态但可用" |

---
