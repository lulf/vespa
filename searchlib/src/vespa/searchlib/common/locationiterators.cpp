// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "locationiterators.h"
#include <vespa/searchlib/bitcompression/compression.h>
#include <vespa/searchlib/attribute/attributevector.h>

#include <vespa/log/log.h>
LOG_SETUP(".searchlib.common.locationiterators");

using namespace search::common;

class FastS_2DZLocationIterator : public search::queryeval::SearchIterator
{
private:
    const unsigned int _numDocs;
    const bool         _strict;
    const Location &   _location;
    std::vector<search::AttributeVector::largeint_t> _pos;

    void doSeek(uint32_t docId) override;
    void doUnpack(uint32_t docId) override;
public:
    FastS_2DZLocationIterator(unsigned int numDocs, bool strict, const Location & location);

    ~FastS_2DZLocationIterator() override;
};


FastS_2DZLocationIterator::
FastS_2DZLocationIterator(unsigned int numDocs,
                          bool strict,
                          const Location & location)
    : SearchIterator(),
      _numDocs(numDocs),
      _strict(strict),
      _location(location),
      _pos()
{
    _pos.resize(1);  //Need at least 1 entry as the singlevalue attributes does not honour given size.
};


FastS_2DZLocationIterator::~FastS_2DZLocationIterator() = default;


void
FastS_2DZLocationIterator::doSeek(uint32_t docId)
{
    LOG(debug, "FastS_2DZLocationIterator: seek(%u) with numDocs=%u endId=%u",
        docId, _numDocs, getEndId());
    if (__builtin_expect(docId >= _numDocs, false)) {
        setAtEnd();
        return;
    }

    const Location &location = _location;
    std::vector<search::AttributeVector::largeint_t> &pos = _pos;

    for (;;) {
        uint32_t numValues =
            location.getVec()->get(docId, &pos[0], pos.size());
        if (numValues > pos.size()) {
            pos.resize(numValues);
            numValues = location.getVec()->get(docId, &pos[0], pos.size());
        }
        for (uint32_t i = 0; i < numValues; i++) {
            int64_t docxy(pos[i]);
            if (location.inside_limit(docxy)) {
                setDocId(docId);
                return;
            }
        }

        if (__builtin_expect(docId + 1 >= _numDocs, false)) {
            setAtEnd();
            return;
        }

        if (!_strict) {
            return;
        }
        docId++;
    }
}


void
FastS_2DZLocationIterator::doUnpack(uint32_t docId)
{
    (void) docId;
}


std::unique_ptr<search::queryeval::SearchIterator>
FastS_AllocLocationIterator(unsigned int numDocs, bool strict, const Location & location)
{
    return std::make_unique<FastS_2DZLocationIterator>(numDocs, strict, location);
}
