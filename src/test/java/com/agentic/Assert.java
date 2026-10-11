package com.agentic;

import java.util.Objects;

/** Minimal assertions (JUnit is not available offline; tests are structured so they port to JUnit 5 directly). */
final class Assert {
    private Assert() {}

    interface Body { void run() throws Exception; }

    static void check(boolean cond, String message) {
        if (!cond) throw new AssertionError(message);
    }

    static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
    }

    static void contains(String haystack, String needle) {
        if (haystack == null || !haystack.contains(needle)) throw new AssertionError("expected to find <" + needle + "> in <" + haystack + ">");
    }

    static <T extends Throwable> T throwsEx(Class<T> type, Body body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (type.isInstance(t)) return type.cast(t);
            throw new AssertionError("expected " + type.getSimpleName() + " but got " + t, t);
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }
}
