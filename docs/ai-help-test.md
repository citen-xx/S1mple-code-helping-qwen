# AI Help 测试记录

## 1. 本轮补强内容

- 扩展了 AI 请求上下文，新增 `questionTitle`、`language`、`judgeStatus`、`judgeMessage`、`actualOutput`、`expectedOutput`、`failedInput` 等字段。
- 补齐了判题失败用例结构化链路，`JudgeResponse` 会在失败时返回 `failedInput`、`expectedOutput`、`actualOutput`、`failedCaseIndex`。
- 保留了旧字段 `questionContent`、`wrongCode`、`errorOutput`，保证旧请求格式仍可兼容。
- 新增了字符级 Prompt 长度保护，对题面、代码、错误输出等字段分别做上限截断。
- 新增了 SSE `message / done / error` 事件协议。
- 新增了 AI 失败兜底：参数缺失时返回明确错误事件，模型调用失败时返回友好提示。
- 后端补了 `completion / timeout / error` 生命周期日志。
- 前端补了 `AbortController`，支持停止生成、重复请求中止旧请求、页面离开中止请求。

## 2. 关键说明

- 当前仍不是严格 Token 级 2000 限制，而是字符级 Prompt 长度保护。
- 当前 AI 仍是用户手动触发，不是判题失败自动触发。
- 当前没有自定义线程池和异步判题队列。
- 当前代码沙箱仍是本地 `ProcessBuilder` 简易执行，不是强隔离沙箱。
- Wrong Answer 场景会结构化传递 `failedInput / expectedOutput / actualOutput / failedCaseIndex`。
- Runtime Error 场景会尽量传递 `failedInput` 和错误输出。
- Compile Error 场景主要传递编译错误信息。
- 当前没有可取消 LangChain4j 上游生成的句柄，所以不能表述为“彻底取消模型生成”。

## 3. 建议验证项

1. Accepted 后手动触发 AI，确认仍能正常返回一般性分析建议。
2. Compile Error 后手动触发 AI，确认能流式返回错误定位建议。
3. Wrong Answer 后手动触发 AI，确认 AI 请求中包含 `failedInput / expectedOutput / actualOutput / failedCaseIndex`。
4. 输入超长代码或超长错误输出，确认后端日志出现 truncation 提示，前端仍可收到分析内容。
5. 点击“停止生成”，确认前端结束 loading，且不把主动中止显示成系统错误。
6. 连续点击 AI 分析，确认旧请求被中止且旧流不会污染新流。
7. 页面离开时，确认当前请求被 abort，没有未捕获异常。
8. 模型配置错误时，确认前端收到 `error` 事件并结束 loading。
9. 正常完成时，确认前端能识别 `done` 事件。
