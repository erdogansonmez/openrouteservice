package org.heigit.ors.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.*;

class ExtendedStorageTest {

    @Test
    void testDefaultConstructor() {
        ExtendedStorage storage = new ExtendedStorage();
        assertTrue(storage.getEnabled(), "Default constructor should initialize 'enabled' to true");
        assertEquals(Path.of(""), storage.getFilepath(), "Default constructor should initialize 'filepath' to an empty string");
    }

    @Test
    void testStringConstructor() {
        ExtendedStorage storage = new ExtendedStorage("");
        assertTrue(storage.getEnabled(), "String constructor should not alter 'enabled' state");
    }

    @Test
    void testLinkedHashMapConstructor() {
        ExtendedStorage storage = new ExtendedStorage(new LinkedHashMap<>());
        assertTrue(storage.getEnabled(), "LinkedHashMap constructor should not alter 'enabled' state");
    }

    @Test
    void testGetterAndSetter() {
        ExtendedStorage storage = new ExtendedStorage();
        storage.setEnabled(false);
        assertFalse(storage.getEnabled(), "setEnabled(false) should result in 'enabled' being false");
        storage.setEnabled(true);
        assertTrue(storage.getEnabled(), "setEnabled(true) should result in 'enabled' being true");
    }

    @ParameterizedTest
    @CsvSource({
            ", ''", // Represents the default path for null input
            "'', ''", // Represents the empty string input
            "'src/test/resources', 'src/test/resources'"
    })
    void testSetFilepath(String input, String expected) {
        ExtendedStorage storage = new ExtendedStorage();
        storage.setFilepath(input);

        if (input == null || input.isEmpty()) {
            assertEquals(Path.of(""), storage.getFilepath(), "setFilepath(null) should result in an empty path");
        } else {
            assertEquals(Paths.get(expected).toAbsolutePath(), storage.getFilepath(), "setFilepath('src/test/resources') should result in an absolute path");
        }
    }

    @Test
    void testSerializationProducesCorrectJson() throws Exception {
        // Step 1: Create and configure an instance of ExtendedStorage
        ExtendedStorage storage = new ExtendedStorage();
        storage.setFilepath("src/test/resources");

        // Step 2: Serialize the object to JSON
        ObjectMapper mapper = new ObjectMapper();
        String jsonResult = mapper.writeValueAsString(storage);

        // Step 3: Assert JSON structure and values including enabled
        assertTrue(jsonResult.contains("\"enabled\":true"), "Serialized JSON should have 'enabled' set to true");
        // Assert that jsonResult doesn't contain the default path parameter
        assertFalse(jsonResult.contains("\"filepath\":"), "Serialized JSON should not have 'filepath' set to an empty string");
    }
}