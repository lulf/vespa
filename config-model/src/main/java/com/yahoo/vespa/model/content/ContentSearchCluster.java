// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.content;

import com.yahoo.config.model.api.ModelContext;
import com.yahoo.config.model.deploy.DeployState;
import com.yahoo.vespa.config.search.DispatchConfig;
import com.yahoo.vespa.config.search.core.ProtonConfig;
import com.yahoo.documentmodel.NewDocumentType;
import com.yahoo.config.model.producer.AbstractConfigProducer;
import com.yahoo.vespa.model.builder.UserConfigBuilder;
import com.yahoo.vespa.model.builder.xml.dom.DomSearchTuningBuilder;
import com.yahoo.vespa.model.builder.xml.dom.ModelElement;
import com.yahoo.vespa.model.builder.xml.dom.VespaDomBuilder;
import com.yahoo.vespa.model.content.cluster.ContentCluster;
import com.yahoo.vespa.model.content.cluster.DomResourceLimitsBuilder;
import com.yahoo.vespa.model.search.AbstractSearchCluster;
import com.yahoo.vespa.model.search.IndexedSearchCluster;
import com.yahoo.vespa.model.search.NodeSpec;
import com.yahoo.vespa.model.search.SearchCluster;
import com.yahoo.vespa.model.search.NamedSchema;
import com.yahoo.vespa.model.search.SchemaDefinitionXMLHandler;
import com.yahoo.vespa.model.search.SearchNode;
import com.yahoo.vespa.model.search.StreamingSearchCluster;
import com.yahoo.vespa.model.search.TransactionLogServer;
import com.yahoo.vespa.model.search.Tuning;
import org.w3c.dom.Element;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Encapsulates the various options for search in a content model.
 * Wraps a search cluster from com.yahoo.vespa.model.search.
 */
public class ContentSearchCluster extends AbstractConfigProducer implements ProtonConfig.Producer, DispatchConfig.Producer {

    private final boolean flushOnShutdown;

    /** If this is set up for streaming search, it is modelled as one search cluster per search definition */
    private final Map<String, AbstractSearchCluster> clusters = new TreeMap<>();

    /** The single, indexed search cluster this sets up (supporting multiple document types), or null if none */
    private IndexedSearchCluster indexedCluster;
    private Redundancy redundancy;

    private final String clusterName;
    private final Map<String, NewDocumentType> documentDefinitions;
    private final Set<NewDocumentType> globallyDistributedDocuments;
    private Double visibilityDelay = 0.0;

    /** The search nodes of this if it does not have an indexed cluster */
    private final List<SearchNode> nonIndexed = new ArrayList<>();

    private final Map<StorageGroup, NodeSpec> groupToSpecMap = new LinkedHashMap<>();
    private Optional<ResourceLimits> resourceLimits = Optional.empty();
    private final ProtonConfig.Indexing.Optimize.Enum feedSequencerType;

    /** Whether the nodes of this cluster also hosts a container cluster in a hosted system */
    private final boolean combined;

    public void prepare() {
        clusters.values().forEach(cluster -> cluster.prepareToDistributeFiles(getSearchNodes()));
    }

    public static class Builder extends VespaDomBuilder.DomConfigProducerBuilder<ContentSearchCluster> {

        private final Map<String, NewDocumentType> documentDefinitions;
        private final Set<NewDocumentType> globallyDistributedDocuments;
        private final boolean combined;

        public Builder(Map<String, NewDocumentType> documentDefinitions,
                       Set<NewDocumentType> globallyDistributedDocuments,
                       boolean combined) {
            this.documentDefinitions = documentDefinitions;
            this.globallyDistributedDocuments = globallyDistributedDocuments;
            this.combined = combined;
        }

        @Override
        protected ContentSearchCluster doBuild(DeployState deployState, AbstractConfigProducer ancestor, Element producerSpec) {
            ModelElement clusterElem = new ModelElement(producerSpec);
            String clusterName = ContentCluster.getClusterId(clusterElem);
            Boolean flushOnShutdownElem = clusterElem.childAsBoolean("engine.proton.flush-on-shutdown");

            ContentSearchCluster search = new ContentSearchCluster(ancestor,
                                                                   clusterName,
                                                                   deployState.getProperties(),
                                                                   documentDefinitions,
                                                                   globallyDistributedDocuments,
                                                                   getFlushOnShutdown(flushOnShutdownElem, deployState),
                                                                   combined);

            ModelElement tuning = clusterElem.childByPath("engine.proton.tuning");
            if (tuning != null) {
                search.setTuning(new DomSearchTuningBuilder().build(deployState, search, tuning.getXml()));
            }
            ModelElement protonElem = clusterElem.childByPath("engine.proton");
            if (protonElem != null) {
                search.setResourceLimits(DomResourceLimitsBuilder.build(protonElem));
            }

            buildAllStreamingSearchClusters(deployState, clusterElem, clusterName, search);
            buildIndexedSearchCluster(deployState, clusterElem, clusterName, search);
            return search;
        }

