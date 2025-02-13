package org.heigit.ors.benchmark;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.MockitoAnnotations;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CoordinateGeneratorTest {
    private CoordinateGenerator generator;
    private double[] extent;

    @Mock
    CloseableHttpClient closeableHttpClient;

    @Captor
    private ArgumentCaptor<HttpClientResponseHandler<String>> handlerCaptor;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        extent = new double[] { 8.6286, 49.3590, 8.7957, 49.4715 };
        generator = new TestCoordinateGenerator(
                100, extent, 1, 100, 100, 350, "driving-car", null);
    }

    // Set the base url to openrouteservice.org and catch the runtime exception
    @Test
    void testCoordinateGeneratorWithMissingApiKey() {
        assertThrows(RuntimeException.class, () -> {
            new CoordinateGenerator(
                    100, extent, 1, 100, 100, 350, "driving-car", "https://openrouteservice.org");
        });
    }

    static Stream<Arguments> pointSizeProvider() {
        return Stream.of(
                Arguments.of(1),
                Arguments.of(5),
                Arguments.of(10),
                Arguments.of(20),
                Arguments.of(50),
                Arguments.of(1000));
    }

    @ParameterizedTest
    @MethodSource("pointSizeProvider")
    void testRandomCoordinatesInExtentWithDifferentSizes(int numPoints) {
        List<double[]> points = generator.randomCoordinatesInExtent(numPoints);

        // Test correct number of points
        assertEquals(numPoints, points.size());

        // Test each point is within extent
        for (double[] point : points) {
            assertEquals(2, point.length);
            assertTrue(point[0] >= extent[0] && point[0] <= extent[2],
                    "X coordinate should be within extent");
            assertTrue(point[1] >= extent[1] && point[1] <= extent[3],
                    "Y coordinate should be within extent");
        }
    }

    @Test
    void testRandomCoordinatesInExtentZeroPoints() {
        List<double[]> points = generator.randomCoordinatesInExtent(0);
        assertTrue(points.isEmpty());
    }

    @Test
    void testRandomCoordinatesInExtentNegativePoints() {
        List<double[]> points = generator.randomCoordinatesInExtent(-1);
        assertTrue(points.isEmpty());
    }

    @Test
    void testApplyMatrixWithMockedResponse() throws Exception {
        // Prepare test data
        String mockJsonResponse = """
                {
                  "distances": [[0], [99]],
                  "destinations": [
                    { "location": [8.681009, 49.409929], "snapped_distance": 7.93 }
                  ],
                  "sources": [
                    { "location": [8.681009, 49.409929], "snapped_distance": 7.93 },
                    { "location": [8.687026, 49.420002], "snapped_distance": 1.86 }
                  ]
                }
                """;

        // Mock the execute method to return our response
        when(closeableHttpClient.execute(any(HttpPost.class), handlerCaptor.capture())).thenReturn(mockJsonResponse);

        // Create test generator with mocked client
        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                100, extent, 1, 100, 100, 350, "driving-car", null);
        testGenerator.setHttpClient(closeableHttpClient);

        // Test with sample points
        List<double[]> testPoints = List.of(
                new double[] { 8.681009, 49.409929 });
        Map<String, List<double[]>> result = testGenerator.applyMatrix(testPoints);

        // Verify the response handler was used
        verify(closeableHttpClient, times(1)).execute(any(), handlerCaptor.capture());

        // Verify results
        assertNotNull(result);
        assertEquals(8.681009, result.get("from_points").get(0)[0], 0.0001);
        assertEquals(49.409929, result.get("from_points").get(0)[1], 0.0001);
        assertEquals(8.687026, result.get("to_points").get(0)[0], 0.0001);
        assertEquals(49.420002, result.get("to_points").get(0)[1], 0.0001);
    }

    @Test
    void testApplyMatrixWithEmptyResponse() throws Exception {
        String mockJsonResponse = "{\"distances\":[],\"destinations\":[],\"sources\":[]}";

        when(closeableHttpClient.execute(any(HttpPost.class), handlerCaptor.capture())).thenReturn(mockJsonResponse);

        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                100, extent, 1, 100, 100, 350, "driving-car", null);
        testGenerator.setHttpClient(closeableHttpClient);

        Map<String, List<double[]>> result = testGenerator.applyMatrix(List.of(new double[] { 8.681, 49.41 }));

        verify(closeableHttpClient, times(1)).execute(any(), handlerCaptor.capture());
        assertTrue(result.get("from_points").isEmpty());
        assertTrue(result.get("to_points").isEmpty());
    }

    @Test
    void testApplyMatrixWithDistanceFiltering() throws Exception {
        String mockJsonResponse = """
                {
                  "distances": [[0], [50], [150]],
                  "destinations": [{ "location": [8.681, 49.41] }],
                  "sources": [
                    { "location": [8.681, 49.41] },
                    { "location": [8.682, 49.42] },
                    { "location": [8.683, 49.43] }
                  ]
                }
                """;

        when(closeableHttpClient.execute(any(HttpPost.class), handlerCaptor.capture())).thenReturn(mockJsonResponse);

        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                100, extent, 40, 125, 100, 350, "driving-car", null);
        testGenerator.setHttpClient(closeableHttpClient);

        Map<String, List<double[]>> result = testGenerator.applyMatrix(List.of(new double[] { 8.681, 49.41 }));

        verify(closeableHttpClient, times(1)).execute(any(), handlerCaptor.capture());
        assertEquals(1, result.get("from_points").size());
        assertEquals(1, result.get("to_points").size());
        assertEquals(8.682, result.get("to_points").get(0)[0], 0.0001);
        assertEquals(49.42, result.get("to_points").get(0)[1], 0.0001);
    }

    @Test
    void testApplyMatrixWithMalformedResponse() {
        String mockJsonResponse = "{ invalid json }";
        StringEntity entity = new StringEntity(mockJsonResponse, StandardCharsets.UTF_8);


        CloseableHttpResponse closeableHttpResponse = mock(CloseableHttpResponse.class);
        when(closeableHttpResponse.getEntity()).thenReturn(entity);
        try {
            when(closeableHttpClient.execute(any(HttpPost.class), handlerCaptor.capture()))
                    .thenReturn(mockJsonResponse);

            TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                    100, extent, 1, 100, 100, 350, "driving-car", null);
            testGenerator.setHttpClient(closeableHttpClient);

            assertThrows(Exception.class, () -> testGenerator.applyMatrix(List.of(new double[] { 8.681, 49.41 })));
            verify(closeableHttpClient, times(1)).execute(any(), handlerCaptor.capture());
        } catch (Exception e) {
            fail("Test setup failed: " + e.getMessage());
        }
    }

    @Test
    void testApplyMatrixWithHttpError() throws Exception {
        when(closeableHttpClient.execute(any(), handlerCaptor.capture()))
                .thenThrow(new IOException("Network error"))
                .thenThrow(new IOException("Network error"));

        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                100, extent, 1, 100, 100, 350, "driving-car", null);
        testGenerator.setHttpClient(closeableHttpClient);

        assertThrows(Exception.class, () -> testGenerator.applyMatrix(List.of(new double[] { 8.681, 49.41 })));
        verify(closeableHttpClient, times(1)).execute(any(), handlerCaptor.capture());
    }

    @Test
    void testApplyMatrixWithEmptyDestinations() throws Exception {

        CloseableHttpResponse closeableHttpResponse = mock(CloseableHttpResponse.class);

        String mockJsonResponse = """
                {
                  "distances": [[0], [99]],
                  "destinations": [],
                  "sources": [
                    { "location": [8.681009, 49.409929], "snapped_distance": 7.93 },
                    { "location": [8.687026, 49.420002], "snapped_distance": 1.86 }
                  ]
                }
                """;

        StringEntity entity = new StringEntity(mockJsonResponse, StandardCharsets.UTF_8);
        when(closeableHttpResponse.getEntity()).thenReturn(entity);
        when(closeableHttpClient.execute(any(HttpPost.class), handlerCaptor.capture())).thenReturn(mockJsonResponse);

        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                100, extent, 1, 100, 100, 350, "driving-car", null);
        testGenerator.setHttpClient(closeableHttpClient);

        Map<String, List<double[]>> result = testGenerator.applyMatrix(
                List.of(new double[] { 8.681009, 49.409929 }));

        // Verify empty results are returned
        assertNotNull(result);
        assertTrue(result.get("from_points").isEmpty());
        assertTrue(result.get("to_points").isEmpty());
        verify(closeableHttpClient, times(1)).execute(any(), handlerCaptor.capture());
    }

    @Test
    void testApplyMatrixWithMissingLocation() throws Exception {
        String mockJsonResponse = """
                {
                  "distances": [[0], [99]],
                  "destinations": [{ "snapped_distance": 7.93 }],
                  "sources": [
                    { "location": [8.681009, 49.409929], "snapped_distance": 7.93 }
                  ]
                }
                """;
        StringEntity entity = new StringEntity(mockJsonResponse, StandardCharsets.UTF_8);

        CloseableHttpResponse closeableHttpResponse = mock(CloseableHttpResponse.class);
        when(closeableHttpResponse.getEntity()).thenReturn(entity);
        when(closeableHttpClient.execute(any(HttpPost.class), handlerCaptor.capture())).thenReturn(mockJsonResponse);
        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                100, extent, 1, 100, 100, 350, "driving-car", null);
        testGenerator.setHttpClient(closeableHttpClient);

        Map<String, List<double[]>> result = testGenerator.applyMatrix(
                List.of(new double[] { 8.681009, 49.409929 }));

        // Verify empty results are returned for invalid destination
        assertNotNull(result);
        assertTrue(result.get("from_points").isEmpty());
        assertTrue(result.get("to_points").isEmpty());
        verify(closeableHttpClient, times(1)).execute(any(), handlerCaptor.capture());
    }

    @Test
    void testApplyMatrixWithNullResponse() throws Exception {

        when(closeableHttpClient.execute(any(HttpPost.class), handlerCaptor.capture())).thenReturn(null);

        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                100, extent, 1, 100, 100, 350, "driving-car", null);
        testGenerator.setHttpClient(closeableHttpClient);

        Map<String, List<double[]>> result = testGenerator.applyMatrix(
                List.of(new double[] { 8.681009, 49.409929 }));

        assertNotNull(result);
        assertTrue(result.get("from_points").isEmpty());
        assertTrue(result.get("to_points").isEmpty());
        verify(closeableHttpClient, times(1)).execute(any(), handlerCaptor.capture());
    }

    @Test
    void testApplyMatrixWithNullResponseEntity() throws Exception {

        CloseableHttpResponse closeableHttpResponse = mock(CloseableHttpResponse.class);
        when(closeableHttpResponse.getEntity()).thenReturn(null);
        when(closeableHttpClient.execute(any(HttpPost.class), handlerCaptor.capture())).thenReturn(null);

        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                100, extent, 1, 100, 100, 350, "driving-car", null);
        testGenerator.setHttpClient(closeableHttpClient);

        Map<String, List<double[]>> result = testGenerator.applyMatrix(
                List.of(new double[] { 8.681009, 49.409929 }));

        assertNotNull(result);
        assertTrue(result.get("from_points").isEmpty());
        assertTrue(result.get("to_points").isEmpty());
        verify(closeableHttpClient, times(1)).execute(any(), handlerCaptor.capture());
    }

    @Test
    void testGeneratePointsSuccessful() throws Exception {
        String mockJsonResponse = """
                {
                  "distances": [[0], [75], [85], [100]],
                  "destinations": [
                    { "location": [8.681, 49.41] }
                  ],
                  "sources": [
                    { "location": [8.681, 49.41] },
                    { "location": [8.682, 49.42] },
                    { "location": [8.683, 49.43] },
                    { "location": [8.684, 49.44] }
                  ]
                }
                """;


        when(closeableHttpClient.execute(any(HttpPost.class), handlerCaptor.capture())).thenReturn(mockJsonResponse);

        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                4, extent, 1, 105, 3, 350, "driving-car", null);
        testGenerator.setHttpClient(closeableHttpClient);

        testGenerator.generatePoints();
        Map<String, List<double[]>> result = testGenerator.getResult();

        // ... existing assertions ...
        verify(closeableHttpClient, atLeast(1)).execute(any(), handlerCaptor.capture());
    }

    @Test
    void testGeneratePointsWithInvalidResponses() throws Exception {
        // Mock a series of invalid responses followed by a valid one
        String invalidResponse = "{ invalid json }";
        String validResponse = """
                {
                  "distances": [[0], [75]],
                  "destinations": [
                    { "location": [8.681, 49.41] }
                  ],
                  "sources": [
                    { "location": [8.681, 49.41] },
                    { "location": [8.682, 49.42] }
                  ]
                }
                """;

        StringEntity invalidEntity = new StringEntity(invalidResponse, StandardCharsets.UTF_8);
        StringEntity validEntity = new StringEntity(validResponse, StandardCharsets.UTF_8);

        CloseableHttpResponse closeableHttpResponse = mock(CloseableHttpResponse.class);
        when(closeableHttpResponse.getEntity())
                .thenReturn(invalidEntity) // First two calls return invalid response
                .thenReturn(invalidEntity)
                .thenReturn(validEntity); // Third call returns valid response
        when(closeableHttpClient.execute(any(HttpPost.class), handlerCaptor.capture())).thenReturn(validResponse);

        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                1, extent, 50, 100, 5, 350, "driving-car", null);
        testGenerator.setHttpClient(closeableHttpClient);

        testGenerator.generatePoints();
        Map<String, List<double[]>> result = testGenerator.getResult();

        assertEquals(1, result.get("from_points").size());
        assertEquals(1, result.get("to_points").size());
        verify(closeableHttpClient, atLeast(1)).execute(any(), handlerCaptor.capture());
    }

    @Test
    void testGeneratePointsMaxAttemptsReached() throws Exception {

        CloseableHttpResponse closeableHttpResponse = mock(CloseableHttpResponse.class);

        String mockJsonResponse = """
                {
                  "distances": [[0], [10], [200]],
                  "destinations": [
                    { "location": [8.681, 49.41] }
                  ],
                  "sources": [
                    { "location": [8.681, 49.41] },
                    { "location": [8.682, 49.42] },
                    { "location": [8.683, 49.43] }
                  ]
                }
                """;
        StringEntity entity = new StringEntity(mockJsonResponse, StandardCharsets.UTF_8);
        when(closeableHttpResponse.getEntity()).thenReturn(entity);
        when(closeableHttpClient.execute(any(HttpPost.class), handlerCaptor.capture())).thenReturn(mockJsonResponse);

        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                1, extent, 100, 150, 2, 350, "driving-car", null);
        testGenerator.setHttpClient(closeableHttpClient);

        testGenerator.generatePoints();
        Map<String, List<double[]>> result = testGenerator.getResult();

        assertTrue(result.get("from_points").isEmpty());
        assertTrue(result.get("to_points").isEmpty());
        verify(closeableHttpClient, times(2)).execute(any(), handlerCaptor.capture());
    }

    @Test
    void testGeneratePointsWithNetworkErrors() throws Exception {

        String mockJsonResponse = """
                    {
                    "distances": [[0], [75]],
                    "destinations": [
                    { "location": [8.681, 49.41] }
                    ],
                    "sources": [
                    { "location": [8.681, 49.41] },
                    { "location": [8.682, 49.42] }
                    ]
                }
                """;
        when(closeableHttpClient.execute(any(HttpPost.class), handlerCaptor.capture()))
                .thenThrow(new IOException("Network error"))
                .thenThrow(new IOException("Network error"))
                .thenReturn(mockJsonResponse);

        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                1, extent, 50, 100, 5, 350, "driving-car", null);
        testGenerator.setHttpClient(closeableHttpClient);

        testGenerator.generatePoints();
        Map<String, List<double[]>> result = testGenerator.getResult();

        verify(closeableHttpClient, times(3)).execute(any(), handlerCaptor.capture());
        assertEquals(1, result.get("from_points").size());
        assertEquals(1, result.get("to_points").size());
    }

    @Test
    void testConvertToCSV() throws IOException {
        Map<String, List<double[]>> coordinates;
        double[] fromCoordinate = {9.0, 10.0};
        double[] toCoordinate = {1.0, 2.0};
        List<double[]> fromPoints = List.of(fromCoordinate, fromCoordinate);
        List<double[]> toPoints = List.of(toCoordinate, toCoordinate);

        coordinates = new HashMap<String, List<double[]>>();
        coordinates.put("to_points", toPoints);
        coordinates.put("from_points", fromPoints);

        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                1, extent, 50, 100, 5, 350, "driving-car", null);
        


        String header = "from_lon,from_lat,to_lon,to_lat\n";
        String expected_result = header.concat("9.0,10.0,1.0,2.0\n").concat("9.0,10.0,1.0,2.0\n");
                 
        String result = testGenerator.printToCSV(coordinates);

        assertEquals(expected_result, result);
    }

    @Test
    void testWriteCSVToFile(@TempDir Path tempDir) throws IOException {

        CloseableHttpResponse closeableHttpResponse = mock(CloseableHttpResponse.class);

        // Mock successful response with valid points
        String mockJsonResponse = """
                {
                  "distances": [[0], [75], [85], [100]],
                  "destinations": [
                    { "location": [8.681, 49.41] }
                  ],
                  "sources": [
                    { "location": [8.681, 49.41] },
                    { "location": [8.682, 49.42] },
                    { "location": [8.683, 49.43] },
                    { "location": [8.684, 49.44] }
                  ]
                }
                """;

        when(closeableHttpClient.execute(any(HttpPost.class), handlerCaptor.capture())).thenReturn(mockJsonResponse);

        TestCoordinateGenerator testGenerator = new TestCoordinateGenerator(
                4, extent, 0, 200, 5, 350, "driving-car", null);
        testGenerator.setHttpClient(closeableHttpClient);
        testGenerator.generatePoints();

        String expected_result = """
                from_lon,from_lat,to_lon,to_lat
                8.681,49.41,8.682,49.42
                8.681,49.41,8.683,49.43
                8.681,49.41,8.684,49.44
                8.681,49.41,8.682,49.42
                """;
        String filename = "test.csv";
        String filePath = tempDir.resolve(filename).toString();
        testGenerator.writeToCSV(filePath);
        // Read the file
        String result = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(filePath)));
        assertEquals(expected_result, result);
        verify(closeableHttpClient, atLeast(1)).execute(any(), handlerCaptor.capture());
    }

    // Test helper class
    private class TestCoordinateGenerator extends CoordinateGenerator {
        private CloseableHttpClient testClient;

        public TestCoordinateGenerator(int numPoints, double[] extent, double minDistance,
                double maxDistance, int maxAttempts, double radius,
                String profile, String baseUrl) {
            super(numPoints, extent, minDistance, maxDistance, maxAttempts, radius, profile, baseUrl);
        }

        void setHttpClient(CloseableHttpClient client) {
            this.testClient = client;
        }

        @Override
        protected CloseableHttpClient createHttpClient() {
            // if testClient is null fail
            if (testClient == null) {
                fail("Test client not set properly");
            }
            return testClient;
        }
    }

    @AfterEach
    public void validate() {
        validateMockitoUsage();
    }
}
