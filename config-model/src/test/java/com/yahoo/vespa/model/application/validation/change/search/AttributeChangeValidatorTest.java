// Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.application.validation.change.search;

import com.yahoo.config.application.api.ValidationOverrides;
import com.yahoo.vespa.model.application.validation.change.VespaConfigChangeAction;
import org.junit.Test;

import java.time.Instant;
import java.util.List;

import static com.yahoo.vespa.model.application.validation.change.ConfigChangeTestUtils.newRefeedAction;
import static com.yahoo.vespa.model.application.validation.change.ConfigChangeTestUtils.newRestartAction;

public class AttributeChangeValidatorTest {

    private static class Fixture extends ContentClusterFixture {
        AttributeChangeValidator validator;

        public Fixture(String currentSd, String nextSd) throws Exception {
            super(currentSd, nextSd);
            validator = new AttributeChangeValidator(currentDb().getDerivedConfiguration().getAttributeFields(),
                    currentDb().getDerivedConfiguration().getIndexSchema(),
                    currentDocType(),
                    nextDb().getDerivedConfiguration().getAttributeFields(),
                    nextDb().getDerivedConfiguration().getIndexSchema(),
                    nextDocType());
        }

        @Override
        public List<VespaConfigChangeAction> validate() {
            return validator.validate(ValidationOverrides.empty, Instant.now());
        }

    }

    @Test
    public void adding_attribute_aspect_require_restart() throws Exception {
        Fixture f = new Fixture("field f1 type string { indexing: summary }",
                "field f1 type string { indexing: attribute | summary }");
        f.assertValidation(newRestartAction(
                "Field 'f1' changed: add attribute aspect"));
    }

    @Test
    public void removing_attribute_aspect_require_restart() throws Exception {
        Fixture f = new Fixture("field f1 type string { indexing: attribute | summary }",
                "field f1 type string { indexing: summary }");
        f.assertValidation(newRestartAction(
                "Field 'f1' changed: remove attribute aspect"));
    }

    @Test
    public void adding_attribute_field_is_ok() throws Exception {
        Fixture f = new Fixture("", "field f1 type string { indexing: attribute | summary \n attribute: fast-search }");
        f.assertValidation();
    }

    @Test
    public void removing_attribute_field_is_ok() throws Exception {
        Fixture f = new Fixture("field f1 type string { indexing: attribute | summary }", "");
        f.assertValidation();
    }

    @Test
    public void changing_fast_search_require_restart() throws Exception {
        new Fixture("field f1 type string { indexing: attribute }",
                "field f1 type string { indexing: attribute \n attribute: fast-search }").
                assertValidation(newRestartAction(
                        "Field 'f1' changed: add attribute 'fast-search'"));
    }

    @Test
    public void changing_fast_access_require_restart() throws Exception {
        new Fixture("field f1 type string { indexing: attribute \n attribute: fast-access }",
                "field f1 type string { indexing: attribute }").
                assertValidation(newRestartAction(
                        "Field 'f1' changed: remove attribute 'fast-access'"));
    }

    @Test
    public void changing_huge_require_restart() throws Exception {
        new Fixture("field f1 type string { indexing: attribute }",
                "field f1 type string { indexing: attribute \n attribute: huge }").
                assertValidation(newRestartAction(
                        "Field 'f1' changed: add attribute 'huge'"));
    }

    @Test
    public void changing_dense_posting_list_threshold_require_restart() throws Exception {
        new Fixture(
                "field f1 type predicate { indexing: attribute \n index { arity: 8 \n dense-posting-list-threshold: 0.2 } }",
                "field f1 type predicate { indexing: attribute \n index { arity: 8 \n dense-posting-list-threshold: 0.4 } }").
                assertValidation(newRestartAction(
                        "Field 'f1' changed: change property 'dense-posting-list-threshold' from '0.2' to '0.4'"));
    }

    @Test
    public void removing_attribute_aspect_from_index_field_is_ok() throws Exception {
        Fixture f = new Fixture("field f1 type string { indexing: index | attribute }",
                "field f1 type string { indexing: index }");
        f.assertValidation();
    }

    @Test
    public void removing_attribute_aspect_from_index_and_summary_field_is_ok() throws Exception {
        Fixture f = new Fixture("field f1 type string { indexing: index | attribute | summary }",
                "field f1 type string { indexing: index | summary }");
        f.assertValidation();
    }

