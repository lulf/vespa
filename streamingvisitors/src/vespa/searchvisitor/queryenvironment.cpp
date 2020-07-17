// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "queryenvironment.h"
#include <vespa/searchlib/common/geo_location.h>
#include <vespa/searchlib/common/geo_location_spec.h>
#include <vespa/searchlib/common/geo_location_parser.h>

#include <vespa/log/log.h>
LOG_SETUP(".searchvisitor.queryenvironment");

using search::IAttributeManager;
using search::common::GeoLocationParser;
using search::common::GeoLocationSpec;
using search::fef::Properties;
using vespalib::string;

namespace streaming {

namespace {

std::vector<GeoLocationSpec>
parseLocation(const string & location_str)
{
    std::vector<GeoLocationSpec> fefLocations;
    if (location_str.empty()) {
        return fefLocations;
    }
    GeoLocationParser locationParser;
    if (!locationParser.parseOldFormatWithField(location_str)) {
        LOG(warning, "Location parse error (location: '%s'): %s. Location ignored.",
                     location_str.c_str(), locationParser.getParseError());
        return fefLocations;
    }
    auto loc = locationParser.getGeoLocation();
    if (loc.has_point) {
        fefLocations.push_back(GeoLocationSpec{locationParser.getFieldName(), loc});
    }
    return fefLocations;
}

}

QueryEnvironment::QueryEnvironment(const string & location_str,
                                   const IndexEnvironment & indexEnv,
                                   const Properties & properties,
                                   const IAttributeManager * attrMgr) :
    _indexEnv(indexEnv),
    _properties(properties),
    _attrCtx(attrMgr->createContext()),
    _queryTerms(),
    _locations(parseLocation(location_str))
{
}

QueryEnvironment::~QueryEnvironment() {}

} // namespace streaming

