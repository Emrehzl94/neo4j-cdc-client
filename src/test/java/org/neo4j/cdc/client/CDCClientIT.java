/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.cdc.client;

import static java.util.Collections.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.*;
import java.util.*;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.cdc.client.model.*;
import org.neo4j.cdc.client.selector.EntitySelector;
import org.neo4j.cdc.client.selector.NodeSelector;
import org.neo4j.cdc.client.selector.RelationshipSelector;
import org.neo4j.driver.*;
import org.neo4j.driver.exceptions.FatalDiscoveryException;
import org.testcontainers.containers.Neo4jContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.test.StepVerifier;

@Testcontainers
public abstract class CDCClientIT {

    abstract Driver driver();

    abstract Neo4jContainer<?> neo4j();

    abstract Map<String, Object> defaultExpectedAdditionalEntries();

    private ChangeIdentifier current;

    @BeforeEach
    void reset() {
        try (var session = driver().session(SessionConfig.forDatabase("system"))) {
            session.run(
                            "CREATE OR REPLACE DATABASE $db OPTIONS {txLogEnrichment: $mode} WAIT",
                            Map.of("db", "neo4j", "mode", "FULL"))
                    .consume();
        }

        try (var session = driver().session()) {
            current = currentChangeId(session);
        }
    }

    private static ChangeIdentifier currentChangeId(Session session) {
        return new ChangeIdentifier(
                session.run("CALL cdc.current()").single().get(0).asString());
    }

    @Test
    void earliest() {
        var client = new CDCClient(driver(), Duration.ZERO);

        StepVerifier.create(client.earliest())
                .assertNext(cv -> assertNotNull(cv.getId()))
                .verifyComplete();
    }

    @Test
    void current() {
        var client = new CDCClient(driver(), Duration.ZERO);

        StepVerifier.create(client.current())
                .assertNext(cv -> assertNotNull(cv.getId()))
                .verifyComplete();
    }

    @Test
    void changesCanBeQueried() {
        var client = new CDCClient(driver(), Duration.ZERO);

        try (Session session = driver().session()) {
            session.run("CREATE ()").consume();
        }

        StepVerifier.create(client.query(current))
                .assertNext(n -> assertThat(n).extracting(ChangeEvent::getEvent).isInstanceOf(NodeEvent.class))
                .verifyComplete();
    }

    @Test
    void respectsSessionConfigSupplier() {
        var client = new CDCClient(
                driver(),
                () -> SessionConfig.builder().withDatabase("unknownDatabase").build());
        StepVerifier.create(client.current())
                .expectError(FatalDiscoveryException.class)
                .verify();
    }

    @Test
    void shouldReturnCypherTypesWithoutConversion() {
        var client = new CDCClient(driver(), Duration.ZERO);

        var props = new HashMap<String, Object>();
        props.put("bool", true);
        props.put("date", LocalDate.of(1990, 5, 1));
        props.put("duration", Values.isoDuration(1, 0, 0, 0).asIsoDuration());
        props.put("float", 5.25);
        props.put("integer", 123L);
        props.put("list", List.of(1L, 2L, 3L));
        props.put("local_datetime", LocalDateTime.of(1990, 5, 1, 23, 59, 59, 0));
        props.put("local_time", LocalTime.of(23, 59, 59, 0));
        props.put("point2d", Values.point(4326, 1, 2).asPoint());
        props.put("point3d", Values.point(4979, 1, 2, 3).asPoint());
        props.put("string", "a string");
        props.put("zoned_datetime", ZonedDateTime.of(1990, 5, 1, 23, 59, 59, 0, ZoneId.of("UTC")));
        props.put("zoned_time", OffsetTime.of(23, 59, 59, 0, ZoneOffset.ofHours(1)));

        try (Session session = driver().session()) {
            session.run("CREATE (a) SET a = $props", Map.of("props", props)).consume();
        }

        StepVerifier.create(client.query(current))
                .assertNext(event -> assertThat(event)
                        .extracting(ChangeEvent::getEvent)
                        .asInstanceOf(InstanceOfAssertFactories.type(NodeEvent.class))
                        .satisfies(e -> assertThat(e.getBefore()).isNull())
                        .satisfies(e -> assertThat(e.getAfter())
                                .isNotNull()
                                .extracting(NodeState::getProperties)
                                .asInstanceOf(InstanceOfAssertFactories.MAP)
                                .containsAllEntriesOf(props)))
                .verifyComplete();
    }

