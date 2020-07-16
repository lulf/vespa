// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "persistencethread.h"
#include "splitbitdetector.h"
#include "bucketownershipnotifier.h"
#include "testandsethelper.h"
#include <vespa/storageapi/message/bucketsplitting.h>
#include <vespa/storage/common/bucketoperationlogger.h>
#include <vespa/document/fieldset/fieldsetrepo.h>
#include <vespa/document/update/documentupdate.h>
#include <vespa/vespalib/stllike/hash_map.hpp>
#include <vespa/vespalib/util/exceptions.h>
#include <vespa/vespalib/util/isequencedtaskexecutor.h>

#include <vespa/log/bufferedlogger.h>
LOG_SETUP(".persistence.thread");

namespace storage {

namespace {

class ResultTask : public vespalib::Executor::Task {
public:
    ResultTask() : _result(), _resultHandler(nullptr) { }
    void setResult(spi::Result::UP result) {
        _result = std::move(result);
    }
    void addResultHandler(const spi::ResultHandler * resultHandler) {
        // Only handles a signal handler now,
        // Can be extended if necessary later on
        assert(_resultHandler == nullptr);
        _resultHandler = resultHandler;
    }
    void handle(const spi::Result &result ) {
        if (_resultHandler != nullptr) {
            _resultHandler->handle(result);
        }
    }
protected:
    spi::Result::UP      _result;
private:
    const spi::ResultHandler * _resultHandler;
};

template<class FunctionType>
class LambdaResultTask : public ResultTask {
public:
    LambdaResultTask(FunctionType && func)
        : _func(std::move(func))
    {}
    ~LambdaResultTask() override {}
    void run() override {
        handle(*_result);
        _func(std::move(_result));
    }
private:
    FunctionType _func;
};

template <class FunctionType>
std::unique_ptr<ResultTask>
makeResultTask(FunctionType &&function)
{
    return std::make_unique<LambdaResultTask<std::decay_t<FunctionType>>>
           (std::forward<FunctionType>(function));
}

class ResultTaskOperationDone : public spi::OperationComplete {
public:
    ResultTaskOperationDone(vespalib::ISequencedTaskExecutor & executor, document::BucketId bucketId,
                            std::unique_ptr<ResultTask> task)
        : _executor(executor),
          _task(std::move(task)),
          _executorId(executor.getExecutorId(bucketId.getId()))
    {
    }
    void onComplete(spi::Result::UP result) override {
        _task->setResult(std::move(result));
        _executor.executeTask(_executorId, std::move(_task));
    }
    void addResultHandler(const spi::ResultHandler * resultHandler) override {
        _task->addResultHandler(resultHandler);
    }
private:
    vespalib::ISequencedTaskExecutor             & _executor;
    std::unique_ptr<ResultTask>                    _task;
    vespalib::ISequencedTaskExecutor::ExecutorId   _executorId;
};

}

PersistenceThread::PersistenceThread(vespalib::ISequencedTaskExecutor * sequencedExecutor,
                                     ServiceLayerComponentRegister& compReg,
                                     const config::ConfigUri & configUri,
                                     spi::PersistenceProvider& provider,
                                     FileStorHandler& filestorHandler,
                                     FileStorThreadMetrics& metrics,
                                     uint16_t deviceIndex)
    : _stripeId(filestorHandler.getNextStripeId(deviceIndex)),
      _env(configUri, compReg, filestorHandler, metrics, deviceIndex, provider),
      _sequencedExecutor(sequencedExecutor),
      _spi(provider),
      _processAllHandler(_env, provider),
      _mergeHandler(_spi, _env),
      _bucketOwnershipNotifier(),
      _flushMonitor(),
      _closed(false)
{
    std::ostringstream threadName;
    threadName << "Disk " << _env._partition << " thread " << _stripeId;
    _component = std::make_unique<ServiceLayerComponent>(compReg, threadName.str());
    _bucketOwnershipNotifier = std::make_unique<BucketOwnershipNotifier>(*_component, filestorHandler);
    framework::MilliSecTime maxProcessingTime(60 * 1000);
    framework::MilliSecTime waitTime(1000);
    _thread = _component->startThread(*this, maxProcessingTime, waitTime);
}

PersistenceThread::~PersistenceThread()
{
    LOG(debug, "Shutting down persistence thread. Waiting for current operation to finish.");
    _thread->interrupt();
    LOG(debug, "Waiting for thread to terminate.");
    _thread->join();
    LOG(debug, "Persistence thread done with destruction");
}

spi::Bucket
PersistenceThread::getBucket(const DocumentId& id, const document::Bucket &bucket) const
{
    BucketId docBucket(_env._bucketFactory.getBucketId(id));
    docBucket.setUsedBits(bucket.getBucketId().getUsedBits());
    if (bucket.getBucketId() != docBucket) {
        docBucket = _env._bucketFactory.getBucketId(id);
        throw vespalib::IllegalStateException("Document " + id.toString()
                + " (bucket " + docBucket.toString() + ") does not belong in "
                + "bucket " + bucket.getBucketId().toString() + ".", VESPA_STRLOC);
    }

    return spi::Bucket(bucket, spi::PartitionId(_env._partition));
}

bool
PersistenceThread::tasConditionExists(const api::TestAndSetCommand & cmd) {
    return cmd.getCondition().isPresent();
}

bool
PersistenceThread::tasConditionMatches(const api::TestAndSetCommand & cmd, MessageTracker & tracker,
                                            spi::Context & context, bool missingDocumentImpliesMatch) {
    try {
        TestAndSetHelper helper(*this, cmd, missingDocumentImpliesMatch);

        auto code = helper.retrieveAndMatch(context);
        if (code.failed()) {
            tracker.fail(code.getResult(), code.getMessage());
            return false;
        }
    } catch (const TestAndSetException & e) {
        auto code = e.getCode();
        tracker.fail(code.getResult(), code.getMessage());
        return false;
    }

    return true;
}

MessageTracker::UP
PersistenceThread::handlePut(api::PutCommand& cmd, MessageTracker::UP trackerUP)
{
    MessageTracker & tracker = *trackerUP;
    auto& metrics = _env._metrics.put[cmd.getLoadType()];
    tracker.setMetric(metrics);
    metrics.request_size.addValue(cmd.getApproxByteSize());

    if (tasConditionExists(cmd) && !tasConditionMatches(cmd, tracker, tracker.context())) {
        return trackerUP;
    }

    spi::Bucket bucket = getBucket(cmd.getDocumentId(), cmd.getBucket());
    if (_sequencedExecutor == nullptr) {
        spi::Result response = _spi.put(bucket, spi::Timestamp(cmd.getTimestamp()), std::move(cmd.getDocument()),tracker.context());
        tracker.checkForError(response);
    } else {
        auto task = makeResultTask([tracker = std::move(trackerUP)](spi::Result::UP response) {
            tracker->checkForError(*response);
            tracker->sendReply();
        });
        _spi.putAsync(bucket, spi::Timestamp(cmd.getTimestamp()), std::move(cmd.getDocument()), tracker.context(),
                      std::make_unique<ResultTaskOperationDone>(*_sequencedExecutor, cmd.getBucketId(), std::move(task)));
    }
    return trackerUP;
}

MessageTracker::UP
PersistenceThread::handleRemove(api::RemoveCommand& cmd, MessageTracker::UP trackerUP)
{
    MessageTracker & tracker = *trackerUP;
    auto& metrics = _env._metrics.remove[cmd.getLoadType()];
    tracker.setMetric(metrics);
    metrics.request_size.addValue(cmd.getApproxByteSize());

    if (tasConditionExists(cmd) && !tasConditionMatches(cmd, tracker, tracker.context())) {
        return trackerUP;
    }

    spi::Bucket bucket = getBucket(cmd.getDocumentId(), cmd.getBucket());
    if (_sequencedExecutor == nullptr) {
        spi::RemoveResult response = _spi.removeIfFound(bucket, spi::Timestamp(cmd.getTimestamp()), cmd.getDocumentId(),tracker.context());
        if (tracker.checkForError(response)) {
            tracker.setReply(std::make_shared<api::RemoveReply>(cmd, response.wasFound() ? cmd.getTimestamp() : 0));
        }
        if (!response.wasFound()) {
            metrics.notFound.inc();
        }
    } else {
        // Note that the &cmd capture is OK since its lifetime is guaranteed by the tracker
        auto task = makeResultTask([&metrics, &cmd, tracker = std::move(trackerUP)](spi::Result::UP responseUP) {
            const spi::RemoveResult & response = dynamic_cast<const spi::RemoveResult &>(*responseUP);
            if (tracker->checkForError(response)) {
                tracker->setReply(std::make_shared<api::RemoveReply>(cmd, response.wasFound() ? cmd.getTimestamp() : 0));
            }
            if (!response.wasFound()) {
                metrics.notFound.inc();
            }
            tracker->sendReply();
        });
        _spi.removeIfFoundAsync(bucket, spi::Timestamp(cmd.getTimestamp()), cmd.getDocumentId(), tracker.context(),
                                std::make_unique<ResultTaskOperationDone>(*_sequencedExecutor, cmd.getBucketId(), std::move(task)));
    }
    return trackerUP;
}

MessageTracker::UP
PersistenceThread::handleUpdate(api::UpdateCommand& cmd, MessageTracker::UP trackerUP)
{
    MessageTracker & tracker = *trackerUP;
    auto& metrics = _env._metrics.update[cmd.getLoadType()];
    tracker.setMetric(metrics);
    metrics.request_size.addValue(cmd.getApproxByteSize());

    if (tasConditionExists(cmd) && !tasConditionMatches(cmd, tracker, tracker.context(), cmd.getUpdate()->getCreateIfNonExistent())) {
        return trackerUP;
    }

    spi::Bucket bucket = getBucket(cmd.getDocumentId(), cmd.getBucket());
    if (_sequencedExecutor == nullptr) {
        spi::UpdateResult response = _spi.update(bucket, spi::Timestamp(cmd.getTimestamp()), std::move(cmd.getUpdate()),tracker.context());
        if (tracker.checkForError(response)) {
            auto reply = std::make_shared<api::UpdateReply>(cmd);
            reply->setOldTimestamp(response.getExistingTimestamp());
            tracker.setReply(std::move(reply));
        }
    } else {
        // Note that the &cmd capture is OK since its lifetime is guaranteed by the tracker
        auto task = makeResultTask([&cmd, tracker = std::move(trackerUP)](spi::Result::UP responseUP) {
            const spi::UpdateResult & response = dynamic_cast<const spi::UpdateResult &>(*responseUP);
            if (tracker->checkForError(response)) {
                auto reply = std::make_shared<api::UpdateReply>(cmd);
                reply->setOldTimestamp(response.getExistingTimestamp());
                tracker->setReply(std::move(reply));
            }
            tracker->sendReply();
        });
        _spi.updateAsync(bucket, spi::Timestamp(cmd.getTimestamp()), std::move(cmd.getUpdate()), tracker.context(),
                         std::make_unique<ResultTaskOperationDone>(*_sequencedExecutor, cmd.getBucketId(), std::move(task)));
    }
    return trackerUP;
}

namespace {

spi::ReadConsistency api_read_consistency_to_spi(api::InternalReadConsistency consistency) noexcept {
    switch (consistency) {
    case api::InternalReadConsistency::Strong: return spi::ReadConsistency::STRONG;
    case api::InternalReadConsistency::Weak:   return spi::ReadConsistency::WEAK;
    default: abort();
    }
}

}

MessageTracker::UP
PersistenceThread::handleGet(api::GetCommand& cmd, MessageTracker::UP tracker)
{
    auto& metrics = _env._metrics.get[cmd.getLoadType()];
    tracker->setMetric(metrics);
    metrics.request_size.addValue(cmd.getApproxByteSize());

    document::FieldSet::UP fieldSet = document::FieldSetRepo::parse(*_env._component.getTypeRepo(), cmd.getFieldSet());
    tracker->context().setReadConsistency(api_read_consistency_to_spi(cmd.internal_read_consistency()));
    spi::GetResult result =
        _spi.get(getBucket(cmd.getDocumentId(), cmd.getBucket()), *fieldSet, cmd.getDocumentId(), tracker->context());

    if (tracker->checkForError(result)) {
        if (!result.hasDocument()) {
            _env._metrics.get[cmd.getLoadType()].notFound.inc();
        }
        tracker->setReply(std::make_shared<api::GetReply>(cmd, result.getDocumentPtr(), result.getTimestamp(),
                                                          false, result.is_tombstone()));
    }

    return tracker;
}

MessageTracker::UP
PersistenceThread::handleRevert(api::RevertCommand& cmd, MessageTracker::UP tracker)
{
    tracker->setMetric(_env._metrics.revert[cmd.getLoadType()]);
    spi::Bucket b = spi::Bucket(cmd.getBucket(), spi::PartitionId(_env._partition));
    const std::vector<api::Timestamp> & tokens = cmd.getRevertTokens();
    for (const api::Timestamp & token : tokens) {
        spi::Result result = _spi.removeEntry(b, spi::Timestamp(token), tracker->context());
    }
    return tracker;
}

MessageTracker::UP
PersistenceThread::handleCreateBucket(api::CreateBucketCommand& cmd, MessageTracker::UP tracker)
{
    tracker->setMetric(_env._metrics.createBuckets);
    LOG(debug, "CreateBucket(%s)", cmd.getBucketId().toString().c_str());
    if (_env._fileStorHandler.isMerging(cmd.getBucket())) {
        LOG(warning, "Bucket %s was merging at create time. Unexpected.", cmd.getBucketId().toString().c_str());
        DUMP_LOGGED_BUCKET_OPERATIONS(cmd.getBucketId());
    }
    spi::Bucket spiBucket(cmd.getBucket(), spi::PartitionId(_env._partition));
    _spi.createBucket(spiBucket, tracker->context());
    if (cmd.getActive()) {
        _spi.setActiveState(spiBucket, spi::BucketInfo::ACTIVE);
    }
    return tracker;
}

namespace {

bool bucketStatesAreSemanticallyEqual(const api::BucketInfo& a, const api::BucketInfo& b) {
    // Don't check document sizes, as background moving of documents in Proton
    // may trigger a change in size without any mutations taking place. This will
    // only take place when a document being moved was fed _prior_ to the change
    // where Proton starts reporting actual document sizes, and will eventually
    // converge to a stable value. But for now, ignore it to prevent false positive
    // error logs and non-deleted buckets.
    return ((a.getChecksum() == b.getChecksum()) && (a.getDocumentCount() == b.getDocumentCount()));
}

}

bool
PersistenceThread::checkProviderBucketInfoMatches(const spi::Bucket& bucket, const api::BucketInfo& info) const
{
    spi::BucketInfoResult result(_spi.getBucketInfo(bucket));
    if (result.hasError()) {
        LOG(error, "getBucketInfo(%s) failed before deleting bucket; got error '%s'",
            bucket.toString().c_str(), result.getErrorMessage().c_str());
        return false;
    }
    api::BucketInfo providerInfo(_env.convertBucketInfo(result.getBucketInfo()));
    // Don't check meta fields or active/ready fields since these are not
    // that important and ready may change under the hood in a race with
    // getModifiedBuckets(). If bucket is empty it means it has already
    // been deleted by a racing split/join.
    if (!bucketStatesAreSemanticallyEqual(info, providerInfo) && !providerInfo.empty()) {
        LOG(error,
            "Service layer bucket database and provider out of sync before "
            "deleting bucket %s! Service layer db had %s while provider says "
            "bucket has %s. Deletion has been rejected to ensure data is not "
            "lost, but bucket may remain out of sync until service has been "
            "restarted.",
            bucket.toString().c_str(), info.toString().c_str(), providerInfo.toString().c_str());
        return false;
    }
    return true;
}

MessageTracker::UP
PersistenceThread::handleDeleteBucket(api::DeleteBucketCommand& cmd, MessageTracker::UP tracker)
{
    tracker->setMetric(_env._metrics.deleteBuckets);
    LOG(debug, "DeletingBucket(%s)", cmd.getBucketId().toString().c_str());
    LOG_BUCKET_OPERATION(cmd.getBucketId(), "deleteBucket()");
    if (_env._fileStorHandler.isMerging(cmd.getBucket())) {
        _env._fileStorHandler.clearMergeStatus(cmd.getBucket(),
                api::ReturnCode(api::ReturnCode::ABORTED, "Bucket was deleted during the merge"));
    }
    spi::Bucket bucket(cmd.getBucket(), spi::PartitionId(_env._partition));
    if (!checkProviderBucketInfoMatches(bucket, cmd.getBucketInfo())) {
           return tracker;
    }
    _spi.deleteBucket(bucket, tracker->context());
    StorBucketDatabase& db(_env.getBucketDatabase(cmd.getBucket().getBucketSpace()));
    {
        StorBucketDatabase::WrappedEntry entry(db.get(cmd.getBucketId(), "FileStorThread::onDeleteBucket"));
        if (entry.exist() && entry->getMetaCount() > 0) {
            LOG(debug, "onDeleteBucket(%s): Bucket DB entry existed. Likely "
                       "active operation when delete bucket was queued. "
                       "Updating bucket database to keep it in sync with file. "
                       "Cannot delete bucket from bucket database at this "
                       "point, as it can have been intentionally recreated "
                       "after delete bucket had been sent",
                cmd.getBucketId().toString().c_str());
            api::BucketInfo info(0, 0, 0);
            // Only set document counts/size; retain ready/active state.
            info.setReady(entry->getBucketInfo().isReady());
            info.setActive(entry->getBucketInfo().isActive());

            entry->setBucketInfo(info);
            entry.write();
        }
    }
    return tracker;
}

MessageTracker::UP
PersistenceThread::handleGetIter(GetIterCommand& cmd, MessageTracker::UP tracker)
{
    tracker->setMetric(_env._metrics.visit[cmd.getLoadType()]);
    spi::IterateResult result(_spi.iterate(cmd.getIteratorId(), cmd.getMaxByteSize(), tracker->context()));
    if (tracker->checkForError(result)) {
        auto reply = std::make_shared<GetIterReply>(cmd);
        reply->getEntries() = result.steal_entries();
        _env._metrics.visit[cmd.getLoadType()].
            documentsPerIterate.addValue(reply->getEntries().size());
        if (result.isCompleted()) {
            reply->setCompleted();
        }
        tracker->setReply(reply);
    }
    return tracker;
}

MessageTracker::UP
PersistenceThread::handleReadBucketList(ReadBucketList& cmd, MessageTracker::UP tracker)
{
    tracker->setMetric(_env._metrics.readBucketList);

    spi::BucketIdListResult result(_spi.listBuckets(cmd.getBucketSpace(), cmd.getPartition()));
    if (tracker->checkForError(result)) {
        auto reply = std::make_shared<ReadBucketListReply>(cmd);
        result.getList().swap(reply->getBuckets());
        tracker->setReply(reply);
    }

    return tracker;
}

MessageTracker::UP
PersistenceThread::handleReadBucketInfo(ReadBucketInfo& cmd, MessageTracker::UP tracker)
{
    tracker->setMetric(_env._metrics.readBucketInfo);
    _env.updateBucketDatabase(cmd.getBucket(), _env.getBucketInfo(cmd.getBucket()));
    return tracker;
}

MessageTracker::UP
PersistenceThread::handleCreateIterator(CreateIteratorCommand& cmd, MessageTracker::UP tracker)
{
    tracker->setMetric(_env._metrics.createIterator);
    document::FieldSet::UP fieldSet = document::FieldSetRepo::parse(*_env._component.getTypeRepo(), cmd.getFields());
    tracker->context().setReadConsistency(cmd.getReadConsistency());
    spi::CreateIteratorResult result(_spi.createIterator(
        spi::Bucket(cmd.getBucket(), spi::PartitionId(_env._partition)),
        *fieldSet, cmd.getSelection(), cmd.getIncludedVersions(), tracker->context()));
    if (tracker->checkForError(result)) {
        tracker->setReply(std::make_shared<CreateIteratorReply>(cmd, spi::IteratorId(result.getIteratorId())));
    }
    return tracker;
}

MessageTracker::UP
PersistenceThread::handleSplitBucket(api::SplitBucketCommand& cmd, MessageTracker::UP tracker)
{
    tracker->setMetric(_env._metrics.splitBuckets);
    NotificationGuard notifyGuard(*_bucketOwnershipNotifier);

    // Calculate the various bucket ids involved.
    if (cmd.getBucketId().getUsedBits() >= 58) {
        tracker->fail(api::ReturnCode::ILLEGAL_PARAMETERS,
                "Can't split anymore since maximum split bits is already reached");
        return tracker;
    }
    if (cmd.getMaxSplitBits() <= cmd.getBucketId().getUsedBits()) {
        tracker->fail(api::ReturnCode::ILLEGAL_PARAMETERS,
                     "Max lit bits must be set higher than the number of bits used in the bucket to split");
        return tracker;
    }

    spi::Bucket spiBucket(cmd.getBucket(), spi::PartitionId(_env._partition));
    SplitBitDetector::Result targetInfo;
    if (_env._config.enableMultibitSplitOptimalization) {
        targetInfo = SplitBitDetector::detectSplit(_spi, spiBucket, cmd.getMaxSplitBits(),
                                                   tracker->context(), cmd.getMinDocCount(), cmd.getMinByteSize());
    }
    if (targetInfo.empty() || !_env._config.enableMultibitSplitOptimalization) {
        document::BucketId src(cmd.getBucketId());
        document::BucketId target1(src.getUsedBits() + 1, src.getId());
        document::BucketId target2(src.getUsedBits() + 1, src.getId() | (uint64_t(1) << src.getUsedBits()));
        targetInfo = SplitBitDetector::Result(target1, target2, false);
    }
    if (targetInfo.failed()) {
        tracker->fail(api::ReturnCode::INTERNAL_FAILURE, targetInfo.getReason());
        return tracker;
    }
        // If we get here, we're splitting data in two.
        // (Possibly in special case where a target will be unused)
    assert(targetInfo.success());
    document::Bucket target1(spiBucket.getBucketSpace(), targetInfo.getTarget1());
    document::Bucket target2(spiBucket.getBucketSpace(), targetInfo.getTarget2());

    LOG(debug, "split(%s -> %s, %s)", cmd.getBucketId().toString().c_str(),
        target1.getBucketId().toString().c_str(), target2.getBucketId().toString().c_str());

    PersistenceUtil::LockResult lock1(_env.lockAndGetDisk(target1));
    PersistenceUtil::LockResult lock2(_env.lockAndGetDisk(target2));

#ifdef ENABLE_BUCKET_OPERATION_LOGGING
    {
        vespalib::string desc(
                vespalib::make_string(
                        "split(%s -> %s, %s)",
                        cmd.getBucketId().toString().c_str(),
                        target1.getBucketId().toString().c_str(),
                        target2.getBucketId().toString().c_str()));
        LOG_BUCKET_OPERATION(cmd.getBucketId(), desc);
        LOG_BUCKET_OPERATION(target1.getBucketId(), desc);
        if (target2.getRawId() != 0) {
            LOG_BUCKET_OPERATION(target2.getBucketId(), desc);
        }
    }
#endif
    spi::Result result = _spi.split(spiBucket, spi::Bucket(target1, spi::PartitionId(lock1.disk)),
                                    spi::Bucket(target2, spi::PartitionId(lock2.disk)), tracker->context());
    if (result.hasError()) {
        tracker->fail(PersistenceUtil::convertErrorCode(result), result.getErrorMessage());
        return tracker;
    }
        // After split we need to take all bucket db locks to update them.
        // Ensure to take them in rising order.
    StorBucketDatabase::WrappedEntry sourceEntry(_env.getBucketDatabase(spiBucket.getBucket().getBucketSpace()).get(
            cmd.getBucketId(), "PersistenceThread::handleSplitBucket-source"));
    auto reply = std::make_shared<api::SplitBucketReply>(cmd);
    api::SplitBucketReply & splitReply = *reply;
    tracker->setReply(std::move(reply));

    typedef std::pair<StorBucketDatabase::WrappedEntry, FileStorHandler::RemapInfo> TargetInfo;
    std::vector<TargetInfo> targets;
    for (uint32_t i = 0; i < 2; i++) {
        const document::Bucket &target(i == 0 ? target1 : target2);
        uint16_t disk(i == 0 ? lock1.disk : lock2.disk);
        assert(target.getBucketId().getRawId() != 0);
        targets.emplace_back(_env.getBucketDatabase(target.getBucketSpace()).get(
                    target.getBucketId(), "PersistenceThread::handleSplitBucket - Target",
                    StorBucketDatabase::CREATE_IF_NONEXISTING),
                FileStorHandler::RemapInfo(target, disk));
        targets.back().first->setBucketInfo(_env.getBucketInfo(target, disk));
        targets.back().first->disk = disk;
    }
    if (LOG_WOULD_LOG(spam)) {
        api::BucketInfo targ1(targets[0].first->getBucketInfo());
        api::BucketInfo targ2(targets[1].first->getBucketInfo());
        LOG(spam, "split(%s - %u -> %s - %u, %s - %u)",
            cmd.getBucketId().toString().c_str(),
            targ1.getMetaCount() + targ2.getMetaCount(),
            target1.getBucketId().toString().c_str(),
            targ1.getMetaCount(),
            target2.getBucketId().toString().c_str(),
            targ2.getMetaCount());
    }
    FileStorHandler::RemapInfo source(cmd.getBucket(), _env._partition);
    _env._fileStorHandler.remapQueueAfterSplit(source, targets[0].second, targets[1].second);
    bool ownershipChanged(!_bucketOwnershipNotifier->distributorOwns(cmd.getSourceIndex(), cmd.getBucket()));
    // Now release all the bucketdb locks.
    for (uint32_t i = 0; i < targets.size(); i++) {
        if (ownershipChanged) {
            notifyGuard.notifyAlways(targets[i].second.bucket, targets[i].first->getBucketInfo());
        }
        // The entries vector has the source bucket in element zero, so indexing
        // that with i+1
        if (targets[i].second.foundInQueue || targets[i].first->getMetaCount() > 0) {
            if (targets[i].first->getMetaCount() == 0) {
                // Fake that the bucket has content so it is not deleted.
                targets[i].first->info.setMetaCount(1);
                // Must make sure target bucket exists when we have pending ops
                // to an empty target bucket, since the provider will have
                // implicitly erased it by this point.
                spi::Bucket createTarget(spi::Bucket(targets[i].second.bucket,
                                                     spi::PartitionId(targets[i].second.diskIndex)));
                LOG(debug, "Split target %s was empty, but re-creating it since there are remapped operations queued to it",
                    createTarget.toString().c_str());
                _spi.createBucket(createTarget, tracker->context());
            }
            splitReply.getSplitInfo().emplace_back(targets[i].second.bucket.getBucketId(),
                                                   targets[i].first->getBucketInfo());
            targets[i].first.write();
        } else {
            targets[i].first.remove();
        }
    }
    if (sourceEntry.exist()) {
        if (ownershipChanged) {
            notifyGuard.notifyAlways(cmd.getBucket(), sourceEntry->getBucketInfo());
        }
        // Delete the old entry.
        sourceEntry.remove();
    }
    return tracker;
}

bool
PersistenceThread::validateJoinCommand(const api::JoinBucketsCommand& cmd, MessageTracker& tracker)
{
    if (cmd.getSourceBuckets().size() != 2) {
        tracker.fail(ReturnCode::ILLEGAL_PARAMETERS,
                     "Join needs exactly two buckets to be joined together" + cmd.getBucketId().toString());
        return false;
    }
    // Verify that source and target buckets look sane.
    for (uint32_t i = 0; i < cmd.getSourceBuckets().size(); i++) {
        if (cmd.getSourceBuckets()[i] == cmd.getBucketId()) {
            tracker.fail(ReturnCode::ILLEGAL_PARAMETERS,
                         "Join had both source and target bucket " + cmd.getBucketId().toString());
            return false;
        }
        if (!cmd.getBucketId().contains(cmd.getSourceBuckets()[i])) {
            tracker.fail(ReturnCode::ILLEGAL_PARAMETERS,
                         "Source bucket " + cmd.getSourceBuckets()[i].toString()
                         + " is not contained in target " + cmd.getBucketId().toString());
            return false;
        }
    }
    return true;
}

MessageTracker::UP
PersistenceThread::handleJoinBuckets(api::JoinBucketsCommand& cmd, MessageTracker::UP tracker)
{
    tracker->setMetric(_env._metrics.joinBuckets);
    if (!validateJoinCommand(cmd, *tracker)) {
        return tracker;
    }
    document::Bucket destBucket = cmd.getBucket();
    // To avoid a potential deadlock all operations locking multiple
    // buckets must lock their buckets in the same order (sort order of
    // bucket id, lowest countbits, lowest location first).
    // Sort buckets to join in order to ensure we lock in correct order
    std::sort(cmd.getSourceBuckets().begin(), cmd.getSourceBuckets().end());
    {
        // Create empty bucket for target.
        StorBucketDatabase::WrappedEntry entry =
        _env.getBucketDatabase(destBucket.getBucketSpace()).get(destBucket.getBucketId(), "join",
                                                                StorBucketDatabase::CREATE_IF_NONEXISTING);
        entry->disk = _env._partition;
        entry.write();
    }

    document::Bucket firstBucket(destBucket.getBucketSpace(), cmd.getSourceBuckets()[0]);
    document::Bucket secondBucket(destBucket.getBucketSpace(), cmd.getSourceBuckets()[1]);

    PersistenceUtil::LockResult lock1(_env.lockAndGetDisk(firstBucket));
    PersistenceUtil::LockResult lock2;
    if (firstBucket != secondBucket) {
        lock2 = _env.lockAndGetDisk(secondBucket);
    }

#ifdef ENABLE_BUCKET_OPERATION_LOGGING
    {
        vespalib::string desc(
                vespalib::make_string(
                        "join(%s, %s -> %s)",
                        firstBucket.getBucketId().toString().c_str(),
                        secondBucket.getBucketId().toString().c_str(),
                        cmd.getBucketId().toString().c_str()));
        LOG_BUCKET_OPERATION(cmd.getBucketId(), desc);
        LOG_BUCKET_OPERATION(firstBucket.getBucketId(), desc);
        if (firstBucket != secondBucket) {
            LOG_BUCKET_OPERATION(secondBucket.getBucketId(), desc);
        }
    }
#endif
    spi::Result result =
        _spi.join(spi::Bucket(firstBucket, spi::PartitionId(lock1.disk)),
                  spi::Bucket(secondBucket, spi::PartitionId(lock2.disk)),
                  spi::Bucket(destBucket, spi::PartitionId(_env._partition)),
                  tracker->context());
    if (!tracker->checkForError(result)) {
        return tracker;
    }
    uint64_t lastModified = 0;
    for (uint32_t i = 0; i < cmd.getSourceBuckets().size(); i++) {
        document::Bucket srcBucket(destBucket.getBucketSpace(), cmd.getSourceBuckets()[i]);
        uint16_t disk = (i == 0) ? lock1.disk : lock2.disk;
        FileStorHandler::RemapInfo target(cmd.getBucket(), _env._partition);
        _env._fileStorHandler.remapQueueAfterJoin(FileStorHandler::RemapInfo(srcBucket, disk), target);
        // Remove source from bucket db.
        StorBucketDatabase::WrappedEntry entry =
                _env.getBucketDatabase(srcBucket.getBucketSpace()).get(srcBucket.getBucketId(), "join-remove-source");
        if (entry.exist()) {
            lastModified = std::max(lastModified, entry->info.getLastModified());
            entry.remove();
        }
    }
    {
        StorBucketDatabase::WrappedEntry entry =
            _env.getBucketDatabase(destBucket.getBucketSpace()).get(destBucket.getBucketId(), "join",
                                                                    StorBucketDatabase::CREATE_IF_NONEXISTING);
        if (entry->info.getLastModified() == 0) {
            entry->info.setLastModified(std::max(lastModified, entry->info.getLastModified()));
        }
        entry.write();
    }
    return tracker;
}

MessageTracker::UP
PersistenceThread::handleSetBucketState(api::SetBucketStateCommand& cmd, MessageTracker::UP tracker)
{
    tracker->setMetric(_env._metrics.setBucketStates);
    NotificationGuard notifyGuard(*_bucketOwnershipNotifier);

    LOG(debug, "handleSetBucketState(): %s", cmd.toString().c_str());
    spi::Bucket bucket(cmd.getBucket(), spi::PartitionId(_env._partition));
    bool shouldBeActive(cmd.getState() == api::SetBucketStateCommand::ACTIVE);
    spi::BucketInfo::ActiveState newState(shouldBeActive ? spi::BucketInfo::ACTIVE : spi::BucketInfo::NOT_ACTIVE);

    spi::Result result(_spi.setActiveState(bucket, newState));
    if (tracker->checkForError(result)) {
        StorBucketDatabase::WrappedEntry
                entry = _env.getBucketDatabase(bucket.getBucket().getBucketSpace()).get(cmd.getBucketId(), "handleSetBucketState");
        if (entry.exist()) {
            entry->info.setActive(newState == spi::BucketInfo::ACTIVE);
            notifyGuard.notifyIfOwnershipChanged(cmd.getBucket(), cmd.getSourceIndex(), entry->info);
            entry.write();
        } else {
            LOG(warning, "Got OK setCurrentState result from provider for %s, "
                "but bucket has disappeared from service layer database",
                cmd.getBucketId().toString().c_str());
        }

        tracker->setReply(std::make_shared<api::SetBucketStateReply>(cmd));
    }

    return tracker;
}

MessageTracker::UP
PersistenceThread::handleInternalBucketJoin(InternalBucketJoinCommand& cmd, MessageTracker::UP tracker)
{
    tracker->setMetric(_env._metrics.internalJoin);
    document::Bucket destBucket = cmd.getBucket();
    {
        // Create empty bucket for target.
        StorBucketDatabase::WrappedEntry entry =
            _env.getBucketDatabase(destBucket.getBucketSpace()).get(
                    destBucket.getBucketId(), "join", StorBucketDatabase::CREATE_IF_NONEXISTING);

        entry->disk = _env._partition;
        entry.write();
    }
    spi::Result result =
        _spi.join(spi::Bucket(destBucket, spi::PartitionId(cmd.getDiskOfInstanceToJoin())),
                  spi::Bucket(destBucket, spi::PartitionId(cmd.getDiskOfInstanceToJoin())),
                  spi::Bucket(destBucket, spi::PartitionId(cmd.getDiskOfInstanceToKeep())),
                  tracker->context());
    if (tracker->checkForError(result)) {
        tracker->setReply(std::make_shared<InternalBucketJoinReply>(cmd, _env.getBucketInfo(cmd.getBucket())));
    }
    return tracker;
}

MessageTracker::UP
PersistenceThread::handleRecheckBucketInfo(RecheckBucketInfoCommand& cmd, MessageTracker::UP tracker)
{
    tracker->setMetric(_env._metrics.recheckBucketInfo);
    document::Bucket bucket(cmd.getBucket());
    api::BucketInfo info(_env.getBucketInfo(bucket));
    NotificationGuard notifyGuard(*_bucketOwnershipNotifier);
    {
        // Update bucket database
        StorBucketDatabase::WrappedEntry entry(
                _component->getBucketDatabase(bucket.getBucketSpace()).get(bucket.getBucketId(), "handleRecheckBucketInfo"));

        if (entry.exist()) {
            api::BucketInfo prevInfo(entry->getBucketInfo());

            if (!(prevInfo == info)) {
                notifyGuard.notifyAlways(bucket, info);
                entry->info = info;
                entry.write();
            }
        }
        // else: there is a race condition where concurrent execution of
        // DeleteBucket in the FileStorManager and this function can cause it
        // to look like the provider has a bucket we do not know about, simply
        // because this function was executed before the actual
        // DeleteBucketCommand in the persistence thread (see ticket 6143025).
    }
    return tracker;
}

MessageTracker::UP
PersistenceThread::handleCommandSplitByType(api::StorageCommand& msg, MessageTracker::UP tracker)
{
    switch (msg.getType().getId()) {
    case api::MessageType::GET_ID:
        return handleGet(static_cast<api::GetCommand&>(msg), std::move(tracker));
    case api::MessageType::PUT_ID:
        return handlePut(static_cast<api::PutCommand&>(msg), std::move(tracker));
    case api::MessageType::REMOVE_ID:
        return handleRemove(static_cast<api::RemoveCommand&>(msg), std::move(tracker));
    case api::MessageType::UPDATE_ID:
        return handleUpdate(static_cast<api::UpdateCommand&>(msg), std::move(tracker));
    case api::MessageType::REVERT_ID:
        return handleRevert(static_cast<api::RevertCommand&>(msg), std::move(tracker));
    case api::MessageType::CREATEBUCKET_ID:
        return handleCreateBucket(static_cast<api::CreateBucketCommand&>(msg), std::move(tracker));
    case api::MessageType::DELETEBUCKET_ID:
        return handleDeleteBucket(static_cast<api::DeleteBucketCommand&>(msg), std::move(tracker));
    case api::MessageType::JOINBUCKETS_ID:
        return handleJoinBuckets(static_cast<api::JoinBucketsCommand&>(msg), std::move(tracker));
    case api::MessageType::SPLITBUCKET_ID:
        return handleSplitBucket(static_cast<api::SplitBucketCommand&>(msg), std::move(tracker));
       // Depends on iterators
    case api::MessageType::STATBUCKET_ID:
        return _processAllHandler.handleStatBucket(static_cast<api::StatBucketCommand&>(msg), std::move(tracker));
    case api::MessageType::REMOVELOCATION_ID:
        return _processAllHandler.handleRemoveLocation(static_cast<api::RemoveLocationCommand&>(msg), std::move(tracker));
    case api::MessageType::MERGEBUCKET_ID:
        return _mergeHandler.handleMergeBucket(static_cast<api::MergeBucketCommand&>(msg), std::move(tracker));
    case api::MessageType::GETBUCKETDIFF_ID:
        return _mergeHandler.handleGetBucketDiff(static_cast<api::GetBucketDiffCommand&>(msg), std::move(tracker));
    case api::MessageType::APPLYBUCKETDIFF_ID:
        return _mergeHandler.handleApplyBucketDiff(static_cast<api::ApplyBucketDiffCommand&>(msg), std::move(tracker));
    case api::MessageType::SETBUCKETSTATE_ID:
        return handleSetBucketState(static_cast<api::SetBucketStateCommand&>(msg), std::move(tracker));
    case api::MessageType::INTERNAL_ID:
        switch(static_cast<api::InternalCommand&>(msg).getType()) {
        case GetIterCommand::ID:
            return handleGetIter(static_cast<GetIterCommand&>(msg), std::move(tracker));
        case CreateIteratorCommand::ID:
            return handleCreateIterator(static_cast<CreateIteratorCommand&>(msg), std::move(tracker));
        case ReadBucketList::ID:
            return handleReadBucketList(static_cast<ReadBucketList&>(msg), std::move(tracker));
        case ReadBucketInfo::ID:
            return handleReadBucketInfo(static_cast<ReadBucketInfo&>(msg), std::move(tracker));
        case InternalBucketJoinCommand::ID:
            return handleInternalBucketJoin(static_cast<InternalBucketJoinCommand&>(msg), std::move(tracker));
        case RecheckBucketInfoCommand::ID:
            return handleRecheckBucketInfo(static_cast<RecheckBucketInfoCommand&>(msg), std::move(tracker));
        default:
            LOG(warning, "Persistence thread received unhandled internal command %s", msg.toString().c_str());
            break;
        }
    default:
        break;
    }
    return MessageTracker::UP();
}

void
PersistenceThread::handleReply(api::StorageReply& reply)
{
    switch (reply.getType().getId()) {
    case api::MessageType::GETBUCKETDIFF_REPLY_ID:
        _mergeHandler.handleGetBucketDiffReply(static_cast<api::GetBucketDiffReply&>(reply), _env._fileStorHandler);
        break;
    case api::MessageType::APPLYBUCKETDIFF_REPLY_ID:
        _mergeHandler.handleApplyBucketDiffReply(static_cast<api::ApplyBucketDiffReply&>(reply), _env._fileStorHandler);
        break;
    default:
        break;
    }
}

MessageTracker::UP
PersistenceThread::processMessage(api::StorageMessage& msg, MessageTracker::UP tracker)
{
    MBUS_TRACE(msg.getTrace(), 5, "PersistenceThread: Processing message in persistence layer");

    _env._metrics.operations.inc();
    if (msg.getType().isReply()) {
        try{
            LOG(debug, "Handling reply: %s", msg.toString().c_str());
            LOG(spam, "Message content: %s", msg.toString(true).c_str());
            handleReply(static_cast<api::StorageReply&>(msg));
        } catch (std::exception& e) {
            // It's a reply, so nothing we can do.
            LOG(debug, "Caught exception for %s: %s", msg.toString().c_str(), e.what());
        }
    } else {
        auto & initiatingCommand = static_cast<api::StorageCommand&>(msg);
        try {
            LOG(debug, "Handling command: %s", msg.toString().c_str());
            LOG(spam, "Message content: %s", msg.toString(true).c_str());
            return handleCommandSplitByType(initiatingCommand, std::move(tracker));
        } catch (std::exception& e) {
            LOG(debug, "Caught exception for %s: %s", msg.toString().c_str(), e.what());
            api::StorageReply::SP reply(initiatingCommand.makeReply());
            reply->setResult(api::ReturnCode(api::ReturnCode::INTERNAL_FAILURE, e.what()));
            _env._fileStorHandler.sendReply(reply);
        }
    }

    return tracker;
}

void
PersistenceThread::processLockedMessage(FileStorHandler::LockedMessage lock) {
    LOG(debug, "Partition %d, nodeIndex %d, ptr=%p", _env._partition, _env._nodeIndex, lock.second.get());
    api::StorageMessage & msg(*lock.second);

    // Important: we _copy_ the message shared_ptr instead of moving to ensure that `msg` remains
    // valid even if the tracker is destroyed by an exception in processMessage().
    auto tracker = std::make_unique<MessageTracker>(_env, _env._fileStorHandler, std::move(lock.first), lock.second);
    tracker = processMessage(msg, std::move(tracker));
    if (tracker) {
        tracker->sendReply();
    }
}

void
PersistenceThread::run(framework::ThreadHandle& thread)
{
    LOG(debug, "Started persistence thread");

    while (!thread.interrupted() && !_env._fileStorHandler.closed(_env._partition)) {
        thread.registerTick();

        FileStorHandler::LockedMessage lock(_env._fileStorHandler.getNextMessage(_env._partition, _stripeId));

        if (lock.first) {
            processLockedMessage(std::move(lock));
        }

        vespalib::MonitorGuard flushMonitorGuard(_flushMonitor);
        flushMonitorGuard.broadcast();
    }
    LOG(debug, "Closing down persistence thread");
    vespalib::MonitorGuard flushMonitorGuard(_flushMonitor);
    _closed = true;
    flushMonitorGuard.broadcast();
}

void
PersistenceThread::flush()
{
    vespalib::MonitorGuard flushMonitorGuard(_flushMonitor);
    if (!_closed) {
        flushMonitorGuard.wait();
    }
}

} // storage
