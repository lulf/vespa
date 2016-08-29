// Copyright 2016 Yahoo Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.search;

import com.yahoo.config.model.producer.AbstractConfigProducer;
import com.yahoo.search.config.IndexInfoConfig;
import com.yahoo.searchdefinition.derived.DerivedConfiguration;
import com.yahoo.searchdefinition.RankingConstant;
import com.yahoo.vespa.config.search.AttributesConfig;
import com.yahoo.vespa.config.search.SummaryConfig;
import com.yahoo.vespa.config.search.IndexschemaConfig;
import com.yahoo.vespa.config.search.RankProfilesConfig;
import com.yahoo.vespa.config.search.core.RankingConstantsConfig;
import com.yahoo.vespa.config.search.SummarymapConfig;
import com.yahoo.vespa.config.search.summary.JuniperrcConfig;
import com.yahoo.vespa.configdefinition.IlscriptsConfig;
import com.yahoo.config.FileReference;
import com.yahoo.vespa.model.utils.FileSender;

/**
 * Represents a document database and the backend configuration needed for this database.
 *
 * @author geirst
 */
public class DocumentDatabase extends AbstractConfigProducer implements
    IndexInfoConfig.Producer,
    IlscriptsConfig.Producer,
    AttributesConfig.Producer,
    RankProfilesConfig.Producer,
    RankingConstantsConfig.Producer,
    IndexschemaConfig.Producer,
    JuniperrcConfig.Producer,
    SummarymapConfig.Producer,
    SummaryConfig.Producer {

    private final String inputDocType;
    private final DerivedConfiguration derivedCfg;

    public DocumentDatabase(AbstractConfigProducer parent, String inputDocType, DerivedConfiguration derivedCfg) {
        super(parent, inputDocType);
        this.inputDocType = inputDocType;
        this.derivedCfg = derivedCfg;
    }

    public String getName() {
        return inputDocType;
    }

    public String getInputDocType() {
        return inputDocType;
    }

    public DerivedConfiguration getDerivedConfiguration() {
        return derivedCfg;
    }

    @Override
    public void getConfig(IndexInfoConfig.Builder builder) {
        derivedCfg.getIndexInfo().getConfig(builder);
    }
    
    @Override
    public void getConfig(IlscriptsConfig.Builder builder) {
        derivedCfg.getIndexingScript().getConfig(builder);
    }
    
    @Override
    public void getConfig(AttributesConfig.Builder builder) {
        derivedCfg.getAttributeFields().getConfig(builder);
    }
    
    @Override
    public void getConfig(RankProfilesConfig.Builder builder) {
        derivedCfg.getRankProfileList().getConfig(builder);
    }

    @Override
    public void getConfig(RankingConstantsConfig.Builder builder) {
        for (RankingConstant rConstant : derivedCfg.getSearch().getRankingConstants()) {
            if ("".equals(rConstant.getFileReference())) {
                System.err.println("INVALID rank constant "+rConstant.getName()+" [missing file reference]");
                continue;
            }
            builder.constant(new RankingConstantsConfig.Constant.Builder()
                             .name(rConstant.getName())
                             .fileref(rConstant.getFileReference())
                             .type(rConstant.getType()));
        }
    }

    @Override
    public void getConfig(IndexschemaConfig.Builder builder) {
        derivedCfg.getIndexSchema().getConfig(builder);
    }
    
    @Override
    public void getConfig(JuniperrcConfig.Builder builder) {
        derivedCfg.getJuniperrc().getConfig(builder);
    }
    
    @Override
    public void getConfig(SummarymapConfig.Builder builder) {
        derivedCfg.getSummaryMap().getConfig(builder);
    }
    
    @Override
    public void getConfig(SummaryConfig.Builder builder) {
        derivedCfg.getSummaries().getConfig(builder);
    }
}
