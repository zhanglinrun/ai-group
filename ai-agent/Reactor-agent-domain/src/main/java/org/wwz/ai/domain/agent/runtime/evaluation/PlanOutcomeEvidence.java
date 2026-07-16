package org.wwz.ai.domain.agent.runtime.evaluation;

import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Outcome evidence derived from trusted runtime facts rather than model claims.
 *
 * <p>The factories deliberately compute {@code verified} from test-runner status,
 * an HTTP resolver status, or the local artifact filesystem. An LLM response cannot
 * directly mark an outcome as verified.</p>
 */
public final class PlanOutcomeEvidence {

    private final PlanOutcomeEvidenceType type;
    private final String name;
    private final boolean required;
    private final boolean verified;
    private final String reference;

    private PlanOutcomeEvidence(PlanOutcomeEvidenceType type,
                                String name,
                                boolean required,
                                boolean verified,
                                String reference) {
        this.type = type;
        this.name = StringUtils.defaultIfBlank(name, type.name().toLowerCase());
        this.required = required;
        this.verified = verified;
        this.reference = StringUtils.defaultString(reference);
    }

    public static PlanOutcomeEvidence testResult(String name,
                                                 boolean required,
                                                 int exitCode,
                                                 int testsRun,
                                                 int failures,
                                                 String reportReference) {
        boolean verified = exitCode == 0 && testsRun > 0 && failures == 0;
        return new PlanOutcomeEvidence(
                PlanOutcomeEvidenceType.TEST,
                name,
                required,
                verified,
                reportReference
        );
    }

    public static PlanOutcomeEvidence citation(String name,
                                               boolean required,
                                               URI source,
                                               int resolverStatusCode) {
        String scheme = source == null ? "" : StringUtils.defaultString(source.getScheme()).toLowerCase();
        boolean supportedScheme = "http".equals(scheme) || "https".equals(scheme);
        boolean verified = supportedScheme && resolverStatusCode >= 200 && resolverStatusCode < 400;
        return new PlanOutcomeEvidence(
                PlanOutcomeEvidenceType.CITATION,
                name,
                required,
                verified,
                source == null ? "" : source.toString()
        );
    }

    public static PlanOutcomeEvidence artifact(String name, boolean required, Path artifactPath) {
        Path normalized = artifactPath == null ? null : artifactPath.toAbsolutePath().normalize();
        boolean verified = normalized != null && Files.isRegularFile(normalized) && Files.isReadable(normalized);
        return new PlanOutcomeEvidence(
                PlanOutcomeEvidenceType.ARTIFACT,
                name,
                required,
                verified,
                normalized == null ? "" : normalized.toString()
        );
    }

    /**
     * Verify an artifact emitted by the trusted runtime registry.
     *
     * <p>The registry binding proves that a concrete tool call emitted the file. This factory then
     * checks that the binding has a stable file identity and a reference that the application can
     * actually hand to a downstream consumer. It deliberately performs no network request.</p>
     */
    public static PlanOutcomeEvidence registeredArtifact(String name,
                                                         boolean required,
                                                         String toolCallId,
                                                         String fileName,
                                                         String artifactReference) {
        String reference = StringUtils.trimToEmpty(artifactReference);
        boolean verified = StringUtils.isNotBlank(toolCallId)
                && StringUtils.isNotBlank(fileName)
                && isUsableArtifactReference(reference);
        return new PlanOutcomeEvidence(
                PlanOutcomeEvidenceType.ARTIFACT,
                StringUtils.defaultIfBlank(name, fileName),
                required,
                verified,
                reference
        );
    }

    private static boolean isUsableArtifactReference(String reference) {
        if (StringUtils.isBlank(reference)) {
            return false;
        }
        if (reference.startsWith("/") && !reference.startsWith("//")) {
            return true;
        }
        try {
            URI uri = URI.create(reference);
            String scheme = StringUtils.defaultString(uri.getScheme()).toLowerCase();
            if (("http".equals(scheme) || "https".equals(scheme))
                    && uri.isAbsolute() && StringUtils.isNotBlank(uri.getHost())) {
                return true;
            }
            if (StringUtils.isNotBlank(scheme)) {
                return false;
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to a local-file check. Malformed URLs are not trusted as references.
        }
        try {
            Path localPath = Path.of(reference).toAbsolutePath().normalize();
            return Files.isRegularFile(localPath) && Files.isReadable(localPath);
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    public PlanOutcomeEvidenceType type() {
        return type;
    }

    public String name() {
        return name;
    }

    public boolean required() {
        return required;
    }

    public boolean verified() {
        return verified;
    }

    public String reference() {
        return reference;
    }
}
