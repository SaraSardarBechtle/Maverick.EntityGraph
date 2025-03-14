package cougar.graph.services.services;

import cougar.graph.model.security.Authorities;
import cougar.graph.model.vocabulary.Local;
import cougar.graph.services.services.handler.DelegatingTransformer;
import cougar.graph.model.rdf.NamespaceAwareStatement;
import cougar.graph.store.EntityStore;
import cougar.graph.store.rdf.models.Entity;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.sparqlbuilder.core.SparqlBuilder;
import org.eclipse.rdf4j.sparqlbuilder.core.Variable;
import org.eclipse.rdf4j.sparqlbuilder.core.query.Queries;
import org.eclipse.rdf4j.sparqlbuilder.core.query.SelectQuery;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;


@Service
@Slf4j(topic = "graph.service.query")
public class QueryServices {

    private final EntityStore entityStore;

    public QueryServices(EntityStore graph) {
        this.entityStore = graph;
    }




    public Flux<BindingSet> queryValues(String query, Authentication authentication) {
        return this.entityStore.query(query, authentication)
                .doOnSubscribe(subscription -> {
                    if(log.isTraceEnabled()) log.trace("Running query in entity store.");
                });
    }

    public Flux<BindingSet> queryValues(SelectQuery query, Authentication authentication) {
        return this.queryValues(query.getQueryString(), authentication);
    }

    public Flux<NamespaceAwareStatement> queryGraph(String query, Authentication authentication) {
        return this.entityStore.construct(query, authentication)
                .doOnSubscribe(subscription -> {
                    if(log.isTraceEnabled()) log.trace("Running query in entity store.");
                });

    }




    public Flux<Entity> listEntities(Authentication authentication) {
        Variable idVariable = SparqlBuilder.var("id");

        SelectQuery query = Queries.SELECT(idVariable).where(
                idVariable.isA(Local.Entities.TYPE));

        return this.queryValues(query.getQueryString(), authentication)
                .map(bindings -> (IRI) bindings.getValue(idVariable.getVarName()))
                .flatMap(id -> this.entityStore.getEntity(id, authentication));
    }



    @Autowired
    public void linkTransformers(DelegatingTransformer transformers) {
        transformers.registerQueryService(this);
    }


}
