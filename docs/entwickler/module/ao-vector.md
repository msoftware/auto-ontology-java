# Modul `ao-vector`

Paket `de.jentsch.autoontology.vector` · [Zurück zur Übersicht](../README.md)

## Zweck

Abstraktion des Vektorstores mit zwei Implementierungen: **Qdrant** (Betrieb) und **In-Memory**
(Tests, Demo). Nach außen sprechen beide dieselbe Sprache: Cosinus-**Distanz**, kleiner ist besser –
wie pgvector im Original.

## Abhängigkeiten

`api`: `ao-store` (für die optionale Persistenz des In-Memory-Stores).
`implementation`: `io.qdrant:client` 1.20.0 (gRPC). Tests: H2, `testcontainers-qdrant`.

## Klassen

| Klasse | Aufgabe |
|---|---|
| `VectorStore` | Interface plus Records `VectorRecord`, `SearchHit`, `VectorFilter`; Konstanten `DATA_LAYER`, `SEMANTIC_LAYER` |
| `QdrantVectorStore` | Adapter auf den Qdrant-Java-Client (gRPC), rechnet Scores in Distanzen um |
| `InMemoryVectorStore` | Brute-Force-Cosinus, optional in `vector_record` persistiert |
| `VectorAutoConfiguration` | wählt die Implementierung nach `ao.vector.store` und ruft `ensureCollections()` |

## Datenmodell

```java
record VectorRecord(String id, String text, String label, String name, String databaseName,
                    Map<String, Object> metadata, float[] vector)
record SearchHit(VectorRecord record, double distance)           // distance = Cosinus-Distanz
record VectorFilter(List<String> databaseNames, List<String> labels, List<String> ids)
```

- `id` ist die **fachliche** ID (z. B. die UUID einer Spalte oder eines Terms).
- `label` ist der Typ: im Data Layer `Table`, `Column`; im Semantic Layer `Term`,
  `ColumnAttribute`, `SqlAttribute`, `CustomAnalysis`, `Dataset`.
- `metadata` enthält die Payload-Felder des Originals (`vdb/records.py`), z. B. `schema_name`,
  `table_name`, `term_name`, `source_path`.
- In `VectorFilter` bedeutet eine **leere Liste „keine Einschränkung“**.

| Collection | Inhalt | Geschrieben von |
|---|---|---|
| `data_objects_layer` | Tabellen und Spalten | `DataLayerEmbedder` (`ao-ingestion`) |
| `semantic_layer` | Terms, ColumnAttributes, SqlAttributes, CustomAnalyses, Datasets | `SemanticEmbedder` (`ao-semantic`), `DatasetSync` (`ao-nl-chart`) |

Beide Namen bekommen das Präfix `ao.vector.qdrant.collection-prefix` (gilt auch für In-Memory).

## Interface

| Methode | Semantik |
|---|---|
| `ensureCollections()` | legt fehlende Collections an; bricht ab, wenn eine vorhandene eine andere Dimension oder Distanz hat |
| `upsert(collection, records)` | schreibt oder ersetzt Points; prüft die Dimension jedes Vektors |
| `search(collection, vector, k, filter, maxDistance)` | Top-k nach Distanz, optional mit Schwelle |
| `deleteByIds(collection, ids)` | löscht nach fachlicher ID |
| `deleteByFilter(collection, databaseName, label)` | löscht nach Datenbank und/oder Label (`null` = beliebig) |
| `count(collection)` | Anzahl Points |
| `ids(collection, databaseName)` | fachliche IDs einer Datenbank (Abgleich) |
| `health()` | Map mit `store`, `ok` und Zählern bzw. Fehlern |

## Implementierungsdetails

### Qdrant

- Verbindung: `ao.vector.qdrant.host`/`port` (gRPC, Default 6334), `use-tls`, `api-key`,
  `timeout` (Default 30 s).
- Collections: `size = ao.vector.dimensions`, `distance = Cosine`, optional `datatype`
  (`float16` halbiert den Speicher). Payload-Indizes vom Typ `keyword` auf `database_name`, `label`
  und `id`.
