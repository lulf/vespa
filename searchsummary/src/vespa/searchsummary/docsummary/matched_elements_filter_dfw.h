// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#pragma once

#include "docsumfieldwriter.h"

namespace search::attribute { class IAttributeContext; }

namespace search::docsummary {

/**
 * Field writer that filters matched elements (according to the query) from a multi-value or complex field
 * (array of primitive, weighted set of primitive, map of primitives, map of struct, array of struct)
 * that is retrieved from the document store.
 */
class MatchedElementsFilterDFW : public IDocsumFieldWriter {
private:
    std::string _input_field_name;
    uint32_t _input_field_enum;
    std::shared_ptr<MatchingElementsFields> _matching_elems_fields;

    const std::vector<uint32_t>& get_matching_elements(uint32_t docid, GetDocsumsState& state) const;

public:
    MatchedElementsFilterDFW(const std::string& input_field_name, uint32_t input_field_enum,
                             std::shared_ptr<MatchingElementsFields> matching_elems_fields);
    static std::unique_ptr<IDocsumFieldWriter> create(const std::string& input_field_name, uint32_t input_field_enum,
                                                      std::shared_ptr<MatchingElementsFields> matching_elems_fields);
    static std::unique_ptr<IDocsumFieldWriter> create(const std::string& input_field_name, uint32_t input_field_enum,
                                                      search::attribute::IAttributeContext& attr_ctx,
                                                      std::shared_ptr<MatchingElementsFields> matching_elems_fields);
    ~MatchedElementsFilterDFW();
    bool IsGenerated() const override { return false; }
    void insertField(uint32_t docid, GeneralResult* result, GetDocsumsState *state,
                     ResType type, vespalib::slime::Inserter& target) override;
};

}
