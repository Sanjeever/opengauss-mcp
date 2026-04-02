# openGauss MCP Server

基于 Spring AI MCP Server 的 openGauss 数据库工具服务，支持通过 MCP 客户端以 `stdio` 方式连接，并调用数据库相关工具完成表结构查看、统计信息查询与 SQL 执行。

## 功能特性

- 基于 MCP 标准协议，支持 `stdio` 传输
- 内置 openGauss 数据库连接能力（JDBC）
- 提供 5 个数据库工具：
  - `list_tables`：列出当前 schema 下的表、视图、物化视图
  - `describe_table`：查看表结构（列、类型、主键、索引、注释、默认值）
  - `get_table_stats`：查看表统计信息（估算行数、分析时间、表大小、索引大小）
  - `run_select`：执行只读 SQL（仅 `SELECT / WITH / EXPLAIN`）
  - `execute_sql`：执行 DDL / DML SQL
- 对只读 SQL 做了语句类型与关键字校验，降低误写风险

## 环境要求

- JDK 17+
- Maven 3.9+
- openGauss 数据库（可访问）

## 快速开始

### 1) 克隆并进入项目

```bash
git clone https://github.com/Sanjeever/opengauss-mcp
cd opengauss-mcp
```

### 2) 构建项目

```bash
mvn clean package
```

构建成功后可执行包路径：

```text
target/opengauss-mcp.jar
```

### 3) 可视化运行（MCP Inspector）

按以下步骤即可在 MCP Inspector 中可视化调试 `opengauss` 服务：

1. 构建项目

```bash
mvn clean package
```

2. 复制配置模板

```bash
cp mcp.json.template mcp.json
```

3. 修改 `mcp.json` 中 `env` 的数据库连接信息

请根据你的 openGauss 实例更新以下环境变量：

- `OPENGAUSS_HOST`
- `OPENGAUSS_PORT`
- `OPENGAUSS_DATABASE`
- `OPENGAUSS_SCHEMA`
- `OPENGAUSS_USERNAME`
- `OPENGAUSS_PASSWORD`

4. 启动 Inspector 并加载 `opengauss` 服务

```bash
npx @modelcontextprotocol/inspector --config ./mcp.json --server opengauss
```

### 4) 本地启动（stdio 模式）

```bash
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar target/opengauss-mcp.jar
```

## MCP 客户端接入示例

该服务使用 `stdio`，客户端通常通过命令启动方式接入。

```json
{
  "mcpServers": {
    "opengauss": {
      "command": "java",
      "args": [
        "-Dfile.encoding=UTF-8",
        "-Dsun.stdout.encoding=UTF-8",
        "-Dsun.stderr.encoding=UTF-8",
        "-jar",
        "/absolute/path/to/opengauss-mcp/target/opengauss-mcp.jar"
      ],
      "env": {
        "OPENGAUSS_HOST": "127.0.0.1",
        "OPENGAUSS_PORT": "5432",
        "OPENGAUSS_DATABASE": "postgres",
        "OPENGAUSS_SCHEMA": "public",
        "OPENGAUSS_USERNAME": "your_username",
        "OPENGAUSS_PASSWORD": "your_password"
      }
    }
  }
}
```

## 工具说明

### list_tables

- 入参：无
- 返回：当前 schema 下对象列表（`object_name`、`object_type`）

### describe_table

- 入参：`tableName`
- 返回：字段名、数据类型、是否主键、索引、注释、默认值等信息
- 约束：表名仅允许合法标识符（字母/数字/下划线）

### get_table_stats

- 入参：`tableName`
- 返回：估算行数、最近分析时间、表大小、索引大小

### run_select

- 入参：`sql`
- 允许：`SELECT` / `WITH` / `EXPLAIN`
- 拒绝：写操作关键字、多语句执行

### execute_sql

- 入参：`sql`
- 能力：执行 DDL/DML，返回受影响行数或结果集

## 常见问题

### 1) 启动后无法连接数据库

请检查：

- `OPENGAUSS_HOST`、`OPENGAUSS_PORT`、`OPENGAUSS_DATABASE`、`OPENGAUSS_SCHEMA`、`OPENGAUSS_USERNAME`、`OPENGAUSS_PASSWORD` 是否正确
- 数据库实例是否可达（网络、端口、防火墙）
- 账号是否具有目标 schema 的权限

### 2) `run_select` 提示不允许执行

`run_select` 只允许只读查询。如需执行建表、更新、删除等操作，请使用 `execute_sql`。

## License

[MIT](LICENSE)
