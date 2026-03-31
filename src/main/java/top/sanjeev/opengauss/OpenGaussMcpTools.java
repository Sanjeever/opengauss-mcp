package top.sanjeev.opengauss;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class OpenGaussMcpTools {

    private static final Pattern READ_ONLY_HEAD = Pattern.compile("^(SELECT|WITH|EXPLAIN)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WRITE_KEYWORDS = Pattern.compile("\\b(INSERT|UPDATE|DELETE|MERGE|ALTER|DROP|CREATE|TRUNCATE|GRANT|REVOKE|CALL|DO|COPY|VACUUM|ANALYZE|REINDEX|REFRESH)\\b", Pattern.CASE_INSENSITIVE);

    private final JdbcTemplate jdbcTemplate;

    public OpenGaussMcpTools(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Tool(name = "list_tables", description = "列出当前 schema 下的表、视图、物化视图")
    public List<Map<String, Object>> listTables() {
        String sql = """
                SELECT c.relname AS object_name,
                       CASE c.relkind
                           WHEN 'r' THEN 'table'
                           WHEN 'v' THEN 'view'
                           WHEN 'm' THEN 'materialized_view'
                       END AS object_type
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = current_schema()
                  AND c.relkind IN ('r', 'v', 'm')
                ORDER BY object_type, object_name
                """;
        return jdbcTemplate.queryForList(sql);
    }

    @Tool(name = "describe_table", description = "返回表结构详情：列名、类型、主键、索引、注释、默认值")
    public List<Map<String, Object>> describeTable(@ToolParam(description = "表名") String tableName) {
        validateIdentifier(tableName);
        String sql = """
                WITH target AS (
                    SELECT c.oid AS table_oid,
                           c.relname AS table_name
                    FROM pg_class c
                    JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname = current_schema()
                      AND c.relname = ?
                      AND c.relkind IN ('r', 'v', 'm')
                ),
                pk_columns AS (
                    SELECT a.attname
                    FROM target t
                    JOIN pg_constraint con ON con.conrelid = t.table_oid AND con.contype = 'p'
                    JOIN pg_attribute a ON a.attrelid = t.table_oid AND a.attnum = ANY(con.conkey)
                ),
                indexed_columns AS (
                    SELECT a.attname,
                           string_agg(DISTINCT idx.relname, ',') AS index_names
                    FROM target t
                    JOIN pg_index i ON i.indrelid = t.table_oid
                    JOIN pg_class idx ON idx.oid = i.indexrelid
                    JOIN pg_attribute a ON a.attrelid = t.table_oid AND a.attnum = ANY(i.indkey)
                    GROUP BY a.attname
                )
                SELECT c.column_name,
                       c.data_type,
                       EXISTS (SELECT 1 FROM pk_columns p WHERE p.attname = c.column_name) AS is_primary_key,
                       COALESCE((SELECT i.index_names FROM indexed_columns i WHERE i.attname = c.column_name), '') AS indexes,
                       col_description((SELECT table_oid FROM target), c.ordinal_position) AS column_comment,
                       c.column_default
                FROM information_schema.columns c
                JOIN target t ON t.table_name = c.table_name
                WHERE c.table_schema = current_schema()
                  AND c.table_name = ?
                ORDER BY c.ordinal_position
                """;
        return jdbcTemplate.queryForList(sql, tableName, tableName);
    }

    @Tool(name = "get_table_stats", description = "返回表统计信息：行数估计、最近分析时间、表大小、索引大小")
    public Map<String, Object> getTableStats(@ToolParam(description = "表名") String tableName) {
        validateIdentifier(tableName);
        String sql = """
                SELECT c.relname AS table_name,
                       c.reltuples::bigint AS estimated_rows,
                       s.last_analyze,
                       pg_size_pretty(pg_relation_size(c.oid)) AS table_size,
                       pg_size_pretty(pg_indexes_size(c.oid)) AS index_size
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
                WHERE n.nspname = current_schema()
                  AND c.relname = ?
                  AND c.relkind = 'r'
                """;
        return querySingleRow(sql, tableName);
    }

    @Tool(name = "run_select", description = "执行只读 SQL，仅允许 SELECT / WITH / EXPLAIN")
    public List<Map<String, Object>> runSelect(@ToolParam(description = "只读 SQL") String sql) {
        validateReadOnlySql(sql);
        return jdbcTemplate.queryForList(trimTrailingSemicolon(sql));
    }

    @Tool(name = "execute_sql", description = "执行任意 DDL/DML SQL")
    public Map<String, Object> executeSql(@ToolParam(description = "SQL 文本") String sql) {
        if (!StringUtils.hasText(sql)) {
            throw new IllegalArgumentException("sql 不能为空");
        }
        String executableSql = trimTrailingSemicolon(sql);
        return jdbcTemplate.execute((ConnectionCallback<Map<String, Object>>) connection -> {
            try (Statement statement = connection.createStatement()) {
                boolean hasResultSet = statement.execute(executableSql);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("sql", executableSql);
                if (hasResultSet) {
                    List<Map<String, Object>> rows = mapRows(statement.getResultSet());
                    result.put("result_type", "result_set");
                    result.put("row_count", rows.size());
                    result.put("rows", rows);
                } else {
                    result.put("result_type", "update_count");
                    result.put("affected_rows", statement.getUpdateCount());
                }
                return result;
            }
        });
    }

    private Map<String, Object> querySingleRow(String sql, String tableName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, tableName);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("表不存在或不在 current_schema: " + tableName);
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> mapRows(ResultSet resultSet) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        while (resultSet.next()) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= columnCount; i++) {
                row.put(metaData.getColumnLabel(i), resultSet.getObject(i));
            }
            rows.add(row);
        }
        return rows;
    }

    private void validateIdentifier(String identifier) {
        if (!StringUtils.hasText(identifier) || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("非法标识符: " + identifier);
        }
    }

    private void validateReadOnlySql(String sql) {
        if (!StringUtils.hasText(sql)) {
            throw new IllegalArgumentException("sql 不能为空");
        }
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements == null || statements.size() != 1) {
                throw new IllegalArgumentException("仅允许单条 SQL");
            }
            net.sf.jsqlparser.statement.Statement statement = statements.get(0);
            if (!isReadOnlyStatement(statement)) {
                throw new IllegalArgumentException("仅允许 SELECT / WITH / EXPLAIN");
            }
            return;
        } catch (JSQLParserException ignored) {
        }
        validateReadOnlySqlByKeyword(sql);
    }

    private boolean isReadOnlyStatement(net.sf.jsqlparser.statement.Statement statement) {
        if (statement instanceof Select) {
            return true;
        }
        if (statement instanceof ExplainStatement explainStatement) {
            return explainStatement.getStatement() != null;
        }
        return false;
    }

    private void validateReadOnlySqlByKeyword(String sql) {
        String normalized = normalizeSqlHead(sql);
        if (!READ_ONLY_HEAD.matcher(normalized).find()) {
            throw new IllegalArgumentException("仅允许 SELECT / WITH / EXPLAIN");
        }
        String uppercase = normalized.toUpperCase(Locale.ROOT);
        if (WRITE_KEYWORDS.matcher(uppercase).find()) {
            throw new IllegalArgumentException("检测到写操作关键字，仅允许只读语句");
        }
        if (normalized.contains(";")) {
            throw new IllegalArgumentException("仅允许单条 SQL");
        }
    }

    private String normalizeSqlHead(String sql) {
        String normalized = sql.strip();
        while (normalized.startsWith("--")) {
            int newline = normalized.indexOf('\n');
            if (newline < 0) {
                return "";
            }
            normalized = normalized.substring(newline + 1).stripLeading();
        }
        while (normalized.startsWith("/*")) {
            int end = normalized.indexOf("*/");
            if (end < 0) {
                return "";
            }
            normalized = normalized.substring(end + 2).stripLeading();
        }
        return trimTrailingSemicolon(normalized);
    }

    private String trimTrailingSemicolon(String sql) {
        String trimmed = sql.strip();
        if (trimmed.endsWith(";")) {
            return trimmed.substring(0, trimmed.length() - 1).stripTrailing();
        }
        return trimmed;
    }
}