    @Test
    void metadataShouldNotHaveAdditionalEntries() {
        CDCClient client = new CDCClient(driver(), Duration.ZERO);

        try (var session = driver().session()) {
            session.run("CREATE (p:Person)", emptyMap()).consume();

            StepVerifier.create(client.query(current))
                    .assertNext(event -> assertThat(event).satisfies(e -> assertThat(e.getMetadata())
                            .satisfies(m -> assertThat(m.getAdditionalEntries()).isEmpty())))
                    .verifyComplete();
        }
    }

    @Test
    void nodeChangesCanBeQueried() {
        CDCClient client = new CDCClient(driver(), Duration.ZERO);

        try (var session = driver().session()) {
            session.run("CREATE CONSTRAINT FOR (p:Person) REQUIRE (p.first_name, p.last_name) IS NODE KEY")
                    .consume();

            final String elementId = session.run(
                            "CREATE (p:Person:Employee) SET p = $props RETURN elementId(p)",
                            Map.of(
                                    "props",
                                    Map.of(
                                            "first_name", "john",
                                            "last_name", "doe",
                                            "date_of_birth", LocalDate.of(1990, 5, 1))))
                    .single()
                    .get(0)
                    .asString();

            StepVerifier.create(client.query(current))
                    .assertNext(event -> assertThat(event)
                            .satisfies(c -> {
                                assertThat(c.getId()).isNotNull();
                                assertThat(c.getTxId()).isNotNull();
                                assertThat(c.getSeq()).isNotNull();
                            })
                            .satisfies(e -> assertThat(e.getMetadata())
                                    .satisfies(m ->
                                            assertThat(m.getAdditionalEntries()).isEmpty())
                                    .satisfies(m ->
                                            assertThat(m.getAuthenticatedUser()).isEqualTo("neo4j"))
                                    .satisfies(m ->
                                            assertThat(m.getExecutingUser()).isEqualTo("neo4j"))
                                    .satisfies(
                                            m -> assertThat(m.getCaptureMode()).isEqualTo(CaptureMode.FULL))
                                    .satisfies(m ->
                                            assertThat(m.getConnectionType()).isEqualTo("bolt"))
                                    .satisfies(m ->
                                            assertThat(m.getConnectionClient()).isNotNull())
                                    .satisfies(m ->
                                            assertThat(m.getConnectionServer()).isNotNull())
                                    .satisfies(m -> assertThat(m.getServerId()).isNotNull())
                                    .satisfies(
                                            m -> assertThat(m.getTxStartTime()).isNotNull())
                                    .satisfies(
                                            m -> assertThat(m.getTxCommitTime()).isNotNull()))
                            .satisfies(e -> assertThat(e.getEvent())
                                    .isNotNull()
                                    .asInstanceOf(InstanceOfAssertFactories.type(NodeEvent.class))
                                    .hasFieldOrPropertyWithValue("eventType", EventType.NODE)
                                    .hasFieldOrPropertyWithValue("operation", EntityOperation.CREATE)
                                    .hasFieldOrPropertyWithValue("elementId", elementId)
                                    .hasFieldOrPropertyWithValue("labels", List.of("Person", "Employee"))
                                    .hasFieldOrPropertyWithValue(
                                            "keys",
                                            Map.of("Person", List.of(Map.of("first_name", "john", "last_name", "doe"))))
                                    .hasFieldOrPropertyWithValue("before", null)
                                    .hasFieldOrPropertyWithValue(
                                            "after",
                                            new NodeState(
                                                    List.of("Person", "Employee"),
                                                    Map.of(
                                                            "first_name",
                                                            "john",
                                                            "last_name",
                                                            "doe",
                                                            "date_of_birth",
                                                            LocalDate.of(1990, 5, 1))))))
                    .verifyComplete();
        }
    }

