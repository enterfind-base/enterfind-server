package enterfind.server.model;

/**
 * Represents a single value within a facet group.
 * Contains the value and the count of items matching that value.
 */
public class FacetValue {
    private String value;
    private int count;

    public FacetValue() {
    }

    public FacetValue(String value, int count) {
        this.value = value;
        this.count = count;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = count;
    }

    @Override
    public String toString() {
        return "FacetValue{" +
                "value='" + value + '\'' +
                ", count=" + count +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FacetValue that = (FacetValue) o;

        if (count != that.count) return false;
        return value != null ? value.equals(that.value) : that.value == null;
    }

    @Override
    public int hashCode() {
        int result = value != null ? value.hashCode() : 0;
        result = 31 * result + count;
        return result;
    }
}
