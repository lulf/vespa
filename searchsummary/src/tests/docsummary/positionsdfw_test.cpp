// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
// Unit tests for positionsdfw.

#include <vespa/searchlib/attribute/extendableattributes.h>
#include <vespa/searchlib/attribute/iattributemanager.h>
#include <vespa/searchlib/common/matching_elements.h>
#include <vespa/searchsummary/docsummary/docsumfieldwriter.h>
#include <vespa/searchsummary/docsummary/positionsdfw.h>
#include <vespa/searchsummary/docsummary/idocsumenvironment.h>
#include <vespa/searchsummary/docsummary/docsumstate.h>
#include <vespa/searchlib/util/rawbuf.h>
#include <vespa/vespalib/testkit/testapp.h>
#include <vespa/vespalib/data/slime/slime.h>
#include <vespa/juniper/rpinterface.h>

#include <vespa/log/log.h>
LOG_SETUP("positionsdfw_test");

using search::IAttributeManager;
using search::MatchingElements;
using search::SingleInt64ExtAttribute;
using search::attribute::IAttributeContext;
using search::attribute::IAttributeVector;
using search::attribute::IAttributeFunctor;
using vespalib::string;
using std::vector;

namespace search::docsummary {

namespace {

class Test : public vespalib::TestApp {
    void requireThat2DPositionFieldIsWritten();

public:
    int Main() override;
};

int
Test::Main()
{
    TEST_INIT("positionsdfw_test");

    TEST_DO(requireThat2DPositionFieldIsWritten());

    TEST_DONE();
}

struct MyEnvironment : IDocsumEnvironment {
    IAttributeManager *attribute_man;

    MyEnvironment() : attribute_man(0) {}

    IAttributeManager *getAttributeManager() override { return attribute_man; }
    string lookupIndex(const string &s) const override { return s; }
    juniper::Juniper *getJuniper() override { return 0; }
};

class MyAttributeContext : public IAttributeContext {
    const IAttributeVector &_attr;
public:
    MyAttributeContext(const IAttributeVector &attr) : _attr(attr) {}
     const IAttributeVector *getAttribute(const string &) const override {
        return &_attr;
    }
    const IAttributeVector *getAttributeStableEnum(const string &) const override {
        LOG_ABORT("MyAttributeContext::getAttributeStableEnum should not be reached");
    }
    void getAttributeList(vector<const IAttributeVector *> &) const override {
        LOG_ABORT("MyAttributeContext::getAttributeList should not be reached");
    }

    void
    asyncForAttribute(const vespalib::string &, std::unique_ptr<IAttributeFunctor>) const override {
        LOG_ABORT("MyAttributeContext::asyncForAttribute should not be reached");
    }
};

class MyAttributeManager : public IAttributeManager {
    const IAttributeVector &_attr;
public:

    MyAttributeManager(const IAttributeVector &attr) : _attr(attr) {}
    AttributeGuard::UP getAttribute(const string &) const override {
        LOG_ABORT("should not be reached");
    }
    std::unique_ptr<attribute::AttributeReadGuard> getAttributeReadGuard(const string &, bool) const override {
        LOG_ABORT("should not be reached");
    }
    void getAttributeList(vector<AttributeGuard> &) const override {
        LOG_ABORT("should not be reached");
    }

    void
    asyncForAttribute(const vespalib::string &, std::unique_ptr<IAttributeFunctor>) const override {
        LOG_ABORT("should not be reached");
    }

    IAttributeContext::UP createContext() const override {
        return IAttributeContext::UP(new MyAttributeContext(_attr));
    }

    std::shared_ptr<attribute::ReadableAttributeVector> readable_attribute_vector(const string&) const override {
        LOG_ABORT("should not be reached");
    }
};

struct MyGetDocsumsStateCallback : GetDocsumsStateCallback {
    virtual void FillSummaryFeatures(GetDocsumsState *, IDocsumEnvironment *) override {}
    virtual void FillRankFeatures(GetDocsumsState *, IDocsumEnvironment *) override {}
    virtual void ParseLocation(GetDocsumsState *) override {}
    std::unique_ptr<MatchingElements> fill_matching_elements(const MatchingElementsFields &) override { abort(); }
};

template <typename AttrType>
void checkWritePositionField(Test &test, AttrType &attr,
                             uint32_t doc_id, const string &expected) {
    for (AttributeVector::DocId i = 0; i < doc_id + 1; ) {
        attr.addDoc(i);
        if (i == 007) {
            attr.add((int64_t) -1);
        } else if (i == 0x42) {
            attr.add(0xAAAAaaaaAAAAaaaa);
        } else if (i == 0x17) {
            attr.add(0x5555aaaa5555aaab);
        } else if (i == 42) {
            attr.add(0x8000000000000000);
        } else {
            attr.add(i); // value = docid
        }
    }

    MyAttributeManager attribute_man(attr);
    PositionsDFW::UP writer =
        createPositionsDFW(attr.getName().c_str(), &attribute_man);
    ASSERT_TRUE(writer.get());
    ResType res_type = RES_LONG_STRING;
    MyGetDocsumsStateCallback callback;
    GetDocsumsState state(callback);
    state._attributes.push_back(&attr);

    vespalib::Slime target;
    vespalib::slime::SlimeInserter inserter(target);
    writer->insertField(doc_id, &state, res_type, inserter);

    vespalib::Memory got = target.get().asString();
    test.EXPECT_EQUAL(expected.size(), got.size);
    test.EXPECT_EQUAL(expected, string(got.data, got.size));
}

void Test::requireThat2DPositionFieldIsWritten() {
    SingleInt64ExtAttribute attr("foo");
    checkWritePositionField(*this, attr, 0x3e, "<position x=\"6\" y=\"7\" latlong=\"N0.000007;E0.000006\" />");
    checkWritePositionField(*this, attr,  007, "<position x=\"-1\" y=\"-1\" latlong=\"S0.000001;W0.000001\" />");
    checkWritePositionField(*this, attr, 0x42, "<position x=\"0\" y=\"-1\" latlong=\"S0.000001;E0.000000\" />");
    checkWritePositionField(*this, attr, 0x17, "<position x=\"-16711935\" y=\"16711935\" latlong=\"N16.711935;W16.711935\" />");
    checkWritePositionField(*this, attr,   42, "");

}

}  // namespace
}

TEST_APPHOOK(search::docsummary::Test);
