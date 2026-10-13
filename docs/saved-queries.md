# Saved Queries

A saved query is a [search](search.md) you've named and stored so you can run it again without rebuilding it. If you find yourself typing the same search string, setting the same conditions, and choosing the same sort order repeatedly, save it once and recall it.

## What a saved query holds

A saved query captures the reusable parts of a search:

- the **search string**,
- the **conditions** (the structured filters on item attributes), and
- the **sort direction**.

Recalling it restores those, so you land on the same search you saved. It's the definition of a search that's kept, not a frozen snapshot of results — run a saved query again after your corpus has changed and it reflects the current state, including items scanned since you saved it.

## Saved queries are personal

A saved query belongs to the user who created it. Your saved queries are yours; they aren't shared across the other users of an instance. On an instance with [authentication](users-and-access.md) turned off, there's a single effective user, so saved queries simply belong to that instance.

## Creating and removing them

You create a saved query from a search you've built — the current search string, conditions, and sort direction are what get stored under the name. You can delete a saved query when you no longer need it. Both actions are also available through [the API](api.md) (`createSavedQuery` / `deleteSavedQuery`), so tooling can manage them alongside everything else.

## When to use one

- A recurring review — "everything added under this folder, newest first."
- A standing filter you return to — a particular document type, a particular person in your photos, a particular attribute value.
- A complex condition set that's tedious to reconstruct — build it once, save it, forget the syntax.

For one-off searches, there's no need to save anything; just search. Saved queries earn their place when the same search recurs.

---

**See also:** [Search](search.md) · [Item Attributes](item-attributes.md) · [The API](api.md)

[← Back to documentation index](../README.md)
