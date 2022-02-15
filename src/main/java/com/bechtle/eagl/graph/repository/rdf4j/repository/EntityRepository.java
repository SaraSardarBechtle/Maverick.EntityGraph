package com.bechtle.eagl.graph.repository.rdf4j.repository;

import com.bechtle.eagl.graph.api.converter.RdfUtils;
import com.bechtle.eagl.graph.domain.model.extensions.NamespaceAwareStatement;
import com.bechtle.eagl.graph.domain.model.wrapper.Entity;
import com.bechtle.eagl.graph.domain.model.wrapper.Transaction;
import com.bechtle.eagl.graph.repository.EntityStore;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.*;
import org.eclipse.rdf4j.model.impl.SimpleNamespace;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.*;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.util.RDFInserter;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.RDFParserFactory;
import org.eclipse.rdf4j.sparqlbuilder.core.query.SelectQuery;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.MimeType;
import org.springframework.web.client.HttpClientErrorException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Component
public class EntityRepository implements EntityStore {

    private final Repository repository;

    public EntityRepository(@Qualifier("entities-storage") Repository repository) {
        this.repository = repository;
    }


    @Override
    public Mono<Transaction> store(Resource subject, IRI predicate, Value literal, @Nullable Transaction transaction) {
        if (transaction == null) transaction = new Transaction();

        Transaction finalTransaction = transaction;
        return Mono.create(c -> {
            try (RepositoryConnection connection = repository.getConnection()) {
                try {

                    Statement statement = repository.getValueFactory().createStatement(subject, predicate, literal);
                    connection.begin();
                    connection.add(statement);
                    connection.commit();

                    finalTransaction.addModifiedResource(subject);
                    finalTransaction.with(NamespaceAwareStatement.wrap(statement, Collections.emptySet()));
                    if (log.isDebugEnabled()) log.debug("(Store) Transaction completed for storing one statement.");

                    c.success(finalTransaction);
                } catch (Exception e) {
                    log.warn("Error while storing statement, performing rollback.", e);
                    connection.rollback();
                    c.error(e);
                }
            }

        });

    }


    @Override
    public Mono<Transaction> store(Model model, Transaction transaction) {
        /* create a new transaction node with some metadata, which is returned as object */

        return Mono.create(c -> {
            // Rio.write(triples.getStatements(), Rio.createWriter(RDFFormat.NQUADS, System.out));

            // TODO: perform validation via sha
            // https://rdf4j.org/javadoc/3.2.0/org/eclipse/rdf4j/sail/shacl/ShaclSail.html


            // get statements and load into repo
            try (RepositoryConnection connection = repository.getConnection()) {
                try {


                    List<Resource> modifiedResources = new ArrayList<>();

                    for (Resource obj : new ArrayList<>(model.subjects())) {

                        if (obj.isIRI()) {

                            connection.begin();
                            Iterable<Statement> statements = model.getStatements(obj, null, null);
                            connection.add(statements);
                            connection.commit();

                            transaction.addModifiedResource(obj);
                            transaction.with(statements);
                        }

                        modifiedResources.add(obj);
                    }


                    modifiedResources.forEach(transaction::addModifiedResource);
                    transaction.with(model);

                    if (log.isDebugEnabled())
                        log.debug("(Store) Transaction completed for storing a model with {} statements.", (long) model.size());
                    c.success(transaction);

                } catch (Exception e) {
                    log.warn("Error while loading statements, performing rollback.", e);
                    connection.rollback();
                    c.error(e);
                }
            }

        });
    }


    @Override
    public Mono<Entity> get(IRI id) {
        try (RepositoryConnection connection = repository.getConnection()) {

            RepositoryResult<Statement> statements = connection.getStatements(id, null, null);
            if (!statements.hasNext()) {
                if (log.isDebugEnabled()) log.debug("(Store) Found no statements for IRI: <{}>.", id);
                return Mono.empty();
            }


            Entity entity = new Entity().withResult(statements);

            // embedded level 1
            entity.getModel().objects().stream()
                    .filter(Value::isIRI)
                    .map(value -> connection.getStatements((IRI) value, null, null))
                    .toList()
                    .forEach(entity::withResult);


            if (log.isDebugEnabled())
                log.debug("(Store) Loaded {} statements for entity with IRI: <{}>.", entity.getModel().size(), id);
            return Mono.just(entity);

        } catch (Exception e) {
            log.error("Unknown error while running query", e);
            return Mono.error(e);
        }
    }