        private boolean getFlushOnShutdown(Boolean flushOnShutdownElem, DeployState deployState) {
            if (flushOnShutdownElem != null) {
                return flushOnShutdownElem;
            }
            return ! stateIsHosted(deployState);
        }

        private Double getQueryTimeout(ModelElement clusterElem) {
            return clusterElem.childAsDouble("engine.proton.query-timeout");
        }

        private void buildAllStreamingSearchClusters(DeployState deployState, ModelElement clusterElem, String clusterName, ContentSearchCluster search) {
            ModelElement docElem = clusterElem.child("documents");

            if (docElem == null) {
                return;
            }

            for (ModelElement docType : docElem.subElements("document")) {
                String mode = docType.stringAttribute("mode");
                if ("streaming".equals(mode)) {
                    buildStreamingSearchCluster(deployState, clusterElem, clusterName, search, docType);
                }
            }
        }

        private void buildStreamingSearchCluster(DeployState deployState, ModelElement clusterElem, String clusterName,
                                                 ContentSearchCluster search, ModelElement docType) {
            String docTypeName = docType.stringAttribute("type");
            StreamingSearchCluster cluster = new StreamingSearchCluster(search, clusterName + "." + docTypeName, 0, docTypeName, clusterName);
            search.addSearchCluster(deployState, cluster, getQueryTimeout(clusterElem), Arrays.asList(docType));
        }

        private void buildIndexedSearchCluster(DeployState deployState, ModelElement clusterElem,
                                               String clusterName, ContentSearchCluster search) {
            List<ModelElement> indexedDefs = getIndexedSchemas(clusterElem);
            if (!indexedDefs.isEmpty()) {
                IndexedSearchCluster isc = new IndexedSearchCluster(search, clusterName, 0, deployState);
                isc.setRoutingSelector(clusterElem.childAsString("documents.selection"));

                Double visibilityDelay = clusterElem.childAsDouble("engine.proton.visibility-delay");
                if (visibilityDelay != null) {
                    search.setVisibilityDelay(visibilityDelay);
                }

                search.addSearchCluster(deployState, isc, getQueryTimeout(clusterElem), indexedDefs);
            }
        }

        private List<ModelElement> getIndexedSchemas(ModelElement clusterElem) {
            List<ModelElement> indexedDefs = new ArrayList<>();
            ModelElement docElem = clusterElem.child("documents");
            if (docElem == null) {
                return indexedDefs;
            }

            for (ModelElement docType : docElem.subElements("document")) {
                String mode = docType.stringAttribute("mode");
                if ("index".equals(mode)) {
                    indexedDefs.add(docType);
                }
            }
            return indexedDefs;
        }
    }

    private static ProtonConfig.Indexing.Optimize.Enum convertFeedSequencerType(String sequencerType) {
        try {
            return ProtonConfig.Indexing.Optimize.Enum.valueOf(sequencerType);
        } catch (Throwable t) {
            return ProtonConfig.Indexing.Optimize.Enum.LATENCY;
        }
    }

    private ContentSearchCluster(AbstractConfigProducer parent,
                                 String clusterName,
                                 ModelContext.Properties featureFlags,
                                 Map<String, NewDocumentType> documentDefinitions,
                                 Set<NewDocumentType> globallyDistributedDocuments,
                                 boolean flushOnShutdown,
                                 boolean combined)
    {
        super(parent, "search");
        this.clusterName = clusterName;
        this.documentDefinitions = documentDefinitions;
        this.globallyDistributedDocuments = globallyDistributedDocuments;
        this.flushOnShutdown = flushOnShutdown;
        this.combined = combined;
        feedSequencerType = convertFeedSequencerType(featureFlags.feedSequencerType());
    }

    public void setVisibilityDelay(double delay) {
        this.visibilityDelay=delay;
        if (hasIndexedCluster()) {
            indexedCluster.setVisibilityDelay(delay);
        }
    }

    private void addSearchCluster(DeployState deployState, SearchCluster cluster, Double queryTimeout, List<ModelElement> documentDefs) {
        addSchemas(deployState, documentDefs, cluster);

        if (queryTimeout != null) {
            cluster.setQueryTimeout(queryTimeout);
        }
        cluster.defaultDocumentsConfig();
        cluster.deriveSchemas(deployState);
        addCluster(cluster);
    }

