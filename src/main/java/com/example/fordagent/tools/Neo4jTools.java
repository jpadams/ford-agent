package com.example.fordagent.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.neo4j.driver.Record;
import org.neo4j.driver.SessionConfig;
import org.neo4j.driver.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class Neo4jTools {

    private static final Logger log = LoggerFactory.getLogger(Neo4jTools.class);
    private static final int MAX_ROWS = 50;

    private final Driver driver;
    private final VizCollector vizCollector;

    public Neo4jTools(Driver driver, VizCollector vizCollector) {
        this.driver = driver;
        this.vizCollector = vizCollector;
    }

    private static List<Object> asListSafe(Value v) {
        return (v == null || v.isNull()) ? List.of() : v.asList();
    }

    @Tool(description = """
            Return a compact summary of the Neo4j graph schema: node labels with their property keys,
            and relationship types with their property keys. Call this before authoring Cypher when
            you do not already know the data model.
            """)
    public String getSchema() {
        long start = System.nanoTime();
        String cid = cid();
        log.info("tool=getSchema conversationId={}", cid);
        try (var session = driver.session(SessionConfig.builder().build())) {
            String result = session.executeRead(tx -> {
                StringBuilder sb = new StringBuilder();

                sb.append("Node labels and properties:\n");
                tx.run("CALL db.schema.nodeTypeProperties()").stream().forEach(r -> {
                    sb.append("  ")
                            .append(asListSafe(r.get("nodeLabels")))
                            .append(" .")
                            .append(r.get("propertyName").asString(""))
                            .append(" : ")
                            .append(asListSafe(r.get("propertyTypes")))
                            .append('\n');
                });

                sb.append("Relationship types and properties:\n");
                tx.run("CALL db.schema.relTypeProperties()").stream().forEach(r -> {
                    sb.append("  ")
                            .append(r.get("relType").asString(""))
                            .append(" .")
                            .append(r.get("propertyName").asString(""))
                            .append(" : ")
                            .append(asListSafe(r.get("propertyTypes")))
                            .append('\n');
                });

                return sb.toString();
            });
            log.info("tool=getSchema conversationId={} status=ok chars={} elapsedMs={}",
                    cid, result.length(), (System.nanoTime() - start) / 1_000_000);
            return result;
        } catch (Exception e) {
            log.error("tool=getSchema conversationId={} status=error elapsedMs={}",
                    cid, (System.nanoTime() - start) / 1_000_000, e);
            return "Failed to read schema: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    @Tool(description = """
            Execute a read-only Cypher query against Neo4j and return up to 50 rows.
            Provide the Cypher string and an optional map of parameters. Do not use this for
            writes (CREATE, MERGE, DELETE, SET, REMOVE); the session is read-only and will reject them.
            """)
    public String runReadQuery(
            @ToolParam(description = "The read-only Cypher query to execute.") String cypher,
            @ToolParam(required = false, description = "Optional named parameters for the query.")
                    Map<String, Object> parameters) {
        Map<String, Object> params = parameters == null ? Map.of() : parameters;
        long start = System.nanoTime();
        String cid = cid();
        log.info("tool=runReadQuery conversationId={} cypher={} params={}", cid, oneLine(cypher), params);
        try (var session = driver.session(SessionConfig.builder().build())) {
            return session.executeRead(tx -> {
                List<Map<String, Object>> rows = new ArrayList<>();
                var result = tx.run(cypher, params);
                int count = 0;
                while (result.hasNext() && count < MAX_ROWS) {
                    Record rec = result.next();
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String key : rec.keys()) {
                        row.put(key, rec.get(key).asObject());
                    }
                    vizCollector.scan(row);
                    rows.add(row);
                    count++;
                }
                boolean truncated = result.hasNext();
                StringBuilder sb = new StringBuilder();
                sb.append("rows=").append(rows.size());
                if (truncated) {
                    sb.append(" (truncated at ").append(MAX_ROWS).append(")");
                }
                sb.append('\n');
                for (Map<String, Object> row : rows) {
                    sb.append(row).append('\n');
                }
                log.info(
                        "tool=runReadQuery conversationId={} status=ok rows={} truncated={} elapsedMs={}",
                        cid, rows.size(), truncated, (System.nanoTime() - start) / 1_000_000);
                return sb.toString();
            });
        } catch (Exception e) {
            log.error(
                    "tool=runReadQuery conversationId={} status=error cypher={} elapsedMs={}",
                    cid, oneLine(cypher), (System.nanoTime() - start) / 1_000_000, e);
            return "Query failed: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    private static String cid() {
        String v = MDC.get("conversationId");
        return v == null ? "" : v;
    }
}
