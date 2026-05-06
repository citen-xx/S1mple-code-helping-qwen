# Simple AI OJ

一个基于 `Spring Boot 3 + MyBatis-Plus + MySQL + Vue 3` 的简易在线判题系统，包含题目管理、代码评测、AI 辅导和前后端部署能力。

## 项目能力

- 题目管理：支持题目和测试用例的增删改查
- 判题模块：支持 `Java / C++` 简易编译运行评测
- AI 辅导：支持基于 `LangChain4j + DashScope(Qwen)` 的流式 SSE 输出
- 前端页面：`Vue 3 + TailwindCSS + Monaco Editor`
- 部署方式：支持 `Docker Compose` 一键启动

## 技术栈

- 后端：`Spring Boot 3.3.5`
- ORM：`MyBatis-Plus 3.5.7`
- 数据库：`MySQL 8.0`
- AI：`LangChain4j 0.36.2`、`DashScope / qwen-plus`
- 前端：`Vue 3`、`Vite 5`、`TailwindCSS`、`Monaco Editor`

## 目录结构

```text
.
├─ src/                     后端源码
├─ sql/                     建表与初始化数据 SQL
├─ frontend/                前端工程
├─ Dockerfile               后端镜像构建
├─ docker-compose.yml       本地 / 服务器编排
├─ DEPLOY.md                部署说明
└─ README.md
```

## 后端接口

### 题目接口

- `GET /api/questions`：题目列表
- `GET /api/questions/{id}`：题目基础信息
- `GET /api/questions/{id}/detail`：题目详情 + 测试用例
- `POST /api/questions`：新增题目
- `PUT /api/questions/{id}`：更新题目
- `DELETE /api/questions/{id}`：删除题目

### 判题接口

- `POST /api/judge`

请求示例：

```json
{
  "questionId": 1,
  "language": "java",
  "code": "public class Main { public static void main(String[] args) {} }"
}
```

### AI 辅导接口

- `POST /api/ai/help`
- 返回类型：`SseEmitter`
- 输出方式：流式 SSE

请求字段：

```json
{
  "questionContent": "题目内容",
  "wrongCode": "用户代码",
  "errorOutput": "报错信息或错误输出"
}
```

## 本地启动

### 1. 启动后端

要求：

- `JDK 17+`
- `Maven 3.9+`
- `MySQL 8.0`

执行：

```bash
mvn spring-boot:run
```

### 2. 启动前端

进入前端目录：

```bash
cd frontend
npm install
npm run dev
```

默认开发地址：

- 前端：`http://localhost:5173`
- 后端：`http://localhost:8080`

## 数据初始化

SQL 文件：

- [sql/schema.sql](./sql/schema.sql)
- [sql/init-data.sql](./sql/init-data.sql)

说明：

- `schema.sql`：建表
- `init-data.sql`：初始化题目与测试用例

如果数据库已经存在且容器不是首次初始化，`docker-entrypoint-initdb.d` 不会重复执行，这时需要手动导入：

```bash
mysql -u root -p simple_ai_oj < sql/init-data.sql
```

## Docker 部署

首次启动：

```bash
docker compose up -d --build
```

停止服务：

```bash
docker compose down
```

如果要连同数据库卷一起删除并重新初始化：

```bash
docker compose down -v
docker compose up -d --build
```

更多部署说明见：

- [DEPLOY.md](./DEPLOY.md)

## AI 配置

当前 AI 辅导默认使用：

- 模型：`qwen-plus`
- 联网搜索：开启

关键配置项：

- `dashscope.api-key`
- `dashscope.model`
- `dashscope.enable-search`
- `dashscope.base-url`

推荐通过环境变量覆盖：

```bash
DASHSCOPE_API_KEY=your-key
DASHSCOPE_MODEL=qwen-plus
DASHSCOPE_ENABLE_SEARCH=true
```

## Vercel 前端部署

如果将 `frontend` 单独部署到 Vercel，需要保证有 `vercel.json`，并把 `/api/*` 代理到后端服务。

当前仓库中的前端 Vercel 配置文件：

- [frontend/vercel.json](./frontend/vercel.json)

## 当前注意事项

- 判题模块当前是简易 `ProcessBuilder` 沙箱，适合演示和轻量使用，不适合直接裸露到公网高风险场景
- AI 接口使用 SSE 流式输出，前端需按流读取
- 如果前端部署在 `https` 域名下，后端最好也提供 `https`，否则可能出现浏览器混合内容问题

## 后续可扩展方向

- 增加登录、提交记录、排行榜
- 增加更严格的代码沙箱与资源隔离
- 增加分页查询、条件筛选、题单
- 增加 AI 对代码 diff、复杂度分析、知识点讲解