    private void addSchemas(DeployState deployState, List<ModelElement> searchDefs, AbstractSearchCluster sc) {
        for (ModelElement e : searchDefs) {
            SchemaDefinitionXMLHandler schemaDefinitionXMLHandler = new SchemaDefinitionXMLHandler(e);
            NamedSchema searchDefinition =
                    schemaDefinitionXMLHandler.getResponsibleSearchDefinition(deployState.getSchemas());
            if (searchDefinition == null)
                throw new RuntimeException("Search definition parsing error or file does not exist: '" +
                                           schemaDefinitionXMLHandler.getName() + "'");

            // TODO: remove explicit building of user configs when the complete content model is built using builders.
            sc.getLocalSDS().add(new AbstractSearchCluster.SchemaSpec(searchDefinition,
                                                                      UserConfigBuilder.build(e.getXml(), deployState, deployState.getDeployLogger())));
            //need to get the document names from this sdfile
            sc.addDocumentNames(searchDefinition);
        }
    }

    private void addCluster(AbstractSearchCluster sc) {
        if (clusters.containsKey(sc.getClusterName())) {
            throw new IllegalArgumentException("I already have registered cluster '" + sc.getClusterName() + "'");
        }
        if (sc instanceof IndexedSearchCluster) {
            if (indexedCluster != null) {
                throw new IllegalArgumentException("I already have one indexed cluster named '" + indexedCluster.getClusterName());
            }
            indexedCluster = (IndexedSearchCluster)sc;
        }
        clusters.put(sc.getClusterName(), sc);
    }

    public List<SearchNode> getSearchNodes() {
        return hasIndexedCluster() ? getIndexed().getSearchNodes() : nonIndexed;
    }

    public void addSearchNode(DeployState deployState, ContentNode node, StorageGroup parentGroup, ModelElement element) {
        AbstractConfigProducer parent = hasIndexedCluster() ? getIndexed() : this;

        NodeSpec spec = getNextSearchNodeSpec(parentGroup);
        SearchNode searchNode;
        TransactionLogServer tls;
        Optional<Tuning> tuning = Optional.ofNullable(this.tuning);
        if (element == null) {
            searchNode = SearchNode.create(parent, "" + node.getDistributionKey(), node.getDistributionKey(), spec,
                                           clusterName, node, flushOnShutdown, tuning, resourceLimits, parentGroup.isHosted(), combined);
            searchNode.setHostResource(node.getHostResource());
            searchNode.initService(deployState.getDeployLogger());

            tls = new TransactionLogServer(searchNode, clusterName);
            tls.setHostResource(searchNode.getHostResource());
            tls.initService(deployState.getDeployLogger());
        } else {
            searchNode = new SearchNode.Builder(""+node.getDistributionKey(), spec, clusterName, node, flushOnShutdown, tuning, resourceLimits, combined).build(deployState, parent, element.getXml());
            tls = new TransactionLogServer.Builder(clusterName).build(deployState, searchNode, element.getXml());
        }
        searchNode.setTls(tls);
        if (hasIndexedCluster()) {
            getIndexed().addSearcher(searchNode);
        } else {
            nonIndexed.add(searchNode);
        }
    }

    /** Translates group ids to continuous 0-base "row" id integers */
    private NodeSpec getNextSearchNodeSpec(StorageGroup parentGroup) {
        NodeSpec spec = groupToSpecMap.get(parentGroup);
        if (spec == null) {
            spec = new NodeSpec(groupToSpecMap.size(), 0);
        } else {
            spec = new NodeSpec(spec.groupIndex(), spec.partitionId() + 1);
        }
        groupToSpecMap.put(parentGroup, spec);
        return spec;
    }

    private Tuning tuning;

    public void setTuning(Tuning tuning) { this.tuning = tuning; }

    private void setResourceLimits(ResourceLimits resourceLimits) {
        this.resourceLimits = Optional.of(resourceLimits);
    }

    public boolean usesHierarchicDistribution() {
        return indexedCluster != null && groupToSpecMap.size() > 1;
    }

    public void handleRedundancy(Redundancy redundancy) {
        if (hasIndexedCluster()) {
            if (usesHierarchicDistribution()) {
                indexedCluster.setMaxNodesDownPerFixedRow((redundancy.effectiveFinalRedundancy() / groupToSpecMap.size()) - 1);
            }
            indexedCluster.setSearchableCopies(redundancy.readyCopies());
        }
        this.redundancy = redundancy;
        for (SearchNode node : getSearchNodes()) {
            node.setRedundancy(redundancy.finalRedundancy());
            node.setSearchableCopies(redundancy.readyCopies());
        }
    }

