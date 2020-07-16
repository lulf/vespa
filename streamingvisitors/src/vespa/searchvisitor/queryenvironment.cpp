// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.

#include "queryenvironment.h"
#include <vespa/searchlib/common/geo_location.h>
#include <vespa/searchlib/common/geo_location_spec.h>
#include <vespa/searchlib/common/geo_location_parser.h>

#include <vespa/log/log.h>
LOG_SETUP(".searchvisitor.queryenvironment");

using search::IAttributeManager;
using search::fef::Properties;
using vespalib::string;

namespace streaming {

namespace {

search::fef::Location
parseLocation(const string & location_str)
{
    search::fef::Location fefLocation;
    if (location_str.empty()) {
        return fefLocation;
    }
    search::common::GeoLocationParser locationParser;
    if (!locationParser.parseOldFormatWithField(location_str)) {
        LOG(warning, "Location parse error (location: '%s'): %s. Location ignored.",
                     location_str.c_str(), locationParser.getParseError());
        return fefLocation;
    }
    auto location = locationParser.getGeoLocation();
    if (location.has_point) {
        fefLocation.setAttribute(locationParser.getFieldName());
        fefLocation.setXPosition(location.point.x);
        fefLocation.setYPosition(location.point.y);
        fefLocation.setXAspect(location.x_aspect.multiplier);
        fefLocation.setValid(true);
    }
    return fefLocation;
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
    _location(parseLocation(location_str))
{
}

QueryEnvironment::~QueryEnvironment() {}

} // namespace streaming

