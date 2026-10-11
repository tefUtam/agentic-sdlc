package com.agentic.gate;

import com.agentic.model.ArtifactStatus;
import com.agentic.model.ArtifactType;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Library of reusable, deterministic gate checks. */
public final class GateChecks {
    private GateChecks() {}

    /** Entry: every required input exists and is VALID. */
    public static NamedCheck inputsValid() {
        return new NamedCheck("inputs-present-and-valid", ctx -> {
            for (ArtifactType t : ctx.requiredInputs()) {
                boolean ok = ctx.inputs().stream().anyMatch(a -> a.type() == t && a.status() == ArtifactStatus.VALID);
                if (!ok) return GateResult.fail("missing or invalid input " + t);
            }
            return GateResult.ok("all " + ctx.requiredInputs().size() + " inputs valid");
        });
    }

    /** Exit: outputs are exactly the declared artifact types and none is blank. */
    public static NamedCheck outputsMatchContract() {
        return new NamedCheck("output-contract", ctx -> {
            Set<ArtifactType> got = ctx.outputs().keySet();
            Set<ArtifactType> want = new HashSet<>(ctx.expectedOutputs());
            if (!got.equals(want)) return GateResult.fail("expected " + want + " but got " + got);
            for (var e : ctx.outputs().entrySet()) {
                if (e.getValue() == null || e.getValue().isBlank()) return GateResult.fail(e.getKey() + " is blank");
            }
            return GateResult.ok("outputs match contract");
        });
    }

    public static NamedCheck contains(ArtifactType type, String needle) {
        return new NamedCheck("contains[" + type + ": " + needle + "]", ctx -> {
            String c = ctx.outputs().get(type);
            return c != null && c.contains(needle) ? GateResult.ok("found") : GateResult.fail("'" + needle + "' not found in " + type);
        });
    }

    public static NamedCheck matches(String name, ArtifactType type, String regex) {
        Pattern p = Pattern.compile(regex);
        return new NamedCheck(name, ctx -> {
            String c = ctx.outputs().get(type);
            return c != null && p.matcher(c).find() ? GateResult.ok("matched") : GateResult.fail("pattern " + regex + " not found in " + type);
        });
    }

    public static NamedCheck numberAtLeast(String name, ArtifactType type, String regexWithGroup, double min) {
        Pattern p = Pattern.compile(regexWithGroup);
        return new NamedCheck(name, ctx -> {
            Double v = extract(ctx.outputs().get(type), p);
            if (v == null) return GateResult.fail("no value found in " + type);
            return v >= min ? GateResult.ok(v + " >= " + min) : GateResult.fail(v + " < required " + min);
        });
    }

    public static NamedCheck numberAtMost(String name, ArtifactType type, String regexWithGroup, double max) {
        Pattern p = Pattern.compile(regexWithGroup);
        return new NamedCheck(name, ctx -> {
            Double v = extract(ctx.outputs().get(type), p);
            if (v == null) return GateResult.fail("no value found in " + type);
            return v <= max ? GateResult.ok(v + " <= " + max) : GateResult.fail(v + " > allowed " + max);
        });
    }

    public static Double extract(String content, Pattern p) {
        if (content == null) return null;
        Matcher m = p.matcher(content);
        if (!m.find()) return null;
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
