package enterfind.server.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import enterfind.server.BaseDatabaseTest;
import enterfind.server.TestFileUtils;
import enterfind.server.exception.InvalidArgumentException;
import enterfind.server.model.DataSourceStatus;
import enterfind.server.model.FacetValue;
import enterfind.server.model.FieldNameConstants;
import enterfind.server.model.LocalFileSystemDataSource;
import enterfind.server.model.input.SearchConditionInput;
import enterfind.server.scan.Item;
import enterfind.server.scan.Scanner;
import enterfind.server.scan.TreatmentProcessor;
import enterfind.server.scan.Treatments;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class SearchDaoTest extends BaseDatabaseTest {
    @Autowired
    private Scanner scanner;
    @Autowired
    private TreatmentProcessor treatmentProcessor;
    @Autowired
    private SearchDAO searchDao;
    @Autowired
    private DataSourceDao dataSourceDao;
    @Autowired
    private TreatmentDao treatmentDao;
    @Autowired
    private ObjectMapper objectMapper;

    private Path tempTestDir;
    private LocalFileSystemDataSource testDataSource;

    @Override
    protected void setUp() {
        // Override to prevent database wipe before each test
        // since we're using @BeforeAll for one-time setup
    }

    @BeforeAll
    void setThisUp() throws Exception {
        wipeDatabase();  // Clean once before all tests
        tempTestDir = Files.createTempDirectory("searchdao-test-");
        TestFileUtils.copyTestShareToTempDir(tempTestDir);
        
        // Create nested folders for folder path search tests: level1/level2/level3
        Path level1 = tempTestDir.resolve("level1");
        Path level2 = level1.resolve("level2");
        Path level3 = level2.resolve("level3");
        Files.createDirectories(level3);
        Files.writeString(level1.resolve("file_at_level1.txt"), "Content at level 1");
        Files.writeString(level2.resolve("file_at_level2.txt"), "Content at level 2");
        Files.writeString(level3.resolve("file_at_level3.txt"), "Content at level 3");

        // Create folder with comma in name for comma-in-name search tests
        Path commaFolder = tempTestDir.resolve("level1").resolve("Rust Servers, Services, and Apps");
        Files.createDirectories(commaFolder);
        Files.writeString(commaFolder.resolve("comma-file.txt"), "Content in comma folder");
        
        testDataSource = new LocalFileSystemDataSource(
                null,
                "Test Local FS",
                "0 0 * * *",
                LocalDateTime.now(),
                null,
                DataSourceStatus.IDLE,
                tempTestDir.toString(),
                null);
        testDataSource = (LocalFileSystemDataSource) dataSourceDao.create(testDataSource);
        // NAME_AND_PATH is auto-added on datasource creation, add CONTENT treatment for search tests
        treatmentDao.addTreatment(testDataSource.id(), Treatments.CONTENT, objectMapper.createObjectNode());
        scanner.scan(testDataSource);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
    }

    @AfterAll
    void tearDown() throws IOException {
        if (tempTestDir != null && Files.exists(tempTestDir)) {
            TestFileUtils.deleteDirectoryRecursively(tempTestDir);
        }
    }

    /** Facet values rendered as "value:count", since FacetValue.equals ignores the count. */
    private static List<String> renderFacets(List<FacetValue> facets) {
        return facets.stream().map(f -> f.getValue() + ":" + f.getCount()).sorted()
            .collect(Collectors.toList());
    }

    /** Sorted names of a result set, so assertions name the items rather than counting them. */
    private static List<String> names(List<Item> items) {
        return items.stream().map(Item::getName).sorted().collect(Collectors.toList());
    }

    // ==================== Sort Direction Tests ====================

    @Test
    public void testSearchSortDirectionDescending() {
        // Default sort direction is DESC (newest first)
        List<Item> resultsDesc = searchDao.search(List.of(), null, null, null, 0, 100, SortDirection.DESC);
        assertEquals(12, resultsDesc.size());
        
        // Verify items are sorted by time_modified descending
        for (int i = 0; i < resultsDesc.size() - 1; i++) {
            LocalDateTime current = resultsDesc.get(i).getTimeModified();
            LocalDateTime next = resultsDesc.get(i + 1).getTimeModified();
            assertTrue(current.compareTo(next) >= 0, 
                "Items should be sorted by time_modified descending");
        }
    }

    @Test
    public void testSearchSortDirectionAscending() {
        List<Item> resultsAsc = searchDao.search(List.of(), null, null, null, 0, 100, SortDirection.ASC);
        assertEquals(12, resultsAsc.size());
        
        // Verify items are sorted by time_modified ascending
        for (int i = 0; i < resultsAsc.size() - 1; i++) {
            LocalDateTime current = resultsAsc.get(i).getTimeModified();
            LocalDateTime next = resultsAsc.get(i + 1).getTimeModified();
            assertTrue(current.compareTo(next) <= 0, 
                "Items should be sorted by time_modified ascending");
        }
    }

    @Test
    public void testSearchSortDirectionNullDefaultsToDesc() {
        // When sortDirection is null, should default to DESC
        List<Item> resultsNull = searchDao.search(List.of(), null, null, null, 0, 100, null);
        List<Item> resultsDesc = searchDao.search(List.of(), null, null, null, 0, 100, SortDirection.DESC);
        
        assertEquals(resultsNull.size(), resultsDesc.size());
        for (int i = 0; i < resultsNull.size(); i++) {
            assertEquals(resultsNull.get(i).getName(), resultsDesc.get(i).getName(),
                "Null sort direction should behave same as DESC");
        }
    }

    @Test
    public void testSearchOrderIsTotalSoPaginationDoesNotDropOrRepeat() {
        // Most fixture items share the same time_modified, so ordering by that column alone leaves
        // tied rows in whatever order the plan happens to produce — which makes paging through them
        // drop some and repeat others. The ORDER BY carries i.id as a tiebreaker to prevent that.
        List<Item> all = searchDao.search(List.of(), null, null, null, 0, 100, SortDirection.DESC);
        assertTrue(all.size() > 2, "Fixture should have enough items to page through");

        List<String> paged = new ArrayList<>();
        for (int skip = 0; skip < all.size(); skip += 2) {
            searchDao.search(List.of(), null, null, null, skip, 2, SortDirection.DESC)
                .forEach(i -> paged.add(i.getId()));
        }

        assertEquals(all.stream().map(Item::getId).collect(Collectors.toList()), paged,
            "Paging in steps of 2 must yield exactly the unpaged order, with no drops or repeats");
    }

    @Test
    public void testSearchWithNoCriteria() {
        List<Item> results = searchDao.search(List.of(), null, null, null, 0, 100);
        assertEquals(12, results.size(), "Should return all 12 items when no search criteria are present (8 original + 3 nested + 1 comma-folder)");
    }

    @Test
    public void testSearchByFileNameParam() {
        List<Item> results = searchDao.search(List.of(), "text.txt", null, null, 0, 100);
        assertEquals(1, results.size(), "Should find exactly 1 file named text.txt");
    }

    @Test
    public void testSearchByFolderParam() {
        List<Item> results = searchDao.search(List.of(), null, "subf", null, 0, 100);
        assertEquals(2, results.size(), "Should find exactly 2 items in folder 'subf'");
    }

    // ==================== Folder API Parameter Tests (forward-slash format, substring match) ====================

    @Test
    public void testSearchByFolderParam_ForwardSlashFormat() {
        // API uses forward-slash format: "level1/level2" matches items with that path (slash is the separator)
        List<Item> results = searchDao.search(List.of(), null, "level1/level2", null, 0, 100);
        // Should match file_at_level2.txt (path=level1/level2) and file_at_level3.txt (path=level1/level2/level3)
        assertEquals(2, results.size(), "Should find items in level1/level2 and its subfolders");
        assertTrue(results.stream().anyMatch(i -> i.getName().equals("file_at_level2.txt")));
        assertTrue(results.stream().anyMatch(i -> i.getName().equals("file_at_level3.txt")));
    }

    @Test
    public void testSearchByFolderParam_ExactNestedPath() {
        // Search for exact nested path should match only that folder and its children
        List<Item> results = searchDao.search(List.of(), null, "level1/level2/level3", null, 0, 100);
        assertEquals(1, results.size(), "Should find exactly 1 item in level1/level2/level3");
        assertEquals("file_at_level3.txt", results.get(0).getName());
    }

    @Test
    public void testSearchByFolderParam_PartialPathMatchesSubstring() {
        // Search for "level2" should match any path containing "level2"
        List<Item> results = searchDao.search(List.of(), null, "level2", null, 0, 100);
        // Should match items with paths: level1/level2 and level1/level2/level3
        assertEquals(2, results.size(), "Should find items where path contains 'level2'");
    }

    @Test
    public void testSearchByFolderParam_Level1MatchesAllNested() {
        // Search for "level1" should match all items under level1 hierarchy (including comma-folder file)
        List<Item> results = searchDao.search(List.of(), null, "level1", null, 0, 100);
        assertEquals(4, results.size(), "Should find all 4 items under level1 hierarchy");
    }

    @Test
    public void testSearchByFolderParam_NoMatchForNonExistentPath() {
        List<Item> results = searchDao.search(List.of(), null, "nonexistent/path", null, 0, 100);
        assertEquals(0, results.size(), "Should find no items for non-existent path");
    }

    // ==================== Path Search Condition Tests (KVP match, individual path component) ====================

    @Test
    public void testSearchByPathCondition_MatchesSingleComponent() {
        // Search condition with path key should match individual path components stored in KVP table
        // The NAME_AND_PATH treatment stores each path component as a separate KVP entry with key="path"
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("path");
        cond.setOperation(SearchOperation.EQUAL);
        cond.setValue("level2");
        List<SearchConditionInput> conditions = List.of(cond);
        List<Item> results = searchDao.search(conditions, null, null, null, 0, 100);
        // Items with path containing "level2" as a component: level1/level2 and level1/level2/level3
        assertEquals(2, results.size(), "Should find items with 'level2' path component in KVP");
    }

    @Test
    public void testSearchByPathCondition_MatchesLevel1Component() {
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("path");
        cond.setOperation(SearchOperation.EQUAL);
        cond.setValue("level1");
        List<SearchConditionInput> conditions = List.of(cond);
        List<Item> results = searchDao.search(conditions, null, null, null, 0, 100);
        // All items under level1 have "level1" as a path component (includes comma-folder file)
        assertEquals(4, results.size(), "Should find all 4 items with 'level1' path component");
    }

    @Test
    public void testSearchByPathCondition_LikeOperationOnComponent() {
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("path");
        cond.setOperation(SearchOperation.LIKE);
        cond.setValue("%level1%");  // FTS searches for complete word "level1"
        List<SearchConditionInput> conditions = List.of(cond);
        List<Item> results = searchDao.search(conditions, null, null, null, 0, 100);
        // All nested items have path components containing "level1" as a word (includes comma-folder file)
        assertEquals(4, results.size(), "Should find all items with path components containing 'level1'");
    }

    @Test
    public void testSearchByPathCondition_NoMatchForFullPath() {
        // Search condition matches KVP entries which store individual path components, not full paths
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("path");
        cond.setOperation(SearchOperation.EQUAL);
        cond.setValue("level1/level2");  // This is NOT how it's stored in KVP - components are stored individually
        List<SearchConditionInput> conditions = List.of(cond);
        List<Item> results = searchDao.search(conditions, null, null, null, 0, 100);
        assertEquals(0, results.size(), "Should not match full slash-path since KVP stores components individually");
    }

    @Test
    public void testSearchByPathCondition_SubfFolder() {
        // Test that subf folder items have "subf" as path component
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("path");
        cond.setOperation(SearchOperation.EQUAL);
        cond.setValue("subf");
        List<SearchConditionInput> conditions = List.of(cond);
        List<Item> results = searchDao.search(conditions, null, null, null, 0, 100);
        assertEquals(2, results.size(), "Should find 2 items with 'subf' path component");
    }

    // ==================== Combined API Folder + Search Condition Tests ====================

    @Test
    public void testSearchByFolderParamAndPathCondition_Combined() {
        // API folder param (path substring) combined with search condition (KVP component)
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("path");
        cond.setOperation(SearchOperation.EQUAL);
        cond.setValue("level3");
        List<SearchConditionInput> conditions = List.of(cond);
        // folder="level1/level2" narrows to level2+ paths, condition narrows to level3 component
        List<Item> results = searchDao.search(conditions, null, "level1/level2", null, 0, 100);
        assertEquals(1, results.size(), "Should find only item at level3");
        assertEquals("file_at_level3.txt", results.get(0).getName());
    }

    @Test
    public void testSearchByFolderParam_DifferenceFromPathCondition() {
        // Demonstrate the difference: folder param matches path substring, condition matches KVP component
        
        // Folder param "level2/level3" - matches items where i.path contains "level2/level3"
        List<Item> folderResults = searchDao.search(List.of(), null, "level2/level3", null, 0, 100);
        assertEquals(1, folderResults.size(), "Folder param should match path substring 'level2/level3'");
        
        // Path condition "level3" - matches items that have "level3" as a path component in KVP
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("path");
        cond.setOperation(SearchOperation.EQUAL);
        cond.setValue("level3");
        List<Item> conditionResults = searchDao.search(List.of(cond), null, null, null, 0, 100);
        assertEquals(1, conditionResults.size(), "Path condition should match KVP component 'level3'");
        
        // Both should return the same file in this case
        assertEquals(folderResults.get(0).getName(), conditionResults.get(0).getName());
    }

    @Test
    public void testSearchByFileNameCondition() {
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("name");
        cond.setOperation(SearchOperation.LIKE);
        cond.setValue("%text.txt%");
        List<SearchConditionInput> conditions = List.of(cond);
        List<Item> results = searchDao.search(conditions, null, null, null, 0, 100);
        assertEquals(1, results.size(), "Should find exactly 1 file matching name pattern %text.txt%");
    }

    @Test
    public void testSearchByContentCondition() {
        // "Distributed systems" is AND-of-words (plainto_tsquery), not phrase-adjacent, so on its own
        // this would also match the USGS report, which separately contains "distributed" and
        // "systems". Scope to the resume by name (same pattern as testCountByNameAndFolderAndCondition)
        // to keep this an exact-uniqueness assertion rather than loosening the expected count.
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("content");
        cond.setOperation(SearchOperation.LIKE);
        cond.setValue("%Distributed systems%");
        List<SearchConditionInput> conditions = List.of(cond);
        List<Item> results = searchDao.search(conditions, "Resume.pdf", null, null, 0, 100);
        assertEquals(1, results.size(), "Should find exactly 1 item containing 'Distributed systems'");
        assertTrue(results.get(0).getName().contains("Mikhail"));
    }

    @Test
    public void testLikeUsesSubstringOnMetadataAndWordsOnContent() {
        // LIKE is NOT uniformly word-based: the table split routes metadata fields to ILIKE on
        // ITEM_TREATMENT_RESULTS (character granularity) and content fields to FTS on
        // ITEM_TREATMENT_RESULTS_CONTENT.value_tsv (whole-word granularity).

        // Test 1: Metadata field (path) matches on characters, not tokens. "evel1" is an infix of
        // "level1" and is not a token anywhere, so it can only match if metadata uses ILIKE.
        SearchConditionInput pathCond = new SearchConditionInput();
        pathCond.setName("path");
        pathCond.setOperation(SearchOperation.LIKE);
        pathCond.setValue("evel1");
        List<Item> pathResults = searchDao.search(List.of(pathCond), null, null, null, 0, 100);
        assertEquals(
            List.of("comma-file.txt", "file_at_level1.txt", "file_at_level2.txt", "file_at_level3.txt"),
            names(pathResults),
            "LIKE on metadata (path) is a substring match, so the infix 'evel1' should match every "
            + "item with a 'level1' path component");

        // Test 2: Content field matches whole words only. The same infix trick must find nothing.
        SearchConditionInput contentInfix = new SearchConditionInput();
        contentInfix.setName("content");
        contentInfix.setOperation(SearchOperation.LIKE);
        contentInfix.setValue("istributed");
        List<Item> infixResults = searchDao.search(List.of(contentInfix), null, null, null, 0, 100);
        assertEquals(List.of(), names(infixResults),
            "LIKE on content is word-based, so the infix 'istributed' must not match 'Distributed'");

        // Test 3: ...while the whole word does match on content.
        SearchConditionInput contentCond = new SearchConditionInput();
        contentCond.setName("content");
        contentCond.setOperation(SearchOperation.LIKE);
        contentCond.setValue("Distributed");
        List<Item> contentResults = searchDao.search(List.of(contentCond), null, null, null, 0, 100);
        assertEquals(List.of("5487581_Mikhail_Garber_5644739_202009291103_Resume.pdf", "USGS Mineral Commodity Summaries 2025 (Abrasives-Copper).pdf"), names(contentResults),
            "LIKE on content should find exactly the two documents whose text contains the "
            + "complete word 'Distributed'");
    }

    @Test
    public void testSearchByNameAndFolderAndCondition() {
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("content");
        cond.setOperation(SearchOperation.LIKE);
        cond.setValue("%Distributed systems%");
        List<SearchConditionInput> conditions = List.of(cond);
        // Resume file is likely in root folder and contains the expected content
        List<Item> results = searchDao.search(conditions, "Resume.pdf", null, null, 0, 100);
        assertEquals(1, results.size(), "Should find exactly 1 resume file matching name and content condition");
        assertTrue(results.get(0).getName().contains("Resume.pdf"));
    }

    @Test
    public void testSearchBySearchString() {
        // searchString should match across all field values.
        // Unquoted "Distributed systems" is AND-of-words, not phrase-adjacent, so on its own this
        // would also match the USGS report, which separately contains "distributed" and "systems".
        // Scope to the resume by name to keep this an exact-uniqueness assertion.
        List<Item> results = searchDao.search(List.of(), "Resume.pdf", null, "Distributed systems", 0, 100);
        assertEquals(1, results.size(), "Should find exactly 1 item with 'Distributed systems' in any field");
        assertTrue(results.get(0).getName().contains("Mikhail"));
    }

    @Test
    public void testSearchWithEqualOperation() {
        // Test EQUAL operation - should find exact match for name
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("name");
        cond.setOperation(SearchOperation.EQUAL);
        cond.setValue("text.txt");
        List<SearchConditionInput> conditions = List.of(cond);
        List<Item> results = searchDao.search(conditions, null, null, null, 0, 100);
        assertEquals(1, results.size(), "Should find exactly 1 file with exact name 'text.txt'");
        assertEquals("text.txt", results.get(0).getName());
    }

    @Test
    public void testSearchWithNotEqualOperation() {
        // Test NOT_EQUAL operation - should find all items except the exact match
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("name");
        cond.setOperation(SearchOperation.NOT_EQUAL);
        cond.setValue("text.txt");
        List<SearchConditionInput> conditions = List.of(cond);
        List<Item> results = searchDao.search(conditions, null, null, null, 0, 100);
        // Should find items, but none should be named "text.txt"
        assertTrue(results.size() > 0, "Should find items with names not equal to 'text.txt'");
        for (Item item : results) {
            assertFalse("text.txt".equals(item.getName()),
                "Item name '" + item.getName() + "' should not be 'text.txt'");
        }
    }

    @Test
    public void testSearchWithNotLikeOperation() {
        // The percent wrappers are stripped server-side; conditions stored before the UI stopped
        // adding them still arrive in this shape, so keep exercising it here.
        assertEquals(allNamesExcept("5487581_Mikhail_Garber_5644739_202009291103_Resume.pdf", "USGS Mineral Commodity Summaries 2025 (Abrasives-Copper).pdf"),
            names(searchDao.search(List.of(cond("content", SearchOperation.NOT_LIKE,
                "%Distributed systems%")), null, null, null, 0, 100)),
            "NOT_LIKE on content excludes exactly the two documents holding both lexemes");
    }

    @Test
    public void testSearchWithLessOperation() {
        // Test LESS operation - string comparison
        // Names starting with letters before 'o' should be found when searching for name < "ocrable"
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("name");
        cond.setOperation(SearchOperation.LESS);
        cond.setValue("ocrable");
        List<SearchConditionInput> conditions = List.of(cond);
        List<Item> results = searchDao.search(conditions, null, null, null, 0, 100);
        // All results should have names that are alphabetically less than "ocrable"
        assertTrue(results.size() > 0, "Should find items with names less than 'ocrable'");
        for (Item item : results) {
            assertTrue(item.getName().compareTo("ocrable") < 0, 
                "Item name '" + item.getName() + "' should be alphabetically less than 'ocrable'");
        }
    }

    @Test
    public void testSearchWithGreaterOperation() {
        // Test GREATER operation - string comparison
        // Names starting with letters after 'o' should be found when searching for name > "ocrable"
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("name");
        cond.setOperation(SearchOperation.GREATER);
        cond.setValue("ocrable");
        List<SearchConditionInput> conditions = List.of(cond);
        List<Item> results = searchDao.search(conditions, null, null, null, 0, 100);
        // All results should have names that are alphabetically greater than "ocrable"
        assertTrue(results.size() > 0, "Should find items with names greater than 'ocrable'");
        for (Item item : results) {
            assertTrue(item.getName().compareTo("ocrable") > 0, 
                "Item name '" + item.getName() + "' should be alphabetically greater than 'ocrable'");
        }
    }

    @Test
    public void testSearchOperationsAreExclusive() {
        // Verify LESS and GREATER operations don't overlap (exclude the boundary value)
        SearchConditionInput lessCond = new SearchConditionInput();
        lessCond.setName("name");
        lessCond.setOperation(SearchOperation.LESS);
        lessCond.setValue("ocrable.pdf");
        
        SearchConditionInput greaterCond = new SearchConditionInput();
        greaterCond.setName("name");
        greaterCond.setOperation(SearchOperation.GREATER);
        greaterCond.setValue("ocrable.pdf");
        
        SearchConditionInput equalCond = new SearchConditionInput();
        equalCond.setName("name");
        equalCond.setOperation(SearchOperation.EQUAL);
        equalCond.setValue("ocrable.pdf");
        
        List<Item> lessResults = searchDao.search(List.of(lessCond), null, null, null, 0, 100);
        List<Item> greaterResults = searchDao.search(List.of(greaterCond), null, null, null, 0, 100);
        List<Item> equalResults = searchDao.search(List.of(equalCond), null, null, null, 0, 100);
        List<Item> allResults = searchDao.search(List.of(), null, null, null, 0, 100);
        
        // LESS + GREATER + EQUAL should account for all items (if the boundary value exists)
        // or LESS + GREATER should equal all items (if boundary doesn't exist)
        int totalFromOperations = lessResults.size() + greaterResults.size() + equalResults.size();
        assertEquals(allResults.size(), totalFromOperations, 
            "LESS + GREATER + EQUAL results should equal total items count");
    }

    @Test
    public void testSearchStringWithMultipleMatches() {
        // Search for "pdf" which should appear in content of multiple files
        List<Item> results = searchDao.search(List.of(), null, null, "pdf", 0, 100);
        assertTrue(results.size() > 0, "Should find items containing 'pdf' in any field");
    }

    // ==================== Search String Phrase Search Tests ====================

    @Test
    public void testSearchStringMultipleWords_AND() {
        // Multiple unquoted words are ANDed across the item: each word may be satisfied from
        // either table, independently of where the others were found.
        //
        // comma-file.txt is the case that makes this concrete. It carries "level" in its path
        // (metadata) and "Content" in its extracted text (content), so neither table satisfies the
        // whole query on its own. Folding the string into one query per table, as this used to,
        // searched metadata for the literal substring "Content level" and missed it.
        List<Item> results = searchDao.search(List.of(), null, null, "Content level", 0, 100);
        assertEquals(
            List.of("USGS Mineral Commodity Summaries 2025 (Abrasives-Copper).pdf", "comma-file.txt",
                    "file_at_level1.txt", "file_at_level2.txt", "file_at_level3.txt"),
            names(results),
            "The 3 level files and the USGS report carry both words in content; comma-file.txt "
            + "carries 'level' in metadata and 'Content' in content, and matches only because a "
            + "term may be satisfied from either table");
    }

    // ---- Per-term searchString: quoting, short terms, and cross-table AND ----
    //
    // "Windows Photo Editor" is the EXIF software tag on one JPEG. It lives in metadata only —
    // no content row carries those words — which makes it a clean probe for the metadata half.

    @Test
    public void testQuotedSearchStringMatchesMetadataPhrase() {
        // Previously the quote characters reached ILIKE intact, so the metadata half looked for a
        // value literally containing '"', and a quoted phrase could never match metadata at all.
        List<Item> results = searchDao.search(List.of(), null, null, "\"Windows Photo Editor\"", 0, 100);
        assertEquals(List.of("beam.jpg"), names(results),
            "A quoted phrase must match the item whose EXIF software tag contains it; no content "
            + "row carries these words, so this can only succeed via the metadata half");
    }

    @Test
    public void testQuotedSearchStringRequiresAdjacencyInMetadata() {
        // Quoting means contiguous and in order. The same three words are all present on the item,
        // so a word-AND reading would match; a phrase reading must not.
        List<Item> reordered = searchDao.search(List.of(), null, null, "\"Photo Windows\"", 0, 100);
        assertEquals(List.of(), names(reordered),
            "A quoted phrase is contiguous and ordered, so reordering its words must not match");

        List<Item> unquoted = searchDao.search(List.of(), null, null, "Windows Photo Editor", 0, 100);
        assertEquals(List.of("beam.jpg"), names(unquoted),
            "The same words unquoted are ANDed, not sequenced, and still match beam.jpg");
    }

    @Test
    public void testShortTermMatchesContentOnlyNotMetadata() {
        // pg_trgm extracts no trigram from a 1-2 character pattern, and the planner still picks the
        // index, which then returns the whole table for a full heap recheck — 13 s on the beta
        // corpus. Such a term is not sent to the metadata table, and matches document text only.
        //
        // "xe" is a substring of 4 items' metadata values ("pixels") and is not a prefix of any
        // content token, so under the guard it must find nothing.
        List<Item> twoChars = searchDao.search(List.of(), null, null, "xe", 0, 100);
        assertEquals(List.of(), names(twoChars),
            "A 2-character term must not reach the metadata half, despite matching 4 items there");

        // The control: one character longer, the same kind of infix, and the metadata half runs.
        // This pins the cutoff at the trigram length rather than at some property of the term.
        List<Item> threeChars = searchDao.search(List.of(), null, null, "xel", 0, 100);
        assertEquals(List.of("98594ed9-8559-4607-a0c0-b4ce7df43a26-2020-10-06T15_00_00.000Z_4.jpeg", "beam.jpg", "ocrable.jpg"), names(threeChars),
            "A 3-character term yields trigrams, so the metadata half runs and matches the images "
            + "whose 'image_width'/'image_height' values read '... pixels'");
    }

    @Test
    public void testSearchStringTermIsIntersectedWithLikeCondition() {
        // Each per-term subquery is a UNION and they are joined by INTERSECT, which binds tighter.
        // Unparenthesised, "A UNION B INTERSECT C UNION D" parses as "A UNION (B INTERSECT C) UNION
        // D" — wrong results, no error. A multi-term searchString combined with a LIKE condition is
        // where that mis-parse would show.
        SearchConditionInput nameCond = new SearchConditionInput();
        nameCond.setName("name");
        nameCond.setOperation(SearchOperation.LIKE);
        nameCond.setValue("comma");

        List<Item> results = searchDao.search(List.of(nameCond), null, null, "Content level", 0, 100);
        assertEquals(List.of("comma-file.txt"), names(results),
            "The two searchString terms and the LIKE condition must all be ANDed together");
    }

    @Test
    public void testPercentOnlySearchStringContributesNoTerm() {
        // validateSearchString does not block '%', and a term that reduces to nothing after the
        // strip would build ILIKE '%%' — satisfied by every row carrying any value.
        assertEquals(names(searchDao.search(List.of(), null, null, null, 0, 100)),
            names(searchDao.search(List.of(), null, null, "%", 0, 100)),
            "A searchString of just '%' contributes no term and should return the same items as none");
    }

    // ---- LIKE honours words and quotes ----
    //
    // beam.jpg carries exif_ifd0_software = "Windows Photo Editor 10.0.10011.16384". "Windows" and
    // "Editor" sit in that one value but are not adjacent, which is the shape multi-word metadata
    // LIKE is meant to reach: tool names with version suffixes and trademark symbols, and
    // underscore-separated filenames.

    private static SearchConditionInput cond(String name, SearchOperation op, String value) {
        SearchConditionInput c = new SearchConditionInput();
        c.setName(name);
        c.setOperation(op);
        c.setValue(value);
        return c;
    }

    @Test
    public void testMultiWordMetadataLikeMatchesNonAdjacentWords() {
        // Contiguity is no longer required: both words must appear in the value, in any order and
        // with anything between them.
        assertEquals(List.of("beam.jpg"),
            names(searchDao.search(List.of(cond("exif_ifd0_software", SearchOperation.LIKE,
                "Windows Editor")), null, null, null, 0, 100)),
            "'Windows' and 'Editor' are both in the EXIF software value, separated by 'Photo'");

        // Order does not matter either.
        assertEquals(List.of("beam.jpg"),
            names(searchDao.search(List.of(cond("exif_ifd0_software", SearchOperation.LIKE,
                "Editor Windows")), null, null, null, 0, 100)),
            "Unquoted words are ANDed, not sequenced");
    }

    @Test
    public void testMultiWordMetadataLikeRequiresBothWordsInOneValue() {
        // The words are ANDed within a row, not across the item. "beam" is in the name and
        // "Windows" in the EXIF software tag — both on beam.jpg, but in different keys.
        assertEquals(List.of(),
            names(searchDao.search(List.of(cond("name", SearchOperation.LIKE, "beam Windows")),
                null, null, null, 0, 100)),
            "A condition names one key, so its words must share one value — this is the scope "
            + "difference from searchString, which spans the whole item");
    }

    @Test
    public void testQuotedMetadataLikeRestoresContiguity() {
        assertEquals(List.of("beam.jpg"),
            names(searchDao.search(List.of(cond("exif_ifd0_software", SearchOperation.LIKE,
                "\"Windows Photo\"")), null, null, null, 0, 100)),
            "A quoted phrase that is contiguous in the value matches");

        assertEquals(List.of(),
            names(searchDao.search(List.of(cond("exif_ifd0_software", SearchOperation.LIKE,
                "\"Windows Editor\"")), null, null, null, 0, 100)),
            "The same two words quoted are not contiguous, so quoting recovers exactly the strict "
            + "behaviour that unquoted LIKE gives up");
    }

    @Test
    public void testShortWordInLikeValueIsNotDropped() {
        // searchString drops 1-2 character terms before they reach the metadata table. A LIKE
        // condition must NOT: it always carries key = ? and ANDs its words within one row, which
        // gives the planner a usable path either way.
        //
        // The three JPEGs carry data_precision = "8 bits". If the one-character term were dropped,
        // "9 bits" would match them just as "8 bits" does.
        assertEquals(List.of("98594ed9-8559-4607-a0c0-b4ce7df43a26-2020-10-06T15_00_00.000Z_4.jpeg", "beam.jpg", "ocrable.jpg"),
            names(searchDao.search(List.of(cond("data_precision", SearchOperation.LIKE, "8 bits")),
                null, null, null, 0, 100)),
            "Both terms apply, and the value is '8 bits'");

        assertEquals(List.of(),
            names(searchDao.search(List.of(cond("data_precision", SearchOperation.LIKE, "9 bits")),
                null, null, null, 0, 100)),
            "The one-character term must still be applied, or this would match '8 bits'");
    }

    @Test
    public void testUnquotedContentLikeIsUnchanged() {
        // Content LIKE does not move in this step. plainto_tsquery(a) && plainto_tsquery(b) is
        // byte-for-byte plainto_tsquery("a b"), so the emitted query is identical to before.
        assertEquals(List.of("5487581_Mikhail_Garber_5644739_202009291103_Resume.pdf", "USGS Mineral Commodity Summaries 2025 (Abrasives-Copper).pdf"),
            names(searchDao.search(List.of(cond("content", SearchOperation.LIKE,
                "Distributed systems")), null, null, null, 0, 100)),
            "Unquoted content LIKE stays an AND of lexemes with no order or adjacency");
    }

    @Test
    public void testQuotedContentLikeMatchesPhrase() {
        // Quoting a content condition is a new capability: phrase matching was not reachable
        // through a structured condition at all, since plainto_tsquery discards quotes.
        assertEquals(List.of("5487581_Mikhail_Garber_5644739_202009291103_Resume.pdf"),
            names(searchDao.search(List.of(cond("content", SearchOperation.LIKE,
                "\"Distributed systems\"")), null, null, null, 0, 100)),
            "The phrase is contiguous only in the resume; the USGS report has both words apart");

        assertEquals(List.of(),
            names(searchDao.search(List.of(cond("content", SearchOperation.LIKE,
                "\"systems Distributed\"")), null, null, null, 0, 100)),
            "A phrase is ordered, so reversing it must not match");
    }

    @Test
    public void testMixedLikeValueOnBothFieldClasses() {
        // A value may mix bare words and quoted phrases; both classes parse it the same way.
        assertEquals(List.of("beam.jpg"),
            names(searchDao.search(List.of(cond("exif_ifd0_software", SearchOperation.LIKE,
                "Editor \"Windows Photo\"")), null, null, null, 0, 100)),
            "Metadata: ILIKE '%Editor%' AND ILIKE '%Windows Photo%'");

        assertEquals(List.of("5487581_Mikhail_Garber_5644739_202009291103_Resume.pdf"),
            names(searchDao.search(List.of(cond("content", SearchOperation.LIKE,
                "Java \"Distributed systems\"")), null, null, null, 0, 100)),
            "Content: plainto_tsquery('Java') && phraseto_tsquery('Distributed systems')");
    }

    // ---- NOT_LIKE ----
    //
    // NOT_LIKE compiles to NOT EXISTS around the same value predicate as LIKE, so it moves in the
    // opposite direction: narrowing the predicate widens what satisfies the negation. It is also
    // the only operation that reaches the non-FTS path on its own — NOT_LIKE alone triggers neither
    // branch of hasFTSSearch — so it is the one place where two routes must agree.

    /** Every item in the fixture except the named ones, sorted. */
    private List<String> allNamesExcept(String... excluded) {
        List<String> exclude = List.of(excluded);
        return names(searchDao.search(List.of(), null, null, null, 0, 100)).stream()
            .filter(n -> !exclude.contains(n)).collect(Collectors.toList());
    }

    @Test
    public void testNotLikeAgreesAcrossItsTwoCodePaths() {
        // Alone, NOT_LIKE runs through buildWhereClause; combined with anything FTS it runs through
        // the CTE path instead. Those were two copies of the same semantics that had to be kept in
        // step by hand; they are now one helper, and this pins that they agree.
        SearchConditionInput notLike =
            cond("exif_ifd0_software", SearchOperation.NOT_LIKE, "Windows Editor");
        SearchConditionInput like = cond("content", SearchOperation.LIKE, "Distributed");

        List<String> notLikeAlone = names(searchDao.search(List.of(notLike), null, null, null, 0, 100));
        List<String> likeAlone = names(searchDao.search(List.of(like), null, null, null, 0, 100));
        List<String> combined = names(searchDao.search(List.of(notLike, like), null, null, null, 0, 100));

        List<String> intersection = likeAlone.stream()
            .filter(notLikeAlone::contains).collect(Collectors.toList());
        assertEquals(intersection, combined,
            "The two routes must produce the same NOT_LIKE semantics: combining it with a LIKE "
            + "must give exactly the intersection of the two run separately");
        assertEquals(List.of("5487581_Mikhail_Garber_5644739_202009291103_Resume.pdf", "USGS Mineral Commodity Summaries 2025 (Abrasives-Copper).pdf"), combined);
    }

    @Test
    public void testMultiWordMetadataNotLikeIsExactNegation() {
        // The exact complement of testMultiWordMetadataLikeMatchesNonAdjacentWords.
        assertEquals(allNamesExcept("beam.jpg"),
            names(searchDao.search(List.of(cond("exif_ifd0_software", SearchOperation.NOT_LIKE,
                "Windows Editor")), null, null, null, 0, 100)),
            "NOT_LIKE must exclude exactly the item its LIKE counterpart matches");
    }

    @Test
    public void testQuotedMetadataNotLikeWidensTheNegation() {
        // Quoting narrows the predicate — the words must now be contiguous — so fewer items are
        // excluded and MORE satisfy the negation. This is the direction stored NOT_LIKE conditions
        // move in, opposite to their LIKE counterparts.
        assertEquals(allNamesExcept(),
            names(searchDao.search(List.of(cond("exif_ifd0_software", SearchOperation.NOT_LIKE,
                "\"Windows Editor\"")), null, null, null, 0, 100)),
            "'Windows Editor' is not contiguous in the value, so nothing is excluded at all");
    }

    @Test
    public void testContentNotLikeQuotedVsUnquoted() {
        // Unquoted excludes both documents carrying the two words anywhere.
        assertEquals(allNamesExcept("5487581_Mikhail_Garber_5644739_202009291103_Resume.pdf", "USGS Mineral Commodity Summaries 2025 (Abrasives-Copper).pdf"),
            names(searchDao.search(List.of(cond("content", SearchOperation.NOT_LIKE,
                "Distributed systems")), null, null, null, 0, 100)),
            "Unquoted content NOT_LIKE excludes every document holding both lexemes");

        // Quoted excludes only the document where they are adjacent.
        assertEquals(allNamesExcept("5487581_Mikhail_Garber_5644739_202009291103_Resume.pdf"),
            names(searchDao.search(List.of(cond("content", SearchOperation.NOT_LIKE,
                "\"Distributed systems\"")), null, null, null, 0, 100)),
            "Quoted content NOT_LIKE excludes only the document with the contiguous phrase, so "
            + "the USGS report comes back");

        // Reversed, the phrase matches nothing, so nothing is excluded.
        assertEquals(allNamesExcept(),
            names(searchDao.search(List.of(cond("content", SearchOperation.NOT_LIKE,
                "\"systems Distributed\"")), null, null, null, 0, 100)),
            "A phrase is ordered, so the reversed phrase excludes nothing");
    }

    @Test
    public void testNotLikeIsSatisfiedByItemsMissingTheKeyEntirely() {
        // A documented limit, not an accident: NOT_LIKE compiles to NOT EXISTS, so it is satisfied
        // by an item that simply does not carry the key — not only by one carrying it with a
        // non-matching value. Only beam.jpg has exif_ifd0_software at all.
        assertEquals(allNamesExcept(),
            names(searchDao.search(List.of(cond("exif_ifd0_software", SearchOperation.NOT_LIKE,
                "zzzznotpresent")), null, null, null, 0, 100)),
            "Every item satisfies this: beam.jpg because its value does not match, and the other "
            + "eleven because they have no exif_ifd0_software row at all");
    }

    @Test
    public void testShortWordInNotLikeValueIsNotDropped() {
        // The mirror of testShortWordInLikeValueIsNotDropped. The three JPEGs carry
        // data_precision = "8 bits"; if the one-character term were dropped, "9 bits" would exclude
        // them just as "8 bits" does.
        assertEquals(allNamesExcept("98594ed9-8559-4607-a0c0-b4ce7df43a26-2020-10-06T15_00_00.000Z_4.jpeg", "beam.jpg", "ocrable.jpg"),
            names(searchDao.search(List.of(cond("data_precision", SearchOperation.NOT_LIKE,
                "8 bits")), null, null, null, 0, 100)),
            "'8 bits' matches the three JPEGs, so the negation excludes exactly them");

        assertEquals(allNamesExcept(),
            names(searchDao.search(List.of(cond("data_precision", SearchOperation.NOT_LIKE,
                "9 bits")), null, null, null, 0, 100)),
            "The one-character term must still be applied, or this would exclude the JPEGs too");
    }

    @Test
    public void testNotLikeCountMatchesSearchResults() {
        // countSearchResults builds its own SQL. NOT_LIKE is the operation most likely to drift
        // between the two, since it is the one that can land on either path.
        for (SearchConditionInput c : List.of(
                cond("content", SearchOperation.NOT_LIKE, "Distributed systems"),
                cond("content", SearchOperation.NOT_LIKE, "\"Distributed systems\""),
                cond("exif_ifd0_software", SearchOperation.NOT_LIKE, "Windows Editor"),
                cond("data_precision", SearchOperation.NOT_LIKE, "8 bits"))) {
            List<String> found = names(searchDao.search(List.of(c), null, null, null, 0, 100));
            assertEquals(found.size(), searchDao.countSearchResults(List.of(c), null, null, null),
                "Count must match the search for NOT_LIKE " + c.getName() + "=" + c.getValue()
                + ", which returned " + found);
        }
    }

    @Test
    public void testSearchStringQuotedPhrase() {
        // Quoted words should use phrase search (exact sequence)
        List<Item> results = searchDao.search(List.of(), null, null, "\"Content at\"", 0, 100);
        // Should find files containing the exact phrase "Content at"
        assertEquals(3, results.size(), "Should find all 3 level files containing the phrase 'Content at'");
    }

    @Test
    public void testSearchStringMixedQuotedAndUnquoted() {
        // Mix of quoted phrase and unquoted words
        List<Item> results = searchDao.search(List.of(), null, null, "\"Content at\" level", 0, 100);
        // Should find files containing the phrase "Content at" AND the word "level"
        assertEquals(3, results.size(), "Should find all 3 level files with phrase 'Content at' and word 'level'");
    }

    @Test
    public void testSearchStringMultipleQuotedPhrases() {
        // Multiple quoted phrases should be ANDed
        List<Item> results = searchDao.search(List.of(), null, null, "\"Content at\" \"level 1\"", 0, 100);
        // Should find files containing both phrases "Content at" and "level 1"
        assertEquals(1, results.size(), "Should find exactly 1 file containing both phrases");
        assertEquals("file_at_level1.txt", results.get(0).getName());
    }

    @Test
    public void testSearchStringEmptyQuotes() {
        // Empty quotes should be ignored
        List<Item> results = searchDao.search(List.of(), null, null, "\"\" content", 0, 100);
        // Should behave like searching for just "content"
        assertNotNull(results, "Empty quotes should be handled gracefully");
    }

    // A searchString that is non-empty but contributes no terms must not reach the FTS path: the
    // CTE would be built as "WITH fts_matches AS ( )", which is not valid SQL. All four entry
    // points share the same guard, so all four are covered here.

    @Test
    public void testSearchStringWhitespaceOnlyBuildsValidSql() {
        List<Item> results = searchDao.search(List.of(), null, null, "   ", 0, 100);
        assertEquals(names(searchDao.search(List.of(), null, null, null, 0, 100)), names(results),
            "A whitespace-only searchString should return the same items as no searchString at all");
    }

    @Test
    public void testSearchStringOnlyEmptyQuotesBuildsValidSql() {
        List<Item> results = searchDao.search(List.of(), null, null, "\"\"", 0, 100);
        assertEquals(names(searchDao.search(List.of(), null, null, null, 0, 100)), names(results),
            "A searchString of just empty quotes should return the same items as no searchString");
    }

    @Test
    public void testCountWithTermlessSearchStringBuildsValidSql() {
        // countSearchResults returns a bare number, so anchor it to the items it is counting:
        // the count must equal the result set the equivalent search names.
        List<String> expected = names(searchDao.search(List.of(), null, null, null, 0, 100));
        assertEquals(expected.size(), searchDao.countSearchResults(List.of(), null, null, "   "),
            "Count with a whitespace-only searchString should count exactly " + expected);
        assertEquals(expected.size(), searchDao.countSearchResults(List.of(), null, null, "\"\""),
            "Count with an empty-quotes searchString should count exactly " + expected);
        assertEquals(expected, names(searchDao.search(List.of(), null, null, "   ", 0, 100)),
            "...and the search behind that count should return those same items");
    }

    @Test
    public void testFacetsAndKeysWithTermlessSearchStringBuildValidSql() {
        // FacetValue.equals ignores count, so compare a rendering that carries it.
        assertEquals(renderFacets(searchDao.getFacetValues("content-type", List.of(), null, null, null)),
            renderFacets(searchDao.getFacetValues("content-type", List.of(), null, null, "   ")),
            "Facets with a whitespace-only searchString should match facets with none, counts included");
        assertEquals(searchDao.getAvailableItemAttributeKeys(List.of(), null, null, null),
            searchDao.getAvailableItemAttributeKeys(List.of(), null, null, "\"\""),
            "Key discovery with an empty-quotes searchString should return the same keys as none");
    }

    @Test
    public void testSearchWithMultipleConditions() {
        // This test verifies that multiple search conditions work correctly
        // Each condition should be matched against different rows in ITEM_TREATMENT_RESULTS
        // Bug fix: Previously all conditions were put in a single EXISTS subquery,
        // which would fail because a single row can't match multiple different keys
        
        // Create two conditions: name and path (which are different keys in ITEM_TREATMENT_RESULTS)
        SearchConditionInput nameCond = new SearchConditionInput();
        nameCond.setName("name");
        nameCond.setOperation(SearchOperation.LIKE);
        nameCond.setValue("%Mineral%");  // FTS match for "Mineral"

        SearchConditionInput typeCond = new SearchConditionInput();
        typeCond.setName("name");
        typeCond.setOperation(SearchOperation.LIKE);
        typeCond.setValue("%Commodity%");  // FTS match for "Commodity"

        List<SearchConditionInput> conditions = List.of(nameCond, typeCond);
        List<Item> results = searchDao.search(conditions, null, null, null, 0, 100);

        // Should find "USGS Mineral Commodity Summaries 2025 (Abrasives-Copper).pdf" which has both words in name
        assertTrue(results.size() >= 1, "Should find at least 1 file matching both conditions");
    }

    @Test
    public void testSearchWithMultipleConditionsSameKey() {
        // Test with multiple conditions on the same key - should use AND logic
        // This will also test that separate EXISTS clauses are generated
        SearchConditionInput cond1 = new SearchConditionInput();
        cond1.setName("name");
        cond1.setOperation(SearchOperation.LIKE);
        cond1.setValue("%USGS%");  // FTS searches for word "USGS"

        SearchConditionInput cond2 = new SearchConditionInput();
        cond2.setName("name");
        cond2.setOperation(SearchOperation.LIKE);
        cond2.setValue("%Summaries%");  // FTS searches for word "Summaries"

        List<SearchConditionInput> conditions = List.of(cond1, cond2);
        List<Item> results = searchDao.search(conditions, null, null, null, 0, 100);

        // Should find "USGS Mineral Commodity Summaries 2025 (Abrasives-Copper).pdf" which has both "USGS" and "Summaries"
        assertTrue(results.size() >= 1, "Should find at least 1 file matching both conditions");
        assertTrue(results.stream().anyMatch(item -> item.getName().contains("USGS")),
            "Results should include the USGS Mineral Commodity Summaries file");
    }

    @Test
    public void testCountWithMultipleConditions() {
        // Verify count also works correctly with multiple conditions
        SearchConditionInput nameCond = new SearchConditionInput();
        nameCond.setName("name");
        nameCond.setOperation(SearchOperation.LIKE);
        nameCond.setValue("%text.txt%");
        
        SearchConditionInput pathCond = new SearchConditionInput();
        pathCond.setName("path");
        pathCond.setOperation(SearchOperation.LIKE);
        pathCond.setValue("%");
        
        List<SearchConditionInput> conditions = List.of(nameCond, pathCond);
        int count = searchDao.countSearchResults(conditions, null, null, null);
        
        // Should match search results
        List<Item> results = searchDao.search(conditions, null, null, null, 0, 100);
        assertEquals(results.size(), count, "Count should match search results size with multiple conditions");
    }

    // Count Tests

    @Test
    public void testCountWithNoCriteria() {
        int count = searchDao.countSearchResults(List.of(), null, null, null);
        assertEquals(12, count, "Should count all 12 items when no search criteria are present (8 original + 3 nested + 1 comma-folder)");
    }

    @Test
    public void testCountByFileNameParam() {
        int count = searchDao.countSearchResults(List.of(), "text.txt", null, null);
        assertEquals(1, count, "Should count exactly 1 file named text.txt");
    }

    @Test
    public void testCountByFolderParam() {
        int count = searchDao.countSearchResults(List.of(), null, "subf", null);
        assertEquals(2, count, "Should count exactly 2 items in folder 'subf'");
    }

    @Test
    public void testCountByFileNameCondition() {
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("name");
        cond.setOperation(SearchOperation.LIKE);
        cond.setValue("%text.txt%");
        List<SearchConditionInput> conditions = List.of(cond);
        int count = searchDao.countSearchResults(conditions, null, null, null);
        assertEquals(1, count, "Should count exactly 1 file matching name pattern %text.txt%");
    }

    @Test
    public void testCountByContentCondition() {
        // AND-of-words would also match the USGS report (separately contains "distributed" and
        // "systems"). Scope to the resume by name to keep this an exact-uniqueness assertion.
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("content");
        cond.setOperation(SearchOperation.LIKE);
        cond.setValue("%Distributed systems%");
        List<SearchConditionInput> conditions = List.of(cond);
        int count = searchDao.countSearchResults(conditions, "Resume.pdf", null, null);
        assertEquals(1, count, "Should count exactly 1 item containing 'Distributed systems'");
    }

    @Test
    public void testCountByNameAndFolderAndCondition() {
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("content");
        cond.setOperation(SearchOperation.LIKE);
        cond.setValue("%Distributed systems%");
        List<SearchConditionInput> conditions = List.of(cond);
        int count = searchDao.countSearchResults(conditions, "Resume.pdf", null, null);
        assertEquals(1, count, "Should count exactly 1 resume file matching name and content condition");
    }

    @Test
    public void testCountBySearchString() {
        // AND-of-words would also match the USGS report (separately contains "distributed" and
        // "systems"). Scope to the resume by name to keep this an exact-uniqueness assertion.
        int count = searchDao.countSearchResults(List.of(), "Resume.pdf", null, "Distributed systems");
        assertEquals(1, count, "Should count exactly 1 item with 'Distributed systems' in any field");
    }

    @Test
    public void testCountMatchesSearchResultsSize() {
        // Test that count always matches the actual number of search results for various queries

        // Test 1: No criteria
        List<Item> results1 = searchDao.search(List.of(), null, null, null, 0, 100);
        int count1 = searchDao.countSearchResults(List.of(), null, null, null);
        assertEquals(results1.size(), count1, "Count should match search results size with no criteria");

        // Test 2: With name filter
        List<Item> results2 = searchDao.search(List.of(), "pdf", null, null, 0, 100);
        int count2 = searchDao.countSearchResults(List.of(), "pdf", null, null);
        assertEquals(results2.size(), count2, "Count should match search results size with name filter");

        // Test 3: With folder filter
        List<Item> results3 = searchDao.search(List.of(), null, "subf", null, 0, 100);
        int count3 = searchDao.countSearchResults(List.of(), null, "subf", null);
        assertEquals(results3.size(), count3, "Count should match search results size with folder filter");

        // Test 4: With searchString
        List<Item> results4 = searchDao.search(List.of(), null, null, "Distributed", 0, 100);
        int count4 = searchDao.countSearchResults(List.of(), null, null, "Distributed");
        assertEquals(results4.size(), count4, "Count should match search results size with searchString");
    }

    // Validation Tests

    @Test
    public void testValidation_NegativeSkip() {
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(), null, null, null, -1, 100);
        });
        assertTrue(exception.getMessage().contains("Skip parameter must be non-negative"));
    }

    @Test
    public void testValidation_ZeroHowMany() {
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(), null, null, null, 0, 0);
        });
        assertTrue(exception.getMessage().contains("Howmany parameter must be positive"));
    }

    @Test
    public void testValidation_NegativeHowMany() {
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(), null, null, null, 0, -10);
        });
        assertTrue(exception.getMessage().contains("Howmany parameter must be positive"));
    }

    @Test
    public void testValidation_HowManyExceedsLimit() {
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(), null, null, null, 0, 1001);
        });
        assertTrue(exception.getMessage().contains("Howmany parameter exceeds maximum limit"));
    }

    @Test
    public void testValidation_NameTooLong() {
        String longName = "a".repeat(1001);
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(), longName, null, null, 0, 100);
        });
        assertTrue(exception.getMessage().contains("Name parameter exceeds maximum length"));
    }

    @Test
    public void testValidation_FolderTooLong() {
        String longFolder = "a".repeat(1001);
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(), null, longFolder, null, 0, 100);
        });
        assertTrue(exception.getMessage().contains("Folder parameter exceeds maximum length"));
    }

    @Test
    public void testValidation_SearchStringTooLong() {
        String longSearchString = "a".repeat(1001);
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(), null, null, longSearchString, 0, 100);
        });
        assertTrue(exception.getMessage().contains("SearchString parameter exceeds maximum length"));
    }

    @Test
    public void testValidation_SearchStringWithUnmatchedQuote() {
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(), null, null, "\"unmatched", 0, 100);
        });
        assertTrue(exception.getMessage().contains("unmatched double quote"));
    }

    @Test
    public void testValidation_SearchStringWithTsqueryOperator() {
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(), null, null, "(term", 0, 100);
        });
        assertTrue(exception.getMessage().contains("unsupported full-text search syntax character '('"));
    }

    @Test
    public void testValidation_TooManySearchConditions() {
        List<SearchConditionInput> tooManyConditions = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            SearchConditionInput cond = new SearchConditionInput();
            cond.setName("name");
            cond.setOperation(SearchOperation.EQUAL);
            cond.setValue("test");
            tooManyConditions.add(cond);
        }
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(tooManyConditions, null, null, null, 0, 100);
        });
        assertTrue(exception.getMessage().contains("Number of search conditions exceeds maximum limit"));
    }

    @Test
    public void testValidation_SearchConditionNullName() {
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName(null);
        cond.setOperation(SearchOperation.EQUAL);
        cond.setValue("test");
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(cond), null, null, null, 0, 100);
        });
        assertTrue(exception.getMessage().contains("Search condition name cannot be null or empty"));
    }

    @Test
    public void testValidation_SearchConditionEmptyName() {
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("");
        cond.setOperation(SearchOperation.EQUAL);
        cond.setValue("test");
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(cond), null, null, null, 0, 100);
        });
        assertTrue(exception.getMessage().contains("Search condition name cannot be null or empty"));
    }

    @Test
    public void testValidation_SearchConditionNameTooLong() {
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("a".repeat(1001));
        cond.setOperation(SearchOperation.EQUAL);
        cond.setValue("test");
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(cond), null, null, null, 0, 100);
        });
        assertTrue(exception.getMessage().contains("Search condition name exceeds maximum length"));
    }

    @Test
    public void testValidation_SearchConditionNullValue() {
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("name");
        cond.setOperation(SearchOperation.EQUAL);
        cond.setValue(null);
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(cond), null, null, null, 0, 100);
        });
        assertTrue(exception.getMessage().contains("Search condition value cannot be null"));
    }

    @Test
    public void testValidation_SearchConditionValueTooLong() {
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("name");
        cond.setOperation(SearchOperation.EQUAL);
        cond.setValue("a".repeat(1001));
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(cond), null, null, null, 0, 100);
        });
        assertTrue(exception.getMessage().contains("Search condition value exceeds maximum length"));
    }

    @Test
    public void testValidation_SearchConditionNullOperation() {
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("name");
        cond.setOperation(null);
        cond.setValue("test");
        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class, () -> {
            searchDao.search(List.of(cond), null, null, null, 0, 100);
        });
        assertTrue(exception.getMessage().contains("Search condition operation cannot be null"));
    }

    // Facet Tests

    @Test
    public void testGetAllItemAttributeKeys() {
        // Get all item attribute keys
        List<String> keys = searchDao.getAllItemAttributeKeys();
        
        assertNotNull(keys, "Keys should not be null");
        assertFalse(keys.isEmpty(), "Should return at least some keys from scanned items");
        
        // Should contain 'name' and 'path' which are always present from NAME_AND_PATH treatment
        assertTrue(keys.contains(FieldNameConstants.FIELD_NAME), "Keys should contain 'name'");
        assertTrue(keys.contains(FieldNameConstants.FIELD_PATH), "Keys should contain 'path'");

        // Verify 'path' comes first and 'name' second due to custom sorting
        assertEquals(FieldNameConstants.FIELD_PATH, keys.get(0), "'path' should be the first key");
        assertEquals(FieldNameConstants.FIELD_NAME, keys.get(1), "'name' should be the second key");

        // Use centralized standard fields list (matching SearchDAO.sortKeysByPriority)
        List<String> priorityKeys = FieldNameConstants.getStandardFieldsInPriorityOrder();

        // Find where priority keys end (the index of the first non-priority key)
        int firstNonPriorityIndex = 0;
        for (int i = 0; i < keys.size(); i++) {
            if (!priorityKeys.contains(keys.get(i))) {
                firstNonPriorityIndex = i;
                break;
            }
        }

        // Verify the remaining non-priority keys are sorted alphabetically
        for (int i = firstNonPriorityIndex; i < keys.size() - 1; i++) {
            assertTrue(keys.get(i).compareTo(keys.get(i + 1)) <= 0,
                    "Keys after priority keys should be sorted alphabetically");
        }
    }

    @Test
    public void testGetAllItemAttributeKeysContainsContentMetadata() {
        // Get all item attribute keys
        List<String> keys = searchDao.getAllItemAttributeKeys();
        
        assertNotNull(keys, "Keys should not be null");
        
        // Since we have CONTENT treatment enabled, we should have 'content' key
        // and potentially other metadata keys extracted from files
        assertTrue(keys.contains("content"), "Keys should contain 'content' from CONTENT treatment");
    }

    // Clear Data Source Data Tests

    @Test
    public void testClearDataSourceData() {
        // Verify we have data before clearing
        List<Item> itemsBefore = searchDao.search(List.of(), null, null, null, 0, 100);
        assertEquals(12, itemsBefore.size(), "Should have 12 items before clearing");

        int countBefore = searchDao.countSearchResults(List.of(), null, null, null);
        assertEquals(12, countBefore, "Count should be 12 before clearing");

        // Verify treatments exist
        List<enterfind.server.model.DataSourceTreatment> treatmentsBefore =
            treatmentDao.getTreatmentsByDataSource(testDataSource.id());
        assertFalse(treatmentsBefore.isEmpty(), "Should have treatments configured before clearing");

        // Clear data
        int deletedCount = dataSourceDao.clearDataSourceData(testDataSource.id());
        assertEquals(12, deletedCount, "Should have deleted 12 items");
        
        // Verify items are gone
        List<Item> itemsAfter = searchDao.search(List.of(), null, null, null, 0, 100);
        assertEquals(0, itemsAfter.size(), "Should have 0 items after clearing");
        
        int countAfter = searchDao.countSearchResults(List.of(), null, null, null);
        assertEquals(0, countAfter, "Count should be 0 after clearing");
        
        // Verify datasource still exists
        assertTrue(dataSourceDao.existsByNameAndType(testDataSource.getName(), testDataSource.getType()),
            "Datasource should still exist after clearing data");
        
        // Verify treatments still exist (they should be preserved)
        List<enterfind.server.model.DataSourceTreatment> treatmentsAfter = 
            treatmentDao.getTreatmentsByDataSource(testDataSource.id());
        assertEquals(treatmentsBefore.size(), treatmentsAfter.size(), 
            "Treatments should be preserved after clearing data");
        
        // Verify dateLastScanned is reset to null
        var updatedDataSource = dataSourceDao.findById(testDataSource.id());
        assertTrue(updatedDataSource.isPresent(), "Datasource should still exist");
        assertNull(updatedDataSource.get().getDateLastScanned(), 
            "dateLastScanned should be null after clearing data");
        
        // Verify we can re-scan and get items back
        scanner.scan(testDataSource);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
        
        List<Item> itemsAfterRescan = searchDao.search(List.of(), null, null, null, 0, 100);
        assertEquals(12, itemsAfterRescan.size(), "Should have 12 items after re-scanning");
    }

    @Test
    public void testClearDataSourceDataRemovesTreatmentResults() {
        // Verify we have treatment results before clearing
        List<String> keysBefore = searchDao.getAllItemAttributeKeys();
        assertFalse(keysBefore.isEmpty(), "Should have item attribute keys before clearing");
        
        // Clear data
        dataSourceDao.clearDataSourceData(testDataSource.id());
        
        // Verify treatment results are gone (no keys available)
        List<String> keysAfter = searchDao.getAllItemAttributeKeys();
        assertTrue(keysAfter.isEmpty(), "Should have no item attribute keys after clearing (treatment results removed)");
        
        // Re-scan to restore data for other tests
        scanner.scan(testDataSource);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
    }

    @Test
    public void testClearDataSourceDataRemovesFolders() throws IOException {
        // Verify we have folders before clearing (test data has a 'subf' subfolder)
        List<Item> itemsInSubf = searchDao.search(List.of(), null, "subf", null, 0, 100);
        assertEquals(2, itemsInSubf.size(), "Should have 2 items in subf folder before clearing");
        
        // Clear data
        dataSourceDao.clearDataSourceData(testDataSource.id());
        
        // Verify folders are cleared (search by folder returns nothing)
        List<Item> itemsInSubfAfter = searchDao.search(List.of(), null, "subf", null, 0, 100);
        assertEquals(0, itemsInSubfAfter.size(), "Should have 0 items in subf folder after clearing");
        
        // Re-scan to restore data for other tests
        scanner.scan(testDataSource);
        int processed;
        do {
            processed = treatmentProcessor.processTreatments();
        } while (processed > 0);
    }

    @Test
    public void testGetFacetValuesForName() {
        // Get facet values for 'name' key
        List<enterfind.server.model.FacetValue> facetValues = searchDao.getFacetValues("name", List.of(), null, null, null);
        assertNotNull(facetValues, "Facet values should not be null");
        assertFalse(facetValues.isEmpty(), "Should return at least some facet values for 'name'");
        // Should contain known file names
        boolean foundTextFile = facetValues.stream().anyMatch(fv -> fv.getValue().equals("text.txt"));
        assertTrue(foundTextFile, "Should contain 'text.txt' as a facet value");
    }

    @Test
    public void testGetFacetValuesWithSearchCondition() {
        // Use test data created in setThisUp: USGS Mineral Commodity Summaries PDF is present
        SearchConditionInput cond = new SearchConditionInput();
        cond.setName("name");
        cond.setOperation(SearchOperation.LIKE);
        cond.setValue("%USGS%");  // FTS searches for word "USGS"
        // Use 'Content-type' as the facet key, which is extracted by CONTENT treatment
        List<enterfind.server.model.FacetValue> facetValues = searchDao.getFacetValues("Content-type", List.of(cond), null, null, null);
        assertNotNull(facetValues, "Facet values should not be null");
            // Print all key/value pairs for diagnosis to System.err for visibility in test reports
            System.err.println("Facet values for key 'Content-type':");
            for (enterfind.server.model.FacetValue fv : facetValues) {
                System.err.println("  value: " + fv.getValue() + ", count: " + fv.getCount());
        }
        // Should contain 'application/pdf' mime type from USGS Mineral Commodity Summaries PDF
        boolean foundPdfMime = facetValues.stream().anyMatch(fv -> fv.getValue().equals("application/pdf"));
        assertTrue(foundPdfMime, "Should contain 'application/pdf' as a facet value");
    }

    // ==================== Comma-in-folder-name Search Tests ====================

    @Test
    public void testSearchByFolderWithCommaInSegment_exactPath() {
        // Folder structure: level1/Rust Servers, Services, and Apps/comma-file.txt
        List<Item> results = searchDao.search(
            List.of(), null, "level1/Rust Servers, Services, and Apps", null, 0, 100);
        assertEquals(1, results.size(),
            "Should find comma-file.txt via exact slash-path with comma folder name");
        assertEquals("comma-file.txt", results.get(0).getName());
    }

    @Test
    public void testSearchByFolderWithCommaInSegment_substringMatch() {
        // Searching by just the comma folder name as a substring
        List<Item> results = searchDao.search(
            List.of(), null, "Rust Servers, Services, and Apps", null, 0, 100);
        assertEquals(1, results.size(),
            "Should find comma-file.txt when searching by the comma folder name alone");
        assertEquals("comma-file.txt", results.get(0).getName());
    }

    // ==================== searchString Partial Word Matching Tests ====================
    // searchString uses two different matching strategies:
    //   - Metadata (ITEM_TREATMENT_RESULTS): ILIKE '%term%' — partial/substring matching
    //   - Content (ITEM_TREATMENT_RESULTS_CONTENT): FTS to_tsquery('simple', ...) with :* prefix operator
    //
    // Unquoted words get :* appended in buildTsQuery, so 'distribut:*' matches "distributed",
    // "distribution", etc. (prefix matching). Quoted phrases are exact-sequence and unchanged.
    //
    // Limitation: only PREFIX matching is supported for content — 'tribut' will NOT match
    // "distributed" because it is an infix, not a prefix.
    //
    // The Mikhail resume has "Distributed systems" exclusively in CONTENT (not in name/path/metadata).

    @Test
    public void testSearchStringFullWordFindsContentField() {
        // Full word "Distributed" finds the resume via FTS on content.
        // The USGS report also contains "distributed"/"distribution", so on its own this would match
        // both; scope to the resume by name to keep this an exact-uniqueness assertion.
        List<Item> results = searchDao.search(List.of(), "Resume.pdf", null, "Distributed", 0, 100);
        assertEquals(1, results.size(),
            "Full word 'Distributed' should find the Mikhail resume via FTS on content");
        assertTrue(results.get(0).getName().contains("Mikhail"),
            "Result should be the Mikhail resume");
    }

    @Test
    public void testSearchStringPrefixWordFindsContentField() {
        // Partial prefix "Distribut" now finds the resume via FTS prefix matching (:*)
        // buildTsQuery converts "Distribut" → "Distribut:*" → matches "Distributed".
        // The USGS report also contains "distributed"/"distribution", so on its own this would match
        // both; scope to the resume by name to keep this an exact-uniqueness assertion.
        List<Item> results = searchDao.search(List.of(), "Resume.pdf", null, "Distribut", 0, 100);
        assertEquals(1, results.size(),
            "Prefix 'Distribut' should find the Mikhail resume via FTS prefix matching");
        assertTrue(results.get(0).getName().contains("Mikhail"),
            "Result should be the Mikhail resume");
    }

    @Test
    public void testSearchStringInfixDoesNotMatchContentField() {
        // Infix "tribut" (not a prefix of "distributed") is NOT found in content.
        // FTS prefix matching only covers tokens that START with the given string.
        // "tribut" is not in any metadata value either, so 0 results expected.
        List<Item> results = searchDao.search(List.of(), null, null, "tribut", 0, 100);
        assertEquals(0, results.size(),
            "Infix 'tribut' should NOT find the Mikhail resume: prefix matching only covers " +
            "tokens starting with the given string, not arbitrary substrings");
    }

    @Test
    public void testSearchStringMultiWordPrefixMatchesContent() {
        // Multiple unquoted partial words are each prefix-matched and ANDed together.
        // "Distribut system" → "Distribut:* & system:*" → matches "Distributed" AND "systems".
        // The USGS report separately contains both "distributed"/"distribution" and "systems" (though
        // not adjacent there), so on its own this would match both; scope to the resume by name to
        // keep this an exact-uniqueness assertion.
        List<Item> results = searchDao.search(List.of(), "Resume.pdf", null, "Distribut system", 0, 100);
        assertEquals(1, results.size(),
            "Multi-word prefix 'Distribut system' should find the resume via FTS prefix matching");
        assertTrue(results.get(0).getName().contains("Mikhail"),
            "Result should be the Mikhail resume");
    }

    @Test
    public void testSearchStringQuotedPhraseUnaffectedByPrefixChange() {
        // Quoted phrases use exact-sequence matching (<->), not prefix.
        // "\"Distributed systems\"" → "Distributed <-> systems" (unchanged)
        List<Item> results = searchDao.search(List.of(), null, null, "\"Distributed systems\"", 0, 100);
        assertEquals(1, results.size(),
            "Quoted phrase 'Distributed systems' should still find the resume via exact phrase search");
        assertTrue(results.get(0).getName().contains("Mikhail"),
            "Result should be the Mikhail resume");
    }

    @Test
    public void testSearchStringPartialWordFindsMetadataField() {
        // Partial word that appears in a METADATA value is found via ILIKE (unchanged).
        // The mime-type "application/pdf" appears in ITEM_TREATMENT_RESULTS for PDF files.
        List<Item> results = searchDao.search(List.of(), null, null, "applicat", 0, 100);
        assertTrue(results.size() > 0,
            "Partial word 'applicat' (substring of 'application/pdf') should find PDF files " +
            "via ILIKE on metadata");
    }
}