    @Test
    public void changing_tensor_type_of_tensor_field_requires_refeed() throws Exception {
        new Fixture(
                "field f1 type tensor(x[2]) { indexing: attribute }",
                "field f1 type tensor(x[3]) { indexing: attribute }")
                .assertValidation(newRefeedAction(
                        "tensor-type-change",
                        ValidationOverrides.empty,
                        "Field 'f1' changed: tensor type: 'tensor(x[2])' -> 'tensor(x[3])'", Instant.now()));

        new Fixture(
                "field f1 type tensor(x[5]) { indexing: attribute }",
                "field f1 type tensor(x[3]) { indexing: attribute }")
                .assertValidation(newRefeedAction(
                        "tensor-type-change",
                        ValidationOverrides.empty,
                        "Field 'f1' changed: tensor type: 'tensor(x[5])' -> 'tensor(x[3])'", Instant.now()));
    }

    @Test
    public void not_changing_tensor_type_of_tensor_field_is_ok() throws Exception {
        new Fixture(
                "field f1 type tensor(x[2]) { indexing: attribute }",
                "field f1 type tensor(x[2]) { indexing: attribute }")
                .assertValidation();
    }

    @Test
    public void adding_rank_filter_requires_restart() throws Exception {
        new Fixture("field f1 type string { indexing: attribute }",
                "field f1 type string { indexing: attribute \n rank: filter }").
                assertValidation(newRestartAction(
                        "Field 'f1' changed: add attribute 'rank: filter'"));
    }

    @Test
    public void removing_rank_filter_requires_restart() throws Exception {
        new Fixture("field f1 type string { indexing: attribute \n rank: filter }",
                "field f1 type string { indexing: attribute }").
                assertValidation(newRestartAction(
                        "Field 'f1' changed: remove attribute 'rank: filter'"));
    }

    @Test
    public void adding_hnsw_index_requires_restart() throws Exception {
        new Fixture("field f1 type tensor(x[2]) { indexing: attribute }",
                "field f1 type tensor(x[2]) { indexing: attribute | index \n index { hnsw } }").
                assertValidation(newRestartAction(
                        "Field 'f1' changed: add attribute 'indexing: index'"));
    }

    @Test
    public void removing_hnsw_index_requres_restart() throws Exception {
        new Fixture("field f1 type tensor(x[2]) { indexing: attribute | index \n index { hnsw } }",
                "field f1 type tensor(x[2]) { indexing: attribute }").
                assertValidation(newRestartAction(
                        "Field 'f1' changed: remove attribute 'indexing: index'"));
    }

    @Test
    public void changing_distance_metric_without_hnsw_index_enabled_requires_restart() throws Exception {
        new Fixture("field f1 type tensor(x[2]) { indexing: attribute }",
                "field f1 type tensor(x[2]) { indexing: attribute \n attribute { " +
                        "distance-metric: geodegrees \n } }").
                assertValidation(newRestartAction("Field 'f1' changed: change property " +
                        "'distance-metric' from 'EUCLIDEAN' to 'GEODEGREES'"));
    }

    @Test
    public void changing_distance_metric_with_hnsw_index_enabled_requires_restart() throws Exception {
        new Fixture("field f1 type tensor(x[2]) { indexing: attribute | index \n index { hnsw } }",
                "field f1 type tensor(x[2]) { indexing: attribute | index \n attribute { " +
                        "distance-metric: geodegrees \n } }").
                assertValidation(newRestartAction("Field 'f1' changed: change property " +
                        "'distance-metric' from 'EUCLIDEAN' to 'GEODEGREES'"));
    }

    @Test
    public void changing_hnsw_index_property_max_links_per_node_requires_restart() throws Exception {
        new Fixture("field f1 type tensor(x[2]) { indexing: attribute | index \n index { hnsw } }",
                "field f1 type tensor(x[2]) { indexing: attribute | index \n index { " +
                        "hnsw { max-links-per-node: 4 } } }").
                assertValidation(newRestartAction("Field 'f1' changed: change hnsw index property " +
                        "'max-links-per-node' from '16' to '4'"));
    }

    @Test
    public void changing_hnsw_index_property_neighbors_to_explore_at_insert_requires_restart() throws Exception {
        new Fixture("field f1 type tensor(x[2]) { indexing: attribute | index \n index { hnsw } }",
                "field f1 type tensor(x[2]) { indexing: attribute | index \n index { " +
                        "hnsw { neighbors-to-explore-at-insert: 100 } } }").
                assertValidation(newRestartAction("Field 'f1' changed: change hnsw index property " +
                        "'neighbors-to-explore-at-insert' from '200' to '100'"));
    }
}
