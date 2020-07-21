// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.model.container.component;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/**
 * @author bjorncs
 */
public class BindingPatternTest {

    @Test
    public void parses_valid_bindings_correctly() {
        assertBindingParses("http://host:1234/path");
        assertBindingParses("http://host/path");
        assertBindingParses("http://host/");
        assertBindingParses("*://*:*/*");
        assertBindingParses("http://*/*");
        assertBindingParses("https://*/my/path");
        assertBindingParses("https://*/path/*");
        assertBindingParses("https://host:*/path/*");
        assertBindingParses("https://host:1234/*");
    }

    @Test
    public void getters_returns_correct_components() {
        {
            BindingPattern pattern = BindingPattern.createModelGeneratedFromPattern("http://host:1234/path/*");
            assertEquals("http", pattern.scheme());
            assertEquals("host", pattern.host());
            assertEquals("1234", pattern.port().get());
            assertEquals("/path/*", pattern.path());
        }
        {
            BindingPattern pattern = BindingPattern.createModelGeneratedFromPattern("https://*/path/v1/");
            assertEquals("https", pattern.scheme());
            assertEquals("*", pattern.host());
            assertFalse(pattern.port().isPresent());
            assertEquals("/path/v1/", pattern.path());
        }
    }

    private static void assertBindingParses(String binding) {
        BindingPattern pattern = BindingPattern.createModelGeneratedFromPattern(binding);
        String stringRepresentation = pattern.patternString();
        assertEquals(
                "Expected string representation of parsed binding to match original binding string",
                binding, stringRepresentation);
    }

}