// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
/**
 * \class storage::DistributorNode
 * \ingroup storageserver
 *
 * \brief Class for setting up a distributor node.
 */

#pragma once

#include "distributornodecontext.h"
#include "storagenode.h"
#include <vespa/storage/common/distributorcomponent.h>
#include <vespa/storageframework/generic/thread/tickingthread.h>

namespace storage {

class DistributorNode
      : public StorageNode,
        private UniqueTimeCalculator
{
    framework::TickingThreadPool::UP _threadPool;
    DistributorNodeContext& _context;
    uint64_t _lastUniqueTimestampRequested;
    uint32_t _uniqueTimestampCounter;
    bool _manageActiveBucketCopies;
    std::unique_ptr<StorageLink> _retrievedCommunicationManager;

public:
    typedef std::unique_ptr<DistributorNode> UP;
    enum NeedActiveState
    {
        NEED_ACTIVE_BUCKET_STATES_SET,
        NO_NEED_FOR_ACTIVE_STATES
    };

    DistributorNode(const config::ConfigUri & configUri,
                    DistributorNodeContext&,
                    ApplicationGenerationFetcher& generationFetcher,
                    NeedActiveState,
                    std::unique_ptr<StorageLink> communicationManager);
    ~DistributorNode() override;

    const lib::NodeType& getNodeType() const override { return lib::NodeType::DISTRIBUTOR; }
    ResumeGuard pause() override;

    void handleConfigChange(vespa::config::content::core::StorDistributormanagerConfig&);
    void handleConfigChange(vespa::config::content::core::StorVisitordispatcherConfig&);

private:
    void initializeNodeSpecific() override;
    std::unique_ptr<StorageLink> createChain() override;
    api::Timestamp getUniqueTimestamp() override;

    /**
     * Shut down necessary distributor-specific components before shutting
     * down general content node components.
     */
    void shutdownDistributor();
};

} // storage
