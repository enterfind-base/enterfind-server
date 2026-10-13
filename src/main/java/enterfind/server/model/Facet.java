package enterfind.server.model;

import java.util.List;

/**
 * Represents a facet group containing a key and list of possible values with their counts.
 * Facets are aggregated from ITEM_TREATMENT_RESULTS based on search results.
 */
public class Facet {
    private String key;
    private List<FacetValue> values;

    public Facet() {
    }

    public Facet(String key, List<FacetValue> values) {
        this.key = key;
        this.values = values;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public List<FacetValue> getValues() {
        return values;
    }

    public void setValues(List<FacetValue> values) {
        this.values = values;
    }

    @Override
    public String toString() {
        return "Facet{" +
                "key='" + key + '\'' +
                ", values=" + values +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        Facet facet = (Facet) o;

        if (key != null ? !key.equals(facet.key) : facet.key != null) return false;
        return values != null ? values.equals(facet.values) : facet.values == null;
    }

    @Override
    public int hashCode() {
        int result = key != null ? key.hashCode() : 0;
        result = 31 * result + (values != null ? values.hashCode() : 0);
        return result;
    }
}