    @Test
    void relationshipChangesCanBeQueried() {
        var client = new CDCClient(driver(), Duration.ZERO);

        try (var session = driver().session()) {
            session.run("CREATE CONSTRAINT FOR (p:Person) REQUIRE (p.id) IS NODE KEY")
                    .consume();
            session.run("CREATE CONSTRAINT FOR (p:Place) REQUIRE (p.id) IS NODE KEY")
                    .consume();
            session.run("CREATE CONSTRAINT FOR ()-[b:BORN_IN]-() REQUIRE (b.on) IS RELATIONSHIP KEY")
                    .consume();

            var nodes = session.run(
                            "CREATE (p:Person), (a:Place) SET p = $person, a = $place RETURN elementId(p), elementId(a)",
                            Map.of("person", Map.of("id", 1L), "place", Map.of("id", 48)))
                    .single();
            final var startElementId = nodes.get(0).asString();
            final var endElementId = nodes.get(1).asString();

            current = currentChangeId(session);

            final var elementId = session.run(
                            "MATCH (p:Person {id: 1}), (a:Place {id:48}) CREATE (p)-[b:BORN_IN]->(a) SET b = $props RETURN elementId(b)",
                            Map.of("props", Map.of("on", LocalDate.of(1990, 5, 1))))
                    .single()
                    .get(0)
                    .asString();

            StepVerifier.create(client.query(current))
                    .assertNext(event -> assertThat(event)
                            .satisfies(c -> {
                                assertThat(c.getId()).isNotNull();
                                assertThat(c.getTxId()).isNotNull();
                                assertThat(c.getSeq()).isNotNull();
                            })
                            .satisfies(e -> assertThat(e.getMetadata())
                                    .satisfies(m ->
                                            assertThat(m.getAdditionalEntries()).isEmpty())
                                    .satisfies(m ->
                                            assertThat(m.getAuthenticatedUser()).isEqualTo("neo4j"))
                                    .satisfies(m ->
                                            assertThat(m.getExecutingUser()).isEqualTo("neo4j"))
                                    .satisfies(
                                            m -> assertThat(m.getCaptureMode()).isEqualTo(CaptureMode.FULL))
                                    .satisfies(m ->
                                            assertThat(m.getConnectionType()).isEqualTo("bolt"))
                                    .satisfies(m ->
                                            assertThat(m.getConnectionClient()).isNotNull())
                                    .satisfies(m ->
                                            assertThat(m.getConnectionServer()).isNotNull())
                                    .satisfies(m -> assertThat(m.getServerId()).isNotNull())
                                    .satisfies(
                                            m -> assertThat(m.getTxStartTime()).isNotNull())
                                    .satisfies(
                                            m -> assertThat(m.getTxCommitTime()).isNotNull()))
                            .satisfies(e -> assertThat(e.getEvent())
                                    .isNotNull()
                                    .asInstanceOf(InstanceOfAssertFactories.type(RelationshipEvent.class))
                                    .hasFieldOrPropertyWithValue("eventType", EventType.RELATIONSHIP)
                                    .hasFieldOrPropertyWithValue("operation", EntityOperation.CREATE)
                                    .hasFieldOrPropertyWithValue("elementId", elementId)
                                    .hasFieldOrPropertyWithValue("type", "BORN_IN")
                                    .hasFieldOrPropertyWithValue(
                                            "start",
                                            new Node(
                                                    startElementId,
                                                    List.of("Person"),
                                                    Map.of("Person", List.of(Map.of("id", 1L)))))
                                    .hasFieldOrPropertyWithValue(
                                            "end",
                                            new Node(
                                                    endElementId,
                                                    List.of("Place"),
                                                    Map.of("Place", List.of(Map.of("id", 48L)))))
                                    .hasFieldOrPropertyWithValue(
                                            "keys", List.of(Map.of("on", LocalDate.of(1990, 5, 1))))
                                    .hasFieldOrPropertyWithValue("before", null)
                                    .hasFieldOrPropertyWithValue(
                                            "after", new RelationshipState(Map.of("on", LocalDate.of(1990, 5, 1))))))
                    .verifyComplete();
        }
    }

