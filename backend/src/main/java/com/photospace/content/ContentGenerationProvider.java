package com.photospace.content;

/**
 * An AI provider that writes Instagram content. Implementations are adapters: provider SDK types stay inside
 * them, and they report every failure as a {@link ContentGenerationException}. The result is a proposal only;
 * {@link ContentValidator} checks it, and a person edits and approves it.
 */
public interface ContentGenerationProvider {

    /** A short provider name stored with each batch, such as {@code anthropic} or {@code mock}. */
    String provider();

    /** The model name stored with each batch. */
    String model();

    GenerationResult generate(GenerationRequest request);
}
