package enterfind.server.model;

import java.util.List;

import enterfind.server.scan.Folder;
import enterfind.server.scan.Item;

public record TestDataSourceResult(
    boolean success,
    String message,
    List<Item> files,
    List<Folder> folders
) {
    public static TestDataSourceResult success(List<Item> files, List<Folder> folders) {
        return new TestDataSourceResult(true, null, files, folders);
    }
    
    public static TestDataSourceResult failure(String message) {
        return new TestDataSourceResult(false, message, List.of(), List.of());
    }
}
