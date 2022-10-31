package io.av360.maverick.graph.services.impl;

import io.av360.maverick.graph.model.errors.EntityNotFound;
import io.av360.maverick.graph.model.errors.InvalidEntityUpdate;
import io.av360.maverick.graph.model.rdf.LocalIRI;
import io.av360.maverick.graph.services.ValueServices;
import io.av360.maverick.graph.services.events.ValueInsertedEvent;
import io.av360.maverick.graph.services.events.ValueRemovedEvent;
import io.av360.maverick.graph.services.events.ValueReplacedEvent;
import io.av360.maverick.graph.store.EntityStore;
import io.av360.maverick.graph.store.SchemaStore;
import io.av360.maverick.graph.store.rdf.models.Transaction;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j(topic = "graph.service.values")
@Service
public class ValueServicesImpl implements ValueServices {


    private final EntityStore entityStore;
    private final SchemaStore schemaStore;

    private final ApplicationEventPublisher eventPublisher;

    public ValueServicesImpl(EntityStore entityStore,
                             SchemaStore schemaStore,
                             ApplicationEventPublisher eventPublisher) {
        this.entityStore = entityStore;
        this.schemaStore = schemaStore;
        this.eventPublisher = eventPublisher;
    }

    @Override
    public Mono<Transaction> insertValue(String id, String predicatePrefix, String predicateKey, String value, Authentication authentication) {
        return this.insertValue(LocalIRI.withDefaultNamespace(id),
                LocalIRI.withDefinedNamespace(schemaStore.getNamespaceFor(predicatePrefix), predicateKey),
                SimpleValueFactory.getInstance().createLiteral(value),
                authentication);
    }

    @Override
    public Mono<Transaction> insertValue(Resource entityIdentifier, IRI predicate, Value value, Authentication authentication) {
        return this.insertValue(entityIdentifier, predicate, value, new Transaction(), authentication)
                .doOnSuccess(trx -> {
                    eventPublisher.publishEvent(new ValueInsertedEvent(trx));
                });

    }

    Mono<Transaction> insertValue(Resource entityIdentifier, IRI predicate, Value value, Transaction transaction, Authentication authentication) {

        return this.entityStore.getEntity(entityIdentifier, authentication)
                .switchIfEmpty(Mono.error(new EntityNotFound(entityIdentifier.stringValue())))
                .map(entity -> Pair.of(entity, transaction.affected(entity)))
                .flatMap(pair -> {
                    // linking to bnodes is forbidden
                    if (value.isBNode()) {
                        log.trace("Insert link for {} to anonymous node is forbidden.", entityIdentifier);
                        return Mono.error(new InvalidEntityUpdate(entityIdentifier, "Trying to link to anonymous node."));
                    } else return Mono.just(pair);
                })
                .flatMap(pair -> {
                    // check if entity already has this statement. If yes, we do nothing
                    if (value.isIRI() && pair.getLeft().hasStatement(entityIdentifier, predicate, value)) {
                        log.trace("Entity {} already has a link '{}' for predicate '{}', ignoring update.", entityIdentifier, value, predicate);
                        return Mono.empty();
                    } else return Mono.just(pair);
                })
                .flatMap(pair -> {
                    // check if entity already has this literal with a different value. If yes, we remove it first (but only if it also has the same language tag)
                    if (value.isLiteral() && pair.getLeft().hasStatement(entityIdentifier, predicate, null)) {
                        log.trace("Entity {} already has a value for predicate '{}'.", entityIdentifier, predicate);
                        Literal updateValue = (Literal) value;

                        try {
                            for (Statement statement : pair.getLeft().listStatements(entityIdentifier, predicate, null)) {
                                if (!statement.getObject().isLiteral())
                                    throw new InvalidEntityUpdate(entityIdentifier, "Replacing an existing link to another entity with a value is not allowed. ");

                                Literal currentValue = (Literal) statement.getObject();
                                if (updateValue.getLanguage().isPresent() && currentValue.getLanguage().isPresent()) {
                                    // entity already has a value for this predicate. It has a language tag. If another value with the same language tag exists, we remove it.
                                    if(StringUtils.equals(currentValue.getLanguage().get(), updateValue.getLanguage().get())) {
                                        this.entityStore.removeStatement(statement, pair.getRight());
                                    }
                                } else {
                                    // entity already has a value for this predicate. It has no language tag. If an existing value has a language tag, we throw an error. If not, we remove it.
                                    if (currentValue.getLanguage().isPresent())
                                        throw new InvalidEntityUpdate(entityIdentifier, "This value already exists with a language tag within this entity. Please add the tag.");

                                    this.entityStore.removeStatement(statement, pair.getRight());
                                }

                            }
                            return Mono.just(pair);

                        } catch (InvalidEntityUpdate e) {
                            return Mono.error(e);
                        }

                    } else return Mono.just(pair);

                })
                .flatMap(pair -> this.entityStore.addStatement(entityIdentifier, predicate, value, transaction))
                .flatMap(trx -> this.entityStore.commit(trx, authentication))
                .switchIfEmpty(Mono.just(transaction));

    }

    /**
     * Deletes a value with a new transaction.  Fails if no entity exists with the given subject
     */
    @Override
    public Mono<Transaction> removeValue(String id, String predicatePrefix, String predicateKey, String value, Authentication authentication) {
        return this.removeValue(LocalIRI.withDefaultNamespace(id),
                LocalIRI.withDefinedNamespace(schemaStore.getNamespaceFor(predicatePrefix), predicateKey),
                schemaStore.getValueFactory().createLiteral(value),
                authentication);
    }


    /**
     * Deletes a value with a new transaction. Fails if no entity exists with the given subject
     */
    @Override
    public Mono<Transaction> removeValue(Resource entityIdentifier, IRI predicate, Value value, Authentication authentication) {
        return this.removeValue(entityIdentifier, predicate, value, new Transaction(), authentication)
                .doOnSuccess(trx -> {
                    eventPublisher.publishEvent(new ValueRemovedEvent(trx));
                });
    }

    /**
     * Internal method to remove a value within an existing transaction.
     */
    Mono<Transaction> removeValue(Resource entityIdentifier, IRI predicate, Value value, Transaction transaction, Authentication authentication) {

        return this.entityStore.listStatements(entityIdentifier, predicate, value, authentication)
                .flatMap(statements -> this.entityStore.removeStatements(statements, transaction))
                .flatMap(trx -> this.entityStore.commit(trx, authentication));
    }

    /**
     * Reroutes a statement of an entity, e.g. <entity> <hasProperty> <falseEntity> to <entity> <hasProperty> <rightEntity>.
     * <p>
     * Has to be part of one transaction (one commit call)
     */
    public Mono<Transaction> replaceValue(Resource entityIdentifier, IRI predicate, Value oldObject, Value newObject, Authentication authentication) {
        return this.entityStore.removeStatement(entityIdentifier, predicate, oldObject, new Transaction())
                .flatMap(trx -> this.entityStore.addStatement(entityIdentifier, predicate, newObject, trx))
                .flatMap(trx -> this.entityStore.commit(trx, authentication))
                .doOnSuccess(trx -> {
                    eventPublisher.publishEvent(new ValueReplacedEvent(trx));
                });
    }


}