    @Test
    void selectorsArePassedToServer() {

        try (var session = driver().session()) {
            session.run("CREATE CONSTRAINT FOR (p:Person) REQUIRE (p.id) IS NODE KEY")
                    .consume();
            session.run("CREATE CONSTRAINT FOR (p:Place) REQUIRE (p.id) IS NODE KEY")
                    .consume();
            session.run("CREATE CONSTRAINT FOR ()-[b:BORN_IN]-() REQUIRE (b.on) IS RELATIONSHIP KEY")
                    .consume();

            var person1 = session.run(
                            "CREATE (p:Person) SET p = $props RETURN elementId(p)", Map.of("props", Map.of("id", 1)))
                    .single()
                    .get(0)
                    .asString();
            var person2 = session.run(
                            "CREATE (p:Person) SET p = $props RETURN elementId(p)", Map.of("props", Map.of("id", 2)))
                    .single()
                    .get(0)
                    .asString();
            var place = session.run(
                            "CREATE (p:Place) SET p = $props RETURN elementId(p)", Map.of("props", Map.of("id", 48)))
                    .single()
                    .get(0)
                    .asString();
            var bornIn = session.run(
                            "MATCH (p:Person WHERE elementId(p) = $person) "
                                    + "MATCH (a:Place WHERE elementId(a) = $place) "
                                    + "CREATE (p)-[b:BORN_IN]->(a) SET b = $props RETURN elementId(b)",
                            Map.of("person", person1, "place", place, "props", Map.of("on", LocalDate.of(1990, 5, 1))))
                    .single()
                    .get(0)
                    .asString();

            StepVerifier.create(new CDCClient(driver(), Duration.ZERO).query(current))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(person1))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(person2))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(place))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(bornIn))
                    .verifyComplete();

            StepVerifier.create(new CDCClient(
                                    driver(),
                                    Duration.ZERO,
                                    NodeSelector.builder()
                                            .withOperation(EntityOperation.CREATE)
                                            .withLabels(Set.of("Place"))
                                            .build())
                            .query(current))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(place))
                    .verifyComplete();

            StepVerifier.create(new CDCClient(
                                    driver(),
                                    Duration.ZERO,
                                    NodeSelector.builder()
                                            .withOperation(EntityOperation.CREATE)
                                            .withLabels(Set.of("Place"))
                                            .build(),
                                    NodeSelector.builder()
                                            .withLabels(Set.of("Person"))
                                            .build())
                            .query(current))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(person1))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(person2))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(place))
                    .verifyComplete();

            StepVerifier.create(new CDCClient(
                                    driver(),
                                    Duration.ZERO,
                                    RelationshipSelector.builder()
                                            .withType("BORN_IN")
                                            .build())
                            .query(current))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(bornIn))
                    .verifyComplete();

            StepVerifier.create(new CDCClient(
                                    driver(),
                                    Duration.ZERO,
                                    NodeSelector.builder()
                                            .withLabels(Set.of("Place"))
                                            .build(),
                                    RelationshipSelector.builder()
                                            .withType("BORN_IN")
                                            .build())
                            .query(current))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(place))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(bornIn))
                    .verifyComplete();
        }
    }

    @Test
    void selectorsDoFilteringCorrectly() {

        try (var session = driver().session()) {
            session.run("CREATE CONSTRAINT FOR (p:Person) REQUIRE (p.id) IS NODE KEY")
                    .consume();
            session.run("CREATE CONSTRAINT FOR (p:Place) REQUIRE (p.id) IS NODE KEY")
                    .consume();
            session.run("CREATE CONSTRAINT FOR ()-[b:BORN_IN]-() REQUIRE (b.on) IS RELATIONSHIP KEY")
                    .consume();

            var person1 = session.run(
                            "CREATE (p:Person) SET p = $props RETURN elementId(p)",
                            Map.of(
                                    "props",
                                    Map.of(
                                            "id",
                                            1,
                                            "name",
                                            "john",
                                            "surname",
                                            "doe",
                                            "dob",
                                            LocalDate.of(1990, 1, 5),
                                            "gender",
                                            "m")))
                    .single()
                    .get(0)
                    .asString();
            var person2 = session.run(
                            "CREATE (p:Person) SET p = $props RETURN elementId(p)",
                            Map.of(
                                    "props",
                                    Map.of(
                                            "id",
                                            2,
                                            "name",
                                            "jane",
                                            "surname",
                                            "doe",
                                            "dob",
                                            LocalDate.of(1995, 5, 1),
                                            "gender",
                                            "f")))
                    .single()
                    .get(0)
                    .asString();
            var place = session.run(
                            "CREATE (p:Place) SET p = $props RETURN elementId(p)",
                            Map.of("props", Map.of("id", 48, "name", "marmaris", "population", 50000)))
                    .single()
                    .get(0)
                    .asString();
            var bornIn = session.run(
                            "MATCH (p:Person WHERE elementId(p) = $person) "
                                    + "MATCH (a:Place WHERE elementId(a) = $place) "
                                    + "CREATE (p)-[b:BORN_IN]->(a) SET b = $props RETURN elementId(b)",
                            Map.of(
                                    "person",
                                    person1,
                                    "place",
                                    place,
                                    "props",
                                    Map.of("on", LocalDate.of(1990, 5, 1), "at", LocalTime.of(23, 39))))
                    .single()
                    .get(0)
                    .asString();

            session.run(
                            "MATCH ()-[r]->() WHERE elementId(r) = $bornIn SET r.hospital = $hospital, r.by = $doctor",
                            Map.of("bornIn", bornIn, "hospital", "state hospital", "doctor", "doctor who"))
                    .consume();

            StepVerifier.create(new CDCClient(driver(), Duration.ZERO).query(current))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(person1))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(person2))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(place))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(bornIn))
                    .assertNext(n -> assertThat(n).extracting("event.elementId").isEqualTo(bornIn))
                    .verifyComplete();

            StepVerifier.create(new CDCClient(
                                    driver(),
                                    Duration.ZERO,
                                    NodeSelector.builder()
                                            .withOperation(EntityOperation.CREATE)
                                            .withLabels(Set.of("Place"))
                                            .includingProperties(Set.of("*"))
                                            .build())
                            .query(current))
                    .assertNext(n -> assertThat(n)
                            .satisfies(e ->
                                    assertThat(e).extracting("event.elementId").isEqualTo(place))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.after.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("id", "name", "population")))
                    .verifyComplete();

            StepVerifier.create(new CDCClient(
                                    driver(),
                                    Duration.ZERO,
                                    NodeSelector.builder()
                                            .withOperation(EntityOperation.CREATE)
                                            .withLabels(Set.of("Place"))
                                            .includingProperties(Set.of("id", "name"))
                                            .build())
                            .query(current))
                    .assertNext(n -> assertThat(n)
                            .satisfies(e ->
                                    assertThat(e).extracting("event.elementId").isEqualTo(place))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.after.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("id", "name")))
                    .verifyComplete();

            StepVerifier.create(new CDCClient(
                                    driver(),
                                    Duration.ZERO,
                                    NodeSelector.builder()
                                            .withOperation(EntityOperation.CREATE)
                                            .withLabels(Set.of("Place"))
                                            .excludingProperties(Set.of("population"))
                                            .build())
                            .query(current))
                    .assertNext(n -> assertThat(n)
                            .satisfies(e ->
                                    assertThat(e).extracting("event.elementId").isEqualTo(place))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.after.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("id", "name")))
                    .verifyComplete();

            StepVerifier.create(new CDCClient(
                                    driver(),
                                    Duration.ZERO,
                                    NodeSelector.builder()
                                            .withLabels(Set.of("Place"))
                                            .excludingProperties(Set.of("population"))
                                            .build(),
                                    NodeSelector.builder()
                                            .withLabels(Set.of("Person"))
                                            .includingProperties(Set.of("id", "name", "surname"))
                                            .build())
                            .query(current))
                    .assertNext(n -> assertThat(n)
                            .satisfies(e ->
                                    assertThat(e).extracting("event.elementId").isEqualTo(person1))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.after.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("id", "name", "surname")))
                    .assertNext(n -> assertThat(n)
                            .satisfies(e ->
                                    assertThat(e).extracting("event.elementId").isEqualTo(person2))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.after.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("id", "name", "surname")))
                    .assertNext(n -> assertThat(n)
                            .satisfies(e ->
                                    assertThat(e).extracting("event.elementId").isEqualTo(place))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.after.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("id", "name")))
                    .verifyComplete();

            StepVerifier.create(new CDCClient(
                                    driver(),
                                    Duration.ZERO,
                                    NodeSelector.builder()
                                            .withLabels(Set.of("Person"))
                                            .withKey(Map.of("id", 1L))
                                            .excludingProperties(Set.of("gender"))
                                            .build(),
                                    NodeSelector.builder()
                                            .withLabels(Set.of("Person"))
                                            .withKey(Map.of("id", 2L))
                                            .includingProperties(Set.of("id", "name", "surname"))
                                            .build())
                            .query(current))
                    .assertNext(n -> assertThat(n)
                            .satisfies(e ->
                                    assertThat(e).extracting("event.elementId").isEqualTo(person1))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.after.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("id", "name", "surname", "dob")))
                    .assertNext(n -> assertThat(n)
                            .satisfies(e ->
                                    assertThat(e).extracting("event.elementId").isEqualTo(person2))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.after.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("id", "name", "surname")))
                    .verifyComplete();

            StepVerifier.create(new CDCClient(
                                    driver(),
                                    Duration.ZERO,
                                    RelationshipSelector.builder()
                                            .withType("BORN_IN")
                                            .includingProperties(Set.of("*"))
                                            .build())
                            .query(current))
                    .assertNext(n -> assertThat(n)
                            .satisfies(e ->
                                    assertThat(e).extracting("event.elementId").isEqualTo(bornIn))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.after.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("on", "at")))
                    .assertNext(n -> assertThat(n)
                            .satisfies(e ->
                                    assertThat(e).extracting("event.elementId").isEqualTo(bornIn))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.before.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("on", "at"))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.after.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("on", "at", "hospital", "by")))
                    .verifyComplete();

            StepVerifier.create(new CDCClient(
                                    driver(),
                                    Duration.ZERO,
                                    RelationshipSelector.builder()
                                            .withType("BORN_IN")
                                            .includingProperties(Set.of("on"))
                                            .build())
                            .query(current))
                    .assertNext(n -> assertThat(n)
                            .satisfies(e ->
                                    assertThat(e).extracting("event.elementId").isEqualTo(bornIn))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.after.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("on")))
                    .assertNext(n -> assertThat(n)
                            .satisfies(e ->
                                    assertThat(e).extracting("event.elementId").isEqualTo(bornIn))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.before.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("on"))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.after.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("on")))
                    .verifyComplete();

            // first matching selector wins
            StepVerifier.create(new CDCClient(
                                    driver(),
                                    Duration.ZERO,
                                    RelationshipSelector.builder()
                                            .withOperation(EntityOperation.CREATE)
                                            .withType("BORN_IN")
                                            .excludingProperties(Set.of("on"))
                                            .build(),
                                    RelationshipSelector.builder()
                                            .withOperation(EntityOperation.CREATE)
                                            .withType("BORN_IN")
                                            .excludingProperties(Set.of("at"))
                                            .build())
                            .query(current))
                    .assertNext(n -> assertThat(n)
                            .satisfies(e ->
                                    assertThat(e).extracting("event.elementId").isEqualTo(bornIn))
                            .satisfies(e -> assertThat(e)
                                    .extracting(
                                            "event.after.properties",
                                            InstanceOfAssertFactories.map(String.class, Object.class))
                                    .containsOnlyKeys("at")))
                    .verifyComplete();
        }
    }

    @Test
    void userSelectorsFilterCorrectly() {
        // prepare
        try (var session = driver().session()) {
            session.run(
                            "CREATE OR REPLACE USER $user SET PLAINTEXT PASSWORD $pwd CHANGE NOT REQUIRED",
                            Map.of("user", "test", "pwd", "passw0rd"))
                    .consume();
            session.run("CREATE OR REPLACE ROLE $role", Map.of("role", "imp")).consume();
            session.run("GRANT IMPERSONATE ($user) ON DBMS TO $role", Map.of("user", "neo4j", "role", "imp"))
                    .consume();
            session.run("GRANT ROLE publisher, imp to $user", Map.of("user", "test"))
                    .consume();
        }

        // make changes with test user
        try (var driver = GraphDatabase.driver(neo4j().getBoltUrl(), AuthTokens.basic("test", "passw0rd"));
                var session = driver.session();
                var impersonatedSession = driver.session(
                        SessionConfig.builder().withImpersonatedUser("neo4j").build())) {
            session.run("UNWIND range(1, 100) AS n CREATE (:Test {id: n})").consume();

            // also make change with impersonation
            impersonatedSession
                    .run("UNWIND range(1, 100) AS n CREATE (:Impersonated {id: n})")
                    .consume();
        }

        // make changes with neo4j user
        try (var session = driver().session()) {
            session.run("UNWIND range(1, 100) AS n CREATE (:Neo4j {id: n})").consume();
        }

        // verify authenticatedUser = test
        StepVerifier.create(new CDCClient(
                                driver(),
                                EntitySelector.builder()
                                        .withAuthenticatedUser("test")
                                        .build())
                        .query(current))
                .recordWith(ArrayList::new)
                .thenConsumeWhile(x -> true)
                .consumeRecordedWith(coll -> assertThat(coll).hasSize(200).allSatisfy(e -> assertThat(e)
                        .satisfies(c -> assertThat(c.getMetadata().getAuthenticatedUser())
                                .isEqualTo("test"))
                        .extracting("event.after.labels", InstanceOfAssertFactories.list(String.class))
                        .containsAnyOf("Test", "Impersonated")))
                .verifyComplete();

        // verify authenticatedUser = neo4j
        StepVerifier.create(new CDCClient(
                                driver(),
                                EntitySelector.builder()
                                        .withAuthenticatedUser("neo4j")
                                        .build())
                        .query(current))
                .recordWith(ArrayList::new)
                .thenConsumeWhile(x -> true)
                .consumeRecordedWith(coll -> assertThat(coll).hasSize(100).allSatisfy(e -> assertThat(e)
                        .satisfies(c -> assertThat(c.getMetadata().getAuthenticatedUser())
                                .isEqualTo("neo4j"))
                        .extracting("event.after.labels", InstanceOfAssertFactories.list(String.class))
                        .containsAnyOf("Neo4j")))
                .verifyComplete();

        // verify executingUser = neo4j
        StepVerifier.create(new CDCClient(
                                driver(),
                                EntitySelector.builder()
                                        .withExecutingUser("neo4j")
                                        .build())
                        .query(current))
                .recordWith(ArrayList::new)
                .thenConsumeWhile(x -> true)
                .consumeRecordedWith(coll -> assertThat(coll).hasSize(200).allSatisfy(e -> assertThat(e)
                        .satisfies(c ->
                                assertThat(c.getMetadata().getExecutingUser()).isEqualTo("neo4j"))
                        .extracting("event.after.labels", InstanceOfAssertFactories.list(String.class))
                        .containsAnyOf("Neo4j", "Impersonated")))
                .verifyComplete();

        // verify executingUser = test
        StepVerifier.create(new CDCClient(
                                driver(),
                                EntitySelector.builder()
                                        .withExecutingUser("test")
                                        .build())
                        .query(current))
                .recordWith(ArrayList::new)
                .thenConsumeWhile(x -> true)
                .consumeRecordedWith(coll -> assertThat(coll).hasSize(100).allSatisfy(e -> assertThat(e)
                        .satisfies(c ->
                                assertThat(c.getMetadata().getExecutingUser()).isEqualTo("test"))
                        .extracting("event.after.labels", InstanceOfAssertFactories.list(String.class))
                        .containsAnyOf("Test")))
                .verifyComplete();

        // verify authenticatedUser = test, executingUser = neo4j
        StepVerifier.create(new CDCClient(
                                driver(),
                                EntitySelector.builder()
                                        .withAuthenticatedUser("test")
                                        .withExecutingUser("neo4j")
                                        .build())
                        .query(current))
                .recordWith(ArrayList::new)
                .thenConsumeWhile(x -> true)
                .consumeRecordedWith(coll -> assertThat(coll).hasSize(100).allSatisfy(e -> assertThat(e)
                        .satisfies(c -> assertThat(c.getMetadata().getAuthenticatedUser())
                                .isEqualTo("test"))
                        .satisfies(c ->
                                assertThat(c.getMetadata().getExecutingUser()).isEqualTo("neo4j"))
                        .extracting("event.after.labels", InstanceOfAssertFactories.list(String.class))
                        .containsAnyOf("Impersonated")))
                .verifyComplete();
    }

    @Test
    void txMetadataSelectorFiltersCorrectly() {
        try (var session = driver().session()) {
            session.run(
                            "UNWIND range(1, 100) AS n CREATE (:Test {id: n})",
                            TransactionConfig.builder()
                                    .withMetadata(Map.of("app", "Test"))
                                    .build())
                    .consume();
        }

        try (var session = driver().session()) {
            session.run(
                            "UNWIND range(1, 100) AS n CREATE (:Other {id: n})",
                            TransactionConfig.builder()
                                    .withMetadata(Map.of("app", "Other"))
                                    .build())
                    .consume();
        }

        try (var session = driver().session()) {
            session.run(
                            "UNWIND range(1, 100) AS n CREATE (:Another {id: n})",
                            TransactionConfig.builder()
                                    .withMetadata(Map.of("app", "Other", "appUser", "test"))
                                    .build())
                    .consume();
        }

        StepVerifier.create(new CDCClient(
                                driver(),
                                EntitySelector.builder()
                                        .withTxMetadata(Map.of("app", "Test"))
                                        .build())
                        .query(current))
                .recordWith(ArrayList::new)
                .thenConsumeWhile(x -> true)
                .consumeRecordedWith(coll -> assertThat(coll).hasSize(100).allSatisfy(e -> assertThat(e)
                        .satisfies(
                                c -> assertThat(c.getMetadata().getTxMetadata()).contains(Map.entry("app", "Test")))
                        .extracting("event.after.labels", InstanceOfAssertFactories.list(String.class))
                        .containsOnly("Test")))
                .verifyComplete();

        StepVerifier.create(new CDCClient(
                                driver(),
                                EntitySelector.builder()
                                        .withTxMetadata(Map.of("app", "Other"))
                                        .build())
                        .query(current))
                .recordWith(ArrayList::new)
                .thenConsumeWhile(x -> true)
                .consumeRecordedWith(coll -> assertThat(coll).hasSize(200).allSatisfy(e -> assertThat(e)
                        .satisfies(
                                c -> assertThat(c.getMetadata().getTxMetadata()).contains(Map.entry("app", "Other")))
                        .extracting("event.after.labels", InstanceOfAssertFactories.list(String.class))
                        .containsAnyOf("Other", "Another")
                        .doesNotContain("Test")))
                .verifyComplete();

        StepVerifier.create(new CDCClient(
                                driver(),
                                EntitySelector.builder()
                                        .withTxMetadata(Map.of("app", "Other", "appUser", "test"))
                                        .build())
                        .query(current))
                .recordWith(ArrayList::new)
                .thenConsumeWhile(x -> true)
                .consumeRecordedWith(coll -> assertThat(coll).hasSize(100).allSatisfy(e -> assertThat(e)
                        .satisfies(c -> assertThat(c.getMetadata().getTxMetadata())
                                .contains(Map.entry("app", "Other"), Map.entry("appUser", "test")))
                        .extracting("event.after.labels", InstanceOfAssertFactories.list(String.class))
                        .contains("Another")
                        .doesNotContain("Test", "Other")))
                .verifyComplete();
    }
}
