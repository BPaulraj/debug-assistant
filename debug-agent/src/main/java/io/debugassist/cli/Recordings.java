package io.debugassist.cli;

import io.debugassist.agent.core.Json;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Finds and reads recording files. */
final class Recordings {

    private Recordings() {
    }

    static List<File> find(List<File> roots) {
        List<File> out = new ArrayList<File>();
        for (File root : roots) {
            if (!root.exists()) {
                throw new IllegalArgumentException("not found: " + root.getAbsolutePath());
            }
            collect(root, out);
        }
        Collections.sort(out);
        return out;
    }

    private static void collect(File f, List<File> out) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) {
                    collect(c, out);
                }
            }
        } else if (f.getName().endsWith(".json")) {
            out.add(f);
        }
    }

    static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(File f) throws IOException {
        Object v = Json.parse(read(f));
        if (!(v instanceof Map) || !((Map<String, Object>) v).containsKey("traceId")) {
            throw new IllegalArgumentException("not a debug-agent recording: " + f);
        }
        return (Map<String, Object>) v;
    }

    static void printList(List<File> roots) throws IOException {
        Map<String, List<Map<String, Object>>> byTrace = new LinkedHashMap<String, List<Map<String, Object>>>();
        for (File f : find(roots)) {
            Map<String, Object> rec;
            try {
                rec = parse(f);
            } catch (RuntimeException skip) {
                continue;
            }
            String id = (String) rec.get("traceId");
            List<Map<String, Object>> list = byTrace.get(id);
            if (list == null) {
                list = new ArrayList<Map<String, Object>>();
                byTrace.put(id, list);
            }
            list.add(rec);
        }
        List<List<Map<String, Object>>> traces = new ArrayList<List<Map<String, Object>>>(byTrace.values());
        Comparator<Map<String, Object>> byStart = new Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Long.compare(((Number) a.get("start")).longValue(), ((Number) b.get("start")).longValue());
            }
        };
        for (List<Map<String, Object>> t : traces) {
            Collections.sort(t, byStart);
        }
        Collections.sort(traces, new Comparator<List<Map<String, Object>>>() {
            @Override
            public int compare(List<Map<String, Object>> a, List<Map<String, Object>> b) {
                return byStart.compare(a.get(0), b.get(0));
            }
        });
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        for (List<Map<String, Object>> t : traces) {
            Map<String, Object> first = t.get(0);
            Set<String> apps = new LinkedHashSet<String>();
            boolean error = false;
            for (Map<String, Object> r : t) {
                apps.add((String) r.get("app"));
                error |= Boolean.TRUE.equals(r.get("error"));
            }
            System.out.printf("%s  %s  %-5s %-30s %s%n",
                    fmt.format(new Date(((Number) first.get("start")).longValue())),
                    first.get("traceId"), error ? "ERROR" : "", apps, first.get("summary"));
        }
        System.out.println(traces.size() + " trace(s)");
    }
}
