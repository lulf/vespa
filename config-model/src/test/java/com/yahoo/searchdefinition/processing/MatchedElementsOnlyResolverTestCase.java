// Copyright 2019 Oath Inc. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.searchdefinition.processing;

import com.yahoo.searchdefinition.RankProfileRegistry;
import com.yahoo.searchdefinition.Search;
import com.yahoo.searchdefinition.SearchBuilder;
import com.yahoo.searchdefinition.parser.ParseException;
import com.yahoo.vespa.documentmodel.SummaryField;
import com.yahoo.vespa.documentmodel.SummaryTransform;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import static com.yahoo.config.model.test.TestUtil.joinLines;
import static org.junit.Assert.assertEquals;

/**
 * @author geirst
 */
public class MatchedElementsOnlyResolverTestCase {

    @Rule
    public final ExpectedException exceptionRule = ExpectedException.none();

    @Test
    public void complex_field_with_some_struct_field_attributes_gets_default_transform() throws ParseException {
        assertSummaryField(joinLines("field my_field type map<string, string> {",
                "  indexing: summary",
                "  summary: matched-elements-only",
                "  struct-field key { indexing: attribute }",
                "}"),
                "my_field", SummaryTransform.MATCHED_ELEMENTS_FILTER);

        assertSummaryField(joinLines("field my_field type map<string, elem> {",
                "  indexing: summary",
                "  summary: matched-elements-only",
                "  struct-field key { indexing: attribute }",
                "}"),
                "my_field", SummaryTransform.MATCHED_ELEMENTS_FILTER);

        assertSummaryField(joinLines("field my_field type array<elem> {",
                "  indexing: summary",
                "  summary: matched-elements-only",
                "  struct-field name { indexing: attribute }",
                "}"),
                "my_field", SummaryTransform.MATCHED_ELEMENTS_FILTER);
    }

    @Test
    public void complex_field_with_only_struct_field_attributes_gets_attribute_transform() throws ParseException {
        assertSummaryField(joinLines("field my_field type map<string, string> {",
                "  indexing: summary",
                "  summary: matched-elements-only",
                "  struct-field key { indexing: attribute }",
                "  struct-field value { indexing: attribute }",
                "}"),
                "my_field", SummaryTransform.MATCHED_ATTRIBUTE_ELEMENTS_FILTER);

        assertSummaryField(joinLines("field my_field type map<string, elem> {",
                "  indexing: summary",
                "  summary: matched-elements-only",
                "  struct-field key { indexing: attribute }",
                "  struct-field value.name { indexing: attribute }",
                "  struct-field value.weight { indexing: attribute }",
                "}"),
                "my_field", SummaryTransform.MATCHED_ATTRIBUTE_ELEMENTS_FILTER);

        assertSummaryField(joinLines("field my_field type array<elem> {",
                "  indexing: summary",
                "  summary: matched-elements-only",
                "  struct-field name { indexing: attribute }",
                "  struct-field weight { indexing: attribute }",
                "}"),
                "my_field", SummaryTransform.MATCHED_ATTRIBUTE_ELEMENTS_FILTER);
    }

    @Test
    public void explicit_complex_summary_field_can_use_filter_transform_with_reference_to_source_field() throws ParseException {
        String documentSummary = joinLines("document-summary my_summary {",
                "  summary my_filter_field type map<string, string> {",
                "    source: my_field",
                "    matched-elements-only",
                "  }",
                "}");
        {
            var search = buildSearch(joinLines("field my_field type map<string, string> {",
                    "  indexing: summary",
                    "  struct-field key { indexing: attribute }",
                    "}"),
                    documentSummary);
            assertSummaryField(search.getSummaryField("my_filter_field"),
                    SummaryTransform.MATCHED_ELEMENTS_FILTER, "my_field");
            assertSummaryField(search.getSummaryField("my_field"),
                    SummaryTransform.NONE, "my_field");
        }
        {
            var search = buildSearch(joinLines("field my_field type map<string, string> {",
                    "  indexing: summary",
                    "  struct-field key { indexing: attribute }",
                    "  struct-field value { indexing: attribute }",
                    "}"),
                    documentSummary);
            assertSummaryField(search.getSummaryField("my_filter_field"),
                    SummaryTransform.MATCHED_ATTRIBUTE_ELEMENTS_FILTER, "my_field");
            assertSummaryField(search.getSummaryField("my_field"),
                    SummaryTransform.ATTRIBUTECOMBINER, "my_field");
        }
    }

    @Test
    public void primitive_array_attribute_field_gets_attribute_transform() throws ParseException {
        assertSummaryField(joinLines("field my_field type array<string> {",
                "  indexing: attribute | summary",
                "  summary: matched-elements-only",
                "}"),
                "my_field", SummaryTransform.MATCHED_ATTRIBUTE_ELEMENTS_FILTER);
    }

    @Test
    public void primitive_weighted_set_attribute_field_gets_attribute_transform() throws ParseException {
        assertSummaryField(joinLines("field my_field type weightedset<string> {",
                "  indexing: attribute | summary",
                "  summary: matched-elements-only",
                "}"),
                "my_field", SummaryTransform.MATCHED_ATTRIBUTE_ELEMENTS_FILTER);
    }

    @Test
    public void explicit_summary_field_can_use_filter_transform_with_reference_to_attribute_source_field() throws ParseException {
        String documentSummary = joinLines("document-summary my_summary {",
                "  summary my_filter_field type array<string> {",
                "    source: my_field",
                "    matched-elements-only",
                "  }",
                "}");

        var search = buildSearch(joinLines("field my_field type array<string> {",
                "  indexing: attribute | summary",
                "}"),
                documentSummary);
        assertSummaryField(search.getSummaryField("my_filter_field"),
                SummaryTransform.MATCHED_ATTRIBUTE_ELEMENTS_FILTER, "my_field");
        assertSummaryField(search.getSummaryField("my_field"),
                SummaryTransform.ATTRIBUTE, "my_field");
    }

    @Test
    public void unsupported_field_type_throws() throws ParseException {
        exceptionRule.expect(IllegalArgumentException.class);
        exceptionRule.expectMessage("For search 'test', document summary 'default', summary field 'my_field': " +
                "'matched-elements-only' is not supported for this field type. " +
                "Supported field types are: array of primitive, weighted set of primitive, " +
                "array of simple struct, map of primitive type to simple struct, " +
                "and map of primitive type to primitive type");
        buildSearch(joinLines("field my_field type string {",
                "  indexing: summary",
                "  summary: matched-elements-only",
                "}"));
    }

    private void assertSummaryField(String fieldContent, String fieldName, SummaryTransform expTransform) throws ParseException {
        var search = buildSearch(fieldContent);
        assertSummaryField(search.getSummaryField(fieldName), expTransform, fieldName);
    }

    private void assertSummaryField(SummaryField field, SummaryTransform expTransform, String expSourceField) {
        assertEquals(expTransform, field.getTransform());
        assertEquals(expSourceField, field.getSingleSource());
    }

    private Search buildSearch(String field) throws ParseException {
        return buildSearch(field, "");
    }

    private Search buildSearch(String field, String summary) throws ParseException {
        var builder = new SearchBuilder(new RankProfileRegistry());
        builder.importString(joinLines("search test {",
                "  document test {",
                "    struct elem {",
                "      field name type string {}",
                "      field weight type int {}",
                "    }",
                field,
                "  }",
                summary,
                "}"));
        builder.build();
        return builder.getSearch();
    }
}
