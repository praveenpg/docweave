package io.docweave;

import io.docweave.batch.BatchProcessor;
import io.docweave.batch.DocumentBatch;
import io.docweave.batch.ElementFileBatch;
import io.docweave.chunking.ChunkOptions;
import io.docweave.chunking.Chunker;
import io.docweave.chunking.ChunkContextProvider;
import io.docweave.chunking.ContextualChunker;
import io.docweave.json.ElementJson;
import io.docweave.partition.PartitionContext;
import io.docweave.partition.PartitionOptions;
import io.docweave.partition.Partitioner;
import io.docweave.partition.PartitionerRegistry;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Public service exposure and optional-runtime boundaries across the convenience artifact. */
class PublicApiTest {
    /** Creates public-contract controls without initializing optional services. */
    PublicApiTest() {
    }

    /** Optional native inference remains outside default convenience dependencies. */
    @Test
    void convenienceArtifactExcludesExplicitNativeLayoutRuntime() {
        assertThrows(ClassNotFoundException.class, () -> Class.forName("ai.onnxruntime.OrtEnvironment"));
        assertThrows(ClassNotFoundException.class, () -> Class.forName("io.docweave.layout.OnnxTableLayout"));
    }

    /** Factories expose interface contracts while implementations retain package visibility. */
    @Test
    void servicesAndBuildersExposeOnlyInterfacesWithHiddenImplementations() {
        for (final Object api : List.of(DocWeave.create(), Chunker.create(),
                     ContextualChunker.create(
                             request -> new ChunkContextProvider.Context("context", "provider", "model")),
                     ElementJson.create(), BatchProcessor.create(), DocumentBatch.create(), ElementFileBatch.create(),
                     PartitionOptions.builder(), ChunkOptions.builder(), PartitionerRegistry.create(),
                     PartitionerRegistry.builder())) {
            assertFalse(Modifier.isPublic(api.getClass().getModifiers()), api.getClass().getName());
            assertTrue(api.getClass().getInterfaces()[0].isInterface());
        }
        for (final Class<?> contract :
                List.of(DocWeave.class, Chunker.class, ContextualChunker.class, ChunkContextProvider.class,
                        ElementJson.class, BatchProcessor.class, DocumentBatch.class, ElementFileBatch.class,
                        PartitionOptions.Builder.class, ChunkOptions.Builder.class, Partitioner.class,
                        PartitionContext.class, PartitionerRegistry.class, PartitionerRegistry.Builder.class)) {
            assertTrue(contract.isInterface(), contract.getName());
        }
    }
}
