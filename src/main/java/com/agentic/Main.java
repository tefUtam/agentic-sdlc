package com.agentic;

import com.agentic.agent.StubLlmClient;
import com.agentic.approval.ApprovalServer;
import com.agentic.approval.ApprovalStatus;
import com.agentic.approval.AutoApprover;
import com.agentic.approval.CliApprover;
import com.agentic.engine.Orchestrator;
import com.agentic.engine.SdlcGraphFactory;
import com.agentic.engine.TraceRenderer;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** CLI entry point: runs one requirement through the orchestrated SDLC graph. */
public final class Main {
    private static final String DEFAULT_REQUIREMENT =
            "Build a URL shortener service with APIs to create short links and redirect, click analytics, "
                    + "and reliability features such as rate limiting and health checks.";
    private static final String AMBIGUOUS_REQUIREMENT = "Make links smarter and more secure";

    private Main() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> opt = parse(args);
        if (opt.containsKey("help")) {
            usage();
            return;
        }
        String scenario = opt.getOrDefault("scenario", "greenfield");
        String requirement;
        switch (scenario) {
            case "greenfield" -> requirement = opt.getOrDefault("requirement", DEFAULT_REQUIREMENT);
            case "ambiguous" -> requirement = opt.getOrDefault("requirement", AMBIGUOUS_REQUIREMENT);
            case "brownfield" -> {
                System.err.println("The brownfield scenario is a later build step (needs impact analysis and re-planning).");
                System.exit(2);
                return;
            }
            default -> {
                System.err.println("Unknown scenario: " + scenario);
                usage();
                System.exit(2);
                return;
            }
        }

        int parallelism = Integer.parseInt(opt.getOrDefault("parallelism", "4"));
        long latency = Long.parseLong(opt.getOrDefault("stub-latency-ms", "150"));
        int port = Integer.parseInt(opt.getOrDefault("port", "8080"));
        Path out = Path.of(opt.getOrDefault("out", "runs"));

        Orchestrator o = Orchestrator.create(out, requirement, SdlcGraphFactory.greenfield(), new StubLlmClient(latency),
                new Orchestrator.Config(parallelism, Duration.ofMinutes(Long.parseLong(opt.getOrDefault("timeout-minutes", "10")))));

        long t0 = System.nanoTime();
        o.eventLog().addListener(e -> printEvent(e.type(), e.nodeId(), e.payloadJson(), (System.nanoTime() - t0) / 1e9, port));

        ApprovalServer server = null;
        if (port >= 0) {
            server = new ApprovalServer(o.approvals(), o::statusSnapshot, port);
            server.start();
            System.out.println("Approval API listening on http://127.0.0.1:" + server.port()
                    + "  (GET /approvals, POST /approvals/{id}/decision, GET /status)");
        }
        AutoApprover auto = null;
        if (opt.containsKey("auto-approve")) {
            auto = AutoApprover.start(o.approvals(), r -> ApprovalStatus.APPROVED, "auto-approver");
            System.out.println("Auto-approve is ON (demo mode): approvals are recorded as 'auto-approver'.");
        }
        if (opt.containsKey("cli-approve")) CliApprover.start(o.approvals(), System.in, System.out, System.getProperty("user.name", "cli-user"));

        System.out.println("Scenario: " + scenario + "\nRequirement: " + requirement + "\n");
        Orchestrator.RunResult result = o.run();
        System.out.println(TraceRenderer.render(o, result));

        if (auto != null) auto.stop();
        if (server != null) server.stop();
        System.exit(result.status() == Orchestrator.RunStatus.DONE ? 0 : 1);
    }

    private static void printEvent(String type, String nodeId, String payload, double secs, int port) {
        String line = null;
        switch (type) {
            case "NODE_STATE_CHANGED" -> {
                String to = field(payload, "to");
                if (Pattern.matches("RUNNING|SUCCEEDED|AWAITING_APPROVAL|FAILED|BLOCKED|ABORTED|SAFE_STOPPED", to)) {
                    line = String.format("%-18s %-17s %s", nodeId, to, field(payload, "reason"));
                }
            }
            case "APPROVAL_REQUESTED" -> {
                String id = field(payload, "approvalId");
                line = "APPROVAL REQUESTED " + id + " at " + nodeId + (port >= 0
                        ? "\n           approve: curl -s -X POST http://127.0.0.1:" + port + "/approvals/" + id
                        + "/decision -d 'decision=APPROVE&approver=<you>&rationale=<why>'" : "");
            }
            case "APPROVAL_DECIDED" -> line = "APPROVAL DECIDED   " + field(payload, "approvalId") + " -> " + field(payload, "decision");
            case "POLICY_EVALUATED" -> {
                if (!field(payload, "outcome").equals("ALLOW")) line = "POLICY " + field(payload, "rule") + " " + field(payload, "outcome") + ": " + field(payload, "detail");
            }
            case "GATE_EVALUATED" -> {
                if (payload.contains("\"passed\":false,\"checks\"")) line = "GATE FAILED at " + nodeId;
            }
            case "SAFE_STOP", "RUN_ABORTED" -> line = type + ": " + field(payload, "reason");
            default -> { }
        }
        if (line != null) System.out.printf("[%6.2fs] %s%n", secs, line);
    }

    private static String field(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            if (!args[i].startsWith("--")) continue;
            String key = args[i].substring(2);
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) m.put(key, args[++i]);
            else m.put(key, "true");
        }
        return m;
    }

    private static void usage() {
        System.out.println("""
                Usage: ./run.sh [options]
                  --scenario greenfield|ambiguous   (default greenfield)
                  --requirement "text"              override the scenario's requirement
                  --auto-approve                    demo mode: approve every checkpoint automatically
                  --cli-approve                     prompt for approvals on stdin
                  --port N                          approval API port (default 8080, -1 disables, 0 = ephemeral)
                  --parallelism N                   agent worker pool size (default 4)
                  --stub-latency-ms N               simulated agent latency (default 150)
                  --timeout-minutes N               run timeout (default 10)
                  --out DIR                         output directory (default ./runs)
                Without --auto-approve or --cli-approve, approve via the HTTP API (curl examples are printed).
                """);
    }
}
