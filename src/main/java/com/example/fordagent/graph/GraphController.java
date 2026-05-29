package com.example.fordagent.graph;

import com.example.fordagent.chat.VizPayload;
import com.example.fordagent.tools.VizCollector;
import java.util.LinkedHashMap;
import java.util.Map;
import org.neo4j.driver.Driver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/graph")
public class GraphController {

    private static final Logger log = LoggerFactory.getLogger(GraphController.class);
    private static final int MAX_NEIGHBOURS = 50;

    private final Driver driver;
    private final VizCollector vizCollector;

    public GraphController(Driver driver, VizCollector vizCollector) {
        this.driver = driver;
        this.vizCollector = vizCollector;
    }

    @PostMapping("/expand")
    public VizPayload expand(@RequestBody ExpandRequest request) {
        if (request == null || request.nodeId() == null || request.nodeId().isBlank()) {
            return VizPayload.empty();
        }
        long start = System.nanoTime();
        log.info("graph expand nodeId={}", request.nodeId());
        try (var session = driver.session()) {
            session.executeRead(tx -> {
                var result = tx.run(
                        "MATCH (n)-[r]-(m) WHERE elementId(n) = $id RETURN n, r, m LIMIT $lim",
                        Map.of("id", request.nodeId(), "lim", MAX_NEIGHBOURS));
                while (result.hasNext()) {
                    var rec = result.next();
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (String key : rec.keys()) {
                        row.put(key, rec.get(key).asObject());
                    }
                    vizCollector.scan(row);
                }
                return null;
            });
            VizPayload snap = vizCollector.snapshot();
            log.info("graph expand nodeId={} status=ok nodes={} rels={} elapsedMs={}",
                    request.nodeId(), snap.nodes().size(), snap.relationships().size(),
                    (System.nanoTime() - start) / 1_000_000);
            return snap;
        } catch (Exception e) {
            log.error("graph expand nodeId={} status=error elapsedMs={}",
                    request.nodeId(), (System.nanoTime() - start) / 1_000_000, e);
            return VizPayload.empty();
        }
    }

    public record ExpandRequest(String nodeId) {}
}
