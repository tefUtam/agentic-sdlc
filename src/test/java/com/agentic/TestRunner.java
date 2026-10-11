package com.agentic;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Runs every public test* method of the listed classes on a fresh instance. Exit code 1 on any failure. */
public final class TestRunner {
    public static void main(String[] args) throws Exception {
        List<Class<?>> classes = List.of(StateMachineTest.class, DagTest.class, EventLogTest.class, ArtifactStoreTest.class,
                GateTest.class, PolicyTest.class, ApprovalTest.class, ApprovalServerTest.class, OrchestratorTest.class);
        int passed = 0, failed = 0;
        long t0 = System.currentTimeMillis();
        for (Class<?> c : classes) {
            Method[] methods = c.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method m : methods) {
                if (!m.getName().startsWith("test")) continue;
                String name = c.getSimpleName() + "." + m.getName();
                try {
                    var ctor = c.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    m.setAccessible(true);
                    m.invoke(ctor.newInstance());
                    passed++;
                    System.out.println("PASS  " + name);
                } catch (InvocationTargetException e) {
                    failed++;
                    System.out.println("FAIL  " + name + "  ->  " + e.getCause());
                    StackTraceElement[] st = e.getCause().getStackTrace();
                    for (int i = 0; i < Math.min(4, st.length); i++) System.out.println("        at " + st[i]);
                }
            }
        }
        System.out.printf("%n%d passed, %d failed (%d ms)%n", passed, failed, System.currentTimeMillis() - t0);
        System.exit(failed == 0 ? 0 : 1);
    }
}
