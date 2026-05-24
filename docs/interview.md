# AI-OJ 智能算法辅助评测系统面试准备稿

## 1. 本轮 AI 补强后的真实定位

- 这是一个面向编程练习场景的在线评测系统，核心由传统 OJ 判题链路和独立的 AI 辅助分析链路组成。
- 判题部分由 Spring Boot 后端接收代码提交，使用本地 `ProcessBuilder` 在临时目录中完成 Java / C++ 的编译和运行，并按测试用例返回 `Accepted`、`Compile Error`、`Runtime Error`、`Wrong Answer`、`Time Limit Exceeded` 等状态。
- AI 部分不是自动纠错，也不会影响判题主链路，而是用户在判题后手动触发的辅助分析能力。
- AI 接口基于 LangChain4j 接入 DashScope / Qwen，并使用 `SseEmitter` 向前端流式返回分析建议。
- 本轮补强后，AI 请求上下文从粗粒度的 `errorOutput` 扩展为结构化字段，包括题目标题、题面、语言、判题状态、失败用例序号、判题信息、失败输入、期望输出、实际输出和用户代码等。
- 本轮新增了字符级 Prompt 长度保护，会对超长题面、代码和错误输出做截断，避免无限制拼接长文本，但当前仍不是严格 Token 级控制。
- 本轮还规范了 SSE 事件协议，后端会发送 `message` / `done` / `error` 事件，前端会按事件类型分别处理。
- 后端已补充 `completion / timeout / error` 生命周期日志，便于追踪单次 AI 流式请求。
- 前端使用 `AbortController` 支持停止生成、重复请求中止旧请求、页面离开中止请求。
- 需要明确的是，当前代码沙箱仍然是本地进程级的简化执行方案，不是容器或内核级强隔离沙箱。

## 2. 本轮可以安全表述的 AI 相关能力

- 使用 LangChain4j 接入 DashScope / Qwen，并通过 `SseEmitter` 实现 AI 建议的流式返回。
- 在 AI 请求中结构化收集题目标题、题面、语言、用户代码、判题状态、判题信息和实际输出，提升模型分析上下文质量。
- Wrong Answer 场景下会把 `failedInput / expectedOutput / actualOutput / failedCaseIndex` 结构化传给 AI。
- Runtime Error 场景下会尽量传递 `failedInput` 和当前错误输出。
- Compile Error 场景下主要传递编译错误信息，不伪装成结构化失败用例。
- 为 Prompt 增加字符级长度保护，对超长代码和错误输出进行截断，并记录截断日志，降低长文本导致的模型调用不稳定风险。
- 将 SSE 输出协议规范为 `message / done / error` 三类事件，前端使用 `fetch + ReadableStream` 做兼容解析和增量展示。
- AI 调用失败时会给前端返回友好错误提示，且不会影响传统判题结果。

## 3. 仍然不能夸大的地方

- 不能说当前有严格的 2000 Token 控制；现在是字符级 Prompt 长度保护。
- 不能说 AI 会在判题失败后自动触发；当前仍然是用户手动点击触发。
- 不能说有自定义线程池或异步判题队列；本轮没有改这些。
- 不能说代码沙箱是强隔离沙箱；当前仍然是本地 `ProcessBuilder` 简化执行。
- 不能说有首字响应 2-3 秒的压测数据；当前没有真实压测证据。
- 不能说已经有完整任务状态机、提交记录表或异步判题体系；本轮没有动这些。
- 不能说已经“彻底取消上游模型生成”；当前只能保证停止继续向已关闭连接发送 SSE 内容。
