package enterfind.server.model.input;

import enterfind.server.dao.SearchOperation;

public class SearchConditionInput {
    private String name;
    private SearchOperation operation;
    private String value;

    public String getName() {
        return name;
    }
    public void setName(String name) {
        this.name = name;
    }
    public SearchOperation getOperation() {
        return operation;
    }
    public void setOperation(SearchOperation operation) {
        this.operation = operation;
    }
    public String getValue() {
        return value;
    }
    public void setValue(String value) {
        this.value = value;
    }
}