    @Override
    public Mono<TupleQueryResult> queryValues(String query) {
        return Mono.create(m -> {
            try (RepositoryConnection connection = repository.getConnection()) {
                TupleQuery q = connection.prepareTupleQuery(QueryLanguage.SPARQL, query);
                try (TupleQueryResult result = q.evaluate()) {
                    m.success(result);
                } catch (Exception e) {
                    log.warn("Error while running value query.", e);
                    m.error(e);
                }
            } catch (MalformedQueryException e) {
                log.warn("Error while parsing query, reason: {}", e.getMessage());
                m.error(e);
            } catch (Exception e) {
                log.error("Unknown error while running query", e);
                m.error(e);
            }
        });
    }

    @Override
    public Mono<TupleQueryResult> queryValues(SelectQuery all) {
        return this.queryValues(all.getQueryString());
    }

    @Override
    public Flux<NamespaceAwareStatement> queryStatements(String query) {
        return Flux.create(c -> {
            try (RepositoryConnection connection = repository.getConnection()) {
                GraphQuery q = connection.prepareGraphQuery(QueryLanguage.SPARQL, query);
                try (GraphQueryResult result = q.evaluate()) {
                    Set<Namespace> namespaces = result.getNamespaces().entrySet().stream()
                            .map(entry -> new SimpleNamespace(entry.getKey(), entry.getValue()))
                            .collect(Collectors.toSet());

                    result.forEach(statement -> c.next(NamespaceAwareStatement.wrap(statement, namespaces)));
                } catch (Exception e) {
                    log.warn("Error while running value query.", e);
                    c.error(e);
                }
            } catch (MalformedQueryException e) {
                log.warn("Error while parsing query", e);
                c.error(new HttpClientErrorException(HttpStatus.BAD_REQUEST, "Invalid query"));
            } catch (Exception e) {
                log.error("Unknown error while running query", e);
                c.error(e);
            } finally {
                c.complete();
            }
        });
    }

    @Override
    public ValueFactory getValueFactory() {
        return this.repository.getValueFactory();
    }

    @Override
    public Mono<Boolean> exists(Resource subj) {
        return Mono.create(m -> {
            try (RepositoryConnection connection = repository.getConnection()) {
                boolean b = connection.hasStatement(subj, RDF.TYPE, null, false);
                m.success(b);
            } catch (Exception e) {
                m.error(e);
            }
        });
    }

    @Override
    public boolean existsSync(Resource subj) {
        try (RepositoryConnection connection = repository.getConnection()) {
            return connection.hasStatement(subj, RDF.TYPE, null, false);
        }
    }


    @Override
    public Mono<Value> type(Resource identifier) {
        return Mono.create(m -> {
            try (RepositoryConnection connection = repository.getConnection()) {
                RepositoryResult<Statement> statements = connection.getStatements(identifier, RDF.TYPE, null, false);

                Value result = null;
                for (Statement st : statements) {
                    // FIXME: not sure if this is a domain exception (which mean it should not be handled here)
                    if (result != null)
                        m.error(new IOException("Duplicate type definitions for resource with identifier " + identifier.stringValue()));
                    else result = st.getObject();
                }
                if (result == null) m.success();
                else m.success(result);


            } catch (Exception e) {
                m.error(e);
            }
        });
    }

    @Override
    public Mono<Void> reset() {
        return Mono.create(m -> {
            try (RepositoryConnection connection = repository.getConnection()) {
                RepositoryResult<Statement> statements = connection.getStatements(null, null, null);
                connection.remove(statements);
                m.success();
            } catch (Exception e) {
                m.error(e);
            }
        });
    }

    @Override
    public Mono<Void> importStatements(Publisher<DataBuffer> bytesPublisher, String mimetype) {
        Optional<RDFParserFactory> parserFactory = RdfUtils.getParserFactory(MimeType.valueOf(mimetype));
        Assert.isTrue(parserFactory.isPresent(), "Unsupported mimetype for parsing the file.");
        RDFParser parser = parserFactory.orElseThrow().getParser();


        Mono<DataBuffer> joined = DataBufferUtils.join(bytesPublisher);


        return joined.flatMap(bytes -> {
            try (RepositoryConnection connection = repository.getConnection()) {
                parser.setRDFHandler(new RDFInserter(connection));
                try (InputStream bais = bytes.asInputStream(true)) {
                    parser.parse(bais);
                } catch (Exception e) {
                    return Mono.error(e);
                }
                return Mono.empty();
            } catch (Exception e) {
                return Mono.error(e);
            }

        }).thenEmpty(Mono.empty());


    }


}
