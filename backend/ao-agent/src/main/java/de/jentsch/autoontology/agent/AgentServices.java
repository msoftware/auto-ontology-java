// SPDX-License-Identifier: Apache-2.0
package de.jentsch.autoontology.agent;

import de.jentsch.autoontology.common.AoProperties;
import de.jentsch.autoontology.common.AoTime;
import de.jentsch.autoontology.ingestion.PiiDetection;
import de.jentsch.autoontology.llm.Embedder;
import de.jentsch.autoontology.llm.LlmGateway;
import de.jentsch.autoontology.store.CatalogRepository;
import de.jentsch.autoontology.store.RuntimeSettings;
import de.jentsch.autoontology.store.SemanticRepository;
import de.jentsch.autoontology.vector.VectorStore;

/** Dependencies of the agent nodes. */
public record AgentServices(
        LlmGateway llm,
        Embedder embedder,
        VectorStore vectors,
        CatalogRepository catalog,
        SemanticRepository semantic,
        JoinPathFinder joins,
        RuntimeSettings settings,
        AoProperties props,
        AoTime time,
        PiiDetection pii) {}
