package com.agentic;

import com.agentic.model.Artifact;
import com.agentic.model.ArtifactStatus;
import com.agentic.store.ArtifactStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.agentic.model.ArtifactType.*;

public class ArtifactStoreTest {

    private static ArtifactStore store() throws Exception {
        return new ArtifactStore(Files.createTempDirectory("artifacts"));
    }

    public void testVersionsIncrementPerTypeAndFilesAreWritten() throws Exception {
        ArtifactStore s = store();
        Artifact a1 = s.commit(ARCH_DOC, "one", "N", 1, List.of());
        Artifact a2 = s.commit(ARCH_DOC, "two", "N", 2, List.of());
        Artifact other = s.commit(DB_SCHEMA, "x", "M", 1, List.of());
        Assert.eq("ARCH_DOC:v1", a1.id());
        Assert.eq("ARCH_DOC:v2", a2.id());
        Assert.eq("DB_SCHEMA:v1", other.id());
        Assert.eq("one", Files.readString(Path.of(a1.path())));
    }

    public void testHashesAreContentAddressed() throws Exception {
        ArtifactStore s = store();
        Artifact a = s.commit(ARCH_DOC, "same", "N", 1, List.of());
        Artifact b = s.commit(ARCH_DOC, "same", "N", 2, List.of());
        Artifact c = s.commit(ARCH_DOC, "different", "N", 3, List.of());
        Assert.eq(a.contentHash(), b.contentHash());
        Assert.check(!a.contentHash().equals(c.contentHash()), "different content must hash differently");
    }

    public void testOnlyValidArtifactsAreResolvedAndNewestWins() throws Exception {
        ArtifactStore s = store();
        Artifact v1 = s.commit(ARCH_DOC, "one", "N", 1, List.of());
        Assert.check(s.latestValid(ARCH_DOC).isEmpty(), "DRAFT must not resolve");
        s.setStatus(v1.id(), ArtifactStatus.VALID);
        Artifact v2 = s.commit(ARCH_DOC, "two", "N", 2, List.of());
        s.setStatus(v2.id(), ArtifactStatus.REJECTED);
        Assert.eq("ARCH_DOC:v1", s.latestValid(ARCH_DOC).orElseThrow().id()); // rejected v2 is ignored but kept
        Assert.eq(2, s.all().size());
    }

    public void testLineageWalksAllAncestorsWithoutDuplicates() throws Exception {
        ArtifactStore s = store();
        Artifact req = s.commit(REQUIREMENT_SPEC, "r", "N", 1, List.of());
        Artifact arch = s.commit(ARCH_DOC, "a", "N", 1, List.of(req.id()));
        Artifact api = s.commit(OPENAPI_SPEC, "o", "N", 1, List.of(arch.id()));
        Artifact db = s.commit(DB_SCHEMA, "d", "N", 1, List.of(arch.id()));
        Artifact code = s.commit(CODE_CORE, "c", "N", 1, List.of(api.id(), db.id()));
        List<String> ids = s.lineage(code.id()).stream().map(Artifact::id).toList();
        Assert.eq(4, ids.size());
        Assert.check(ids.containsAll(List.of(req.id(), arch.id(), api.id(), db.id())), "lineage: " + ids);
    }

    public void testUnknownArtifactIsAnError() throws Exception {
        ArtifactStore s = store();
        Assert.throwsEx(IllegalArgumentException.class, () -> s.content("NOPE:v1"));
        Assert.throwsEx(IllegalArgumentException.class, () -> s.setStatus("NOPE:v1", ArtifactStatus.VALID));
    }
}