    private Optional<StreamingSearchCluster> findStreamingCluster(String docType) {
        return getClusters().values().stream()
                .filter(StreamingSearchCluster.class::isInstance)
                .map(StreamingSearchCluster.class::cast)
                .filter(ssc -> ssc.getSdConfig().getSearch().getName().equals(docType))
                .findFirst();
    }

    public List<StreamingSearchCluster> getStreamingClusters() {
        return getClusters().values().stream()
                .filter(StreamingSearchCluster.class::isInstance)
                .map(StreamingSearchCluster.class::cast)
                .collect(Collectors.toList());
    }

    @Override
    public void getConfig(ProtonConfig.Builder builder) {
        builder.feeding.concurrency(0.50); // As if specified 1.0 in services.xml
        boolean hasAnyNonIndexedCluster = false;
        for (NewDocumentType type : TopologicalDocumentTypeSorter.sort(documentDefinitions.values())) {
            ProtonConfig.Documentdb.Builder ddbB = new ProtonConfig.Documentdb.Builder();
            String docTypeName = type.getFullName().getName();
            boolean globalDocType = isGloballyDistributed(type);
            ddbB.inputdoctypename(docTypeName)
                .configid(getConfigId())
                .visibilitydelay(visibilityDelay)
                .global(globalDocType);
            Optional<StreamingSearchCluster> ssc = findStreamingCluster(docTypeName);
            if (ssc.isPresent()) {
                hasAnyNonIndexedCluster = true;
                ddbB.inputdoctypename(type.getFullName().getName())
                    .configid(ssc.get().getDocumentDBConfigId())
                        .mode(ProtonConfig.Documentdb.Mode.Enum.STREAMING)
                    .feeding.concurrency(0.0);
            } else if (hasIndexedCluster()) {
                if (getIndexed().hasDocumentDB(type.getFullName().getName())) {
                    getIndexed().fillDocumentDBConfig(type.getFullName().getName(), ddbB);
                    if (tuning != null && tuning.searchNode != null && tuning.searchNode.feeding != null) {
                        ddbB.feeding.concurrency(tuning.searchNode.feeding.concurrency / 2);
                    } else {
                        ddbB.feeding.concurrency(builder.feeding.build().concurrency());
                    }
                } else {
                    hasAnyNonIndexedCluster = true;
                    ddbB.feeding.concurrency(0.0);
                    ddbB.mode(ProtonConfig.Documentdb.Mode.Enum.STORE_ONLY);
                }
            } else {
                hasAnyNonIndexedCluster = true;
                ddbB.feeding.concurrency(0.0);
                ddbB.mode(ProtonConfig.Documentdb.Mode.Enum.STORE_ONLY);
            }
            if (globalDocType) {
                ddbB.visibilitydelay(0.0);
            }
            builder.documentdb(ddbB);
        }

        int numDocumentDbs = builder.documentdb.size();
        builder.initialize(new ProtonConfig.Initialize.Builder().threads(numDocumentDbs + 1));

        resourceLimits.ifPresent(limits -> limits.getConfig(builder));

        if (tuning != null) {
            tuning.getConfig(builder);
        }
        if (redundancy != null) {
            redundancy.getConfig(builder);
        }
        if (hasAnyNonIndexedCluster) {
            builder.feeding.concurrency(builder.feeding.build().concurrency() * 2);
        }
        if ((feedSequencerType == ProtonConfig.Indexing.Optimize.Enum.THROUGHPUT) && (visibilityDelay == 0.0)) {
            // THROUGHPUT and zero visibilityDelay is inconsistent and currently a suboptimal combination, defaulting to LATENCY.
            // TODO: Once we have figured out optimal combination this limitation will be cleaned up.
            builder.indexing.optimize(ProtonConfig.Indexing.Optimize.Enum.LATENCY);
        } else {
            builder.indexing.optimize(feedSequencerType);
        }
    }

    private boolean isGloballyDistributed(NewDocumentType docType) {
        return globallyDistributedDocuments.contains(docType);
    }

    @Override
    public void getConfig(DispatchConfig.Builder builder) {
        if (hasIndexedCluster()) {
            getIndexed().getConfig(builder);
        }
    }

    public Map<String, AbstractSearchCluster> getClusters() { return clusters; }
    public IndexedSearchCluster getIndexed() { return indexedCluster; }
    public boolean hasIndexedCluster()       { return indexedCluster != null; }
    public String getClusterName() { return clusterName; }
}