- **Point-ID:** Qdrant akzeptiert nur UUIDs oder Ganzzahlen. Ist die fachliche ID eine UUID, wird
  sie direkt verwendet, sonst `UUID.nameUUIDFromBytes(collection + ":" + id)`. Das ist eine
  deterministische, namensbasierte UUID (Version 3, MD5); die Anforderung nennt v5 – für die
  Funktion ist nur die Determinismus-Eigenschaft wichtig. Die fachliche ID steht immer im Payload
  `id`.
- **Upsert** in Batches von `ao.vector.qdrant.upsert-batch-size` (Default 256) mit `wait = true`,
  damit ein direkt folgender Suchaufruf die neuen Points sieht.
- **Suche:** Qdrant liefert Ähnlichkeit (größer ist besser). Der Adapter setzt
  `scoreThreshold = 1 − maxDistance` und gibt `distance = 1 − score` zurück. Nichts außerhalb dieser
  Klasse sieht Scores.
- **Filter:** `VectorFilter` wird zu Qdrant-`must`-Bedingungen auf den Payload-Indizes (mehrere
  Werte als `match any`).
- `ids()` scrollt in Seiten zu 512.

### In-Memory

- Eine `ConcurrentHashMap` je Collection, Suche per Brute-Force über alle Points,
  `distance = 1 − cosine`, Sortierung nach Distanz und dann ID (deterministisch).
- Mit `ao.vector.persist=true` (Demo) werden Points zusätzlich in `vector_record` geschrieben
  (Vektor als kommagetrennter Text) und in `ensureCollections()` geladen. Hat ein gespeicherter
  Vektor eine andere Dimension, bricht der Start ab („reset the vector store“).
- Schreibende Methoden sind `synchronized`.

### Auswahl

```yaml
ao.vector.store: qdrant | memory     # Default qdrant; Profil demo: memory
ao.vector.dimensions: 2048           # muss zum Embedding-Modell passen
```

`VectorAutoConfiguration` erzeugt die Implementierung und ruft `ensureCollections()` sofort auf.
Fehler (Qdrant nicht erreichbar, falsche Dimension) brechen damit den Start ab.

## Erweiterungspunkte

- **Anderer Vektorstore:** eigene `VectorStore`-Bean im Host. Sie muss die Distanzsemantik
  einhalten; `VectorStoreContract` (Test-Quellen) als Vorlage für den eigenen Vertragstest nutzen.
- **Neues Label:** einfach mit neuem `label` schreiben; für Filter in der Suche das Label in der
  Abfrage angeben (z. B. `Retrieval.SEMANTIC_LABELS` in `ao-agent`).

## Tests

- `VectorStoreContract` (abstrakt): `searchReturnsDistancesSmallestFirstAndHonoursFilters` –
  Distanzen aufsteigend, Filter nach Datenbank und Label.
- `InMemoryVectorStoreTest` – Vertrag gegen In-Memory (Standard-Build) plus
  `persistsIntoTheStoreAndReloads` (Persistenz in `vector_record`).
- `QdrantVectorStoreIT` – Vertrag gegen `qdrant/qdrant:v1.20.0` (Testcontainers, Task `integrationTest`).

## Fallstricke

- **Dimensionswechsel:** Ein anderes Embedding-Modell braucht eine passende `ao.vector.dimensions`
  und neue Collections. Einen automatischen Neuaufbau mit Alias-Umschaltung (FR-VEC-06) gibt es
  nicht: Collections in Qdrant löschen (bzw. `vector_record` leeren) und neu ingesten/kompilieren.
- Ohne gemeinsame Transaktion mit dem Store können Store und Vektoren auseinanderlaufen, wenn ein
  Upsert scheitert. `POST /internal/vector/reconcile` gleicht nur den Data Layer ab.
- Die In-Memory-Suche ist O(n) je Anfrage – für die Demo gedacht, nicht für große Kataloge.
- Client- und Server-Version von Qdrant sind gemeinsam gepinnt (1.20.0 in Version Catalog,
  Compose-Dateien und Tests). Beim Update alle Stellen ändern, nie `latest`.

## Anforderungsbezug

FR-VEC-01 bis FR-VEC-06, FR-DEMO-01, NFR-TST-02.
