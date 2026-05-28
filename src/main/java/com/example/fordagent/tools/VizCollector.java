package com.example.fordagent.tools;

import com.example.fordagent.chat.VizNode;
import com.example.fordagent.chat.VizPayload;
import com.example.fordagent.chat.VizRelationship;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Path;
import org.neo4j.driver.types.Relationship;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
public class VizCollector {

    private static final int MAX_NODES = 100;
    private static final List<String> CAPTION_PROPERTY_PRIORITY = List.of("name", "title", "caption");

    private final LinkedHashMap<String, VizNode> nodes = new LinkedHashMap<>();
    private final LinkedHashMap<String, PendingRelationship> pending = new LinkedHashMap<>();

    public void scan(Object value) {
        if (value == null) return;
        if (value instanceof Node n) {
            addNode(n);
        } else if (value instanceof Relationship r) {
            addRelationship(r);
        } else if (value instanceof Path p) {
            p.nodes().forEach(this::addNode);
            p.relationships().forEach(this::addRelationship);
        } else if (value instanceof List<?> list) {
            for (Object v : list) scan(v);
        } else if (value instanceof Map<?, ?> map) {
            for (Object v : map.values()) scan(v);
        }
    }

    public VizPayload snapshot() {
        List<VizRelationship> rels = pending.values().stream()
                .filter(p -> nodes.containsKey(p.from) && nodes.containsKey(p.to))
                .map(p -> new VizRelationship(p.id, p.from, p.to, p.type, p.type))
                .toList();
        return new VizPayload(List.copyOf(nodes.values()), rels);
    }

    private void addNode(Node n) {
        String id = n.elementId();
        if (nodes.containsKey(id) || nodes.size() >= MAX_NODES) return;
        List<String> labels = streamToList(n.labels());
        Map<String, Object> properties = n.asMap();
        String caption = pickCaption(properties, labels, id);
        nodes.put(id, new VizNode(id, labels, caption, properties));
    }

    private void addRelationship(Relationship r) {
        String id = r.elementId();
        if (pending.containsKey(id)) return;
        pending.put(id, new PendingRelationship(id, r.startNodeElementId(), r.endNodeElementId(), r.type()));
    }

    private static String pickCaption(Map<String, Object> properties, List<String> labels, String fallbackId) {
        for (String key : CAPTION_PROPERTY_PRIORITY) {
            Object v = properties.get(key);
            if (v != null) {
                String s = v.toString();
                if (!s.isBlank()) return s;
            }
        }
        if (!labels.isEmpty()) return labels.get(0);
        return fallbackId;
    }

    private static List<String> streamToList(Iterable<String> iter) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        iter.forEach(out::add);
        return List.copyOf(out);
    }

    private record PendingRelationship(String id, String from, String to, String type) {}
}
