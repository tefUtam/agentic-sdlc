package com.agentic;

import com.agentic.store.Event;
import com.agentic.store.EventLog;
import com.agentic.store.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class EventLogTest {

    private static EventLog filled(Path file, int n) {
        EventLog log = new EventLog(file);
        for (int i = 0; i < n; i++) log.append("E" + i, "N", "tester", Json.obj("i", i));
        return log;
    }

    public void testChainVerifiesAfterManyAppends() {
        Assert.check(filled(null, 20).verifyChain(), "fresh chain should verify");
    }

    public void testEditingAnEventBreaksTheChain() {
        List<Event> events = new ArrayList<>(filled(null, 5).events());
        Event e = events.get(2);
        events.set(2, new Event(e.seq(), e.ts(), e.type(), e.nodeId(), e.actor(), "{\"i\":999}", e.prevHash(), e.hash()));
        Assert.check(!EventLog.verify(events), "tampered payload must be detected");
    }

    public void testRemovingOrReorderingEventsBreaksTheChain() {
        List<Event> removed = new ArrayList<>(filled(null, 5).events());
        removed.remove(1);
        Assert.check(!EventLog.verify(removed), "removal must be detected");
        List<Event> swapped = new ArrayList<>(filled(null, 5).events());
        Event tmp = swapped.get(1);
        swapped.set(1, swapped.get(2));
        swapped.set(2, tmp);
        Assert.check(!EventLog.verify(swapped), "reordering must be detected");
    }

    public void testEventsAreMirroredToJsonlFile() throws Exception {
        Path file = Files.createTempDirectory("evlog").resolve("events.jsonl");
        EventLog log = filled(file, 4);
        List<String> lines = Files.readAllLines(file);
        Assert.eq(4, lines.size());
        Assert.contains(lines.get(0), "\"type\":\"E0\"");
        Assert.contains(lines.get(3), "\"hash\":\"" + log.events().get(3).hash() + "\"");
    }

    public void testConcurrentAppendsStayOrderedAndVerifiable() throws Exception {
        EventLog log = new EventLog(null);
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            Thread th = new Thread(() -> { for (int i = 0; i < 50; i++) log.append("X", null, "t", Json.obj()); });
            threads.add(th);
            th.start();
        }
        for (Thread th : threads) th.join();
        Assert.eq(400, log.events().size());
        Assert.check(log.verifyChain(), "chain must survive concurrent appends");
    }

    public void testJsonEscapingKeepsPayloadValid() {
        String json = Json.write(Json.obj("s", "quote\" backslash\\ newline\n tab\t", "n", 3, "b", true, "l", List.of(1, "x"), "z", null));
        Assert.eq("{\"s\":\"quote\\\" backslash\\\\ newline\\n tab\\t\",\"n\":3,\"b\":true,\"l\":[1,\"x\"],\"z\":null}", json);
    }
}
